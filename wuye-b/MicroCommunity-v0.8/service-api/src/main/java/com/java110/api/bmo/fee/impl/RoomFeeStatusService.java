package com.java110.api.bmo.fee.impl;

import com.java110.core.smo.IComputeFeeSMO;
import com.java110.dto.RoomDto;
import com.java110.dto.fee.BillOweFeeDto;
import com.java110.dto.fee.FeeDto;
import com.java110.intf.fee.IFeeInnerServiceSMO;
import com.java110.vo.api.ApiRoomDataVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/** Read-only indicators for the current page; never infer payment from room occupancy. */
@Service
public class RoomFeeStatusService {
    private static final Logger logger = LoggerFactory.getLogger(RoomFeeStatusService.class);
    @Autowired private IFeeInnerServiceSMO feeInnerServiceSMOImpl;
    @Autowired private IComputeFeeSMO computeFeeSMOImpl;

    public void populate(String communityId, List<RoomDto> rooms, List<ApiRoomDataVo> views) {
        if (rooms == null || rooms.isEmpty() || views == null) return;
        Map<String, RoomDto> byId = new LinkedHashMap<>();
        Map<String, String> statuses = new HashMap<>();
        for (RoomDto room : rooms) {
            byId.put(room.getRoomId(), room);
            statuses.put(room.getRoomId(), "NO_BILLS");
        }
        if (communityId == null || communityId.trim().isEmpty()) {
            for (ApiRoomDataVo view : views) view.setFeePaymentStatus("UNKNOWN");
            return;
        }
        String[] ids = byId.keySet().toArray(new String[0]);
        try {
            FeeDto query = new FeeDto();
            query.setCommunityId(communityId);
            query.setPayerObjType(FeeDto.PAYER_OBJ_TYPE_ROOM);
            query.setPayerObjIds(ids);
            List<FeeDto> fees = feeInnerServiceSMOImpl.queryFees(query);
            BillOweFeeDto snapshotQuery = new BillOweFeeDto();
            snapshotQuery.setCommunityId(communityId);
            snapshotQuery.setPayerObjType(FeeDto.PAYER_OBJ_TYPE_ROOM);
            snapshotQuery.setPayObjIds(ids);
            List<BillOweFeeDto> snapshots = feeInnerServiceSMOImpl.queryBillOweFees(snapshotQuery);
            if (fees == null || snapshots == null) throw new IllegalStateException("Missing fee query result");
            long now = System.currentTimeMillis();
            for (FeeDto fee : fees) {
                if (!communityId.equals(fee.getCommunityId()) || !byId.containsKey(fee.getPayerObjId())
                        || !FeeDto.PAYER_OBJ_TYPE_ROOM.equals(fee.getPayerObjType())) continue;
                String state = "SETTLED";
                try {
                    if (FeeDto.STATE_DOING.equals(fee.getState())) {
                        if (fee.getEndTime() == null) throw new IllegalStateException("Missing fee date");
                        if (fee.getEndTime().getTime() <= now) {
                            // Match the existing arrears payment calculation, using the already loaded room.
                            computeFeeSMOImpl.computeEveryOweFee(fee, byId.get(fee.getPayerObjId()));
                            double amount = fee.getFeePrice();
                            if (!Double.isFinite(amount) || amount < 0) throw new IllegalStateException("Invalid fee amount");
                            if (amount > 0) state = "OUTSTANDING";
                        }
                    } else if (!FeeDto.STATE_FINISH.equals(fee.getState())) state = "UNKNOWN";
                } catch (RuntimeException error) {
                    state = "UNKNOWN";
                    logger.warn("Room fee indicator calculation failed; leaving status unconfirmed");
                }
                merge(statuses, fee.getPayerObjId(), state);
            }
            // Include frozen bill balances, even after notification or when a fee is now finished.
            for (BillOweFeeDto bill : snapshots) {
                if (!communityId.equals(bill.getCommunityId()) || !byId.containsKey(bill.getPayObjId())
                        || !FeeDto.PAYER_OBJ_TYPE_ROOM.equals(bill.getPayerObjType())) continue;
                String state = "SETTLED";
                if (!BillOweFeeDto.STATE_FINISH_FEE.equals(bill.getState())) {
                    try {
                        if (!BillOweFeeDto.STATE_WILL_FEE.equals(bill.getState())
                                && !BillOweFeeDto.STATE_SEND_OWNER.equals(bill.getState())) {
                            throw new IllegalStateException("Unknown bill state");
                        }
                        BigDecimal amount = new BigDecimal(bill.getBillAmountOwed());
                        state = amount.signum() > 0 ? "OUTSTANDING" : amount.signum() < 0 ? "UNKNOWN" : "SETTLED";
                    } catch (RuntimeException error) { state = "UNKNOWN"; }
                }
                merge(statuses, bill.getPayObjId(), state);
            }
        } catch (RuntimeException error) {
            // A failed read must not turn every row green or prevent the room list from loading.
            for (String id : ids) statuses.put(id, "UNKNOWN");
            logger.warn("Room fee indicator query failed; leaving statuses unconfirmed");
        }
        for (ApiRoomDataVo view : views) view.setFeePaymentStatus(statuses.get(view.getRoomId()));
    }

    private void merge(Map<String, String> statuses, String id, String next) {
        String current = statuses.get(id);
        if ("OUTSTANDING".equals(current)) return;
        if ("OUTSTANDING".equals(next) || !"UNKNOWN".equals(current)) statuses.put(id, next);
    }
}
