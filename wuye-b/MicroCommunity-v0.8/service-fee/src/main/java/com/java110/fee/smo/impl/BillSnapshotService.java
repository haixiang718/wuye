package com.java110.fee.smo.impl;

import com.java110.dto.fee.BillDto;
import com.java110.dto.fee.BillOweFeeDto;
import com.java110.dto.fee.BillSnapshotDto;
import com.java110.fee.dao.IFeeServiceDao;
import com.java110.utils.util.BeanConvertUtil;
import com.java110.utils.util.BillCycleWindow;
import com.java110.utils.util.DateUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/** No remote service calls inside this transaction: one configuration row serializes writers. */
@Service
public class BillSnapshotService {
    @Autowired
    private IFeeServiceDao feeServiceDaoImpl;

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public int save(BillSnapshotDto snapshot) {
        validate(snapshot);
        BillDto bill = snapshot.getBill();
        Map<String, Object> scope = new HashMap<>();
        scope.put("communityId", bill.getCommunityId());
        scope.put("configId", bill.getConfigId());
        Map config = feeServiceDaoImpl.lockBillConfig(scope);
        if (config == null || !snapshot.getBillType().equals(config.get("billType"))) {
            throw new IllegalArgumentException("收费配置不存在或出账类型已改变，请重新生成");
        }
        List<Map> current = feeServiceDaoImpl.lockCurrentBills(scope);
        if (current.size() > 1) throw new IllegalStateException("收费项存在多个当前账单，请先核查");
        String previous = current.isEmpty() ? null : String.valueOf(current.get(0).get("billId"));
        // This check is inside the lock: separate jobs cannot both pass it.
        Date cutoff = BillCycleWindow.start(snapshot.getBillType(), new Date());
        if (!current.isEmpty()) {
            Date last;
            try {
                last = DateUtil.getDateFromString(String.valueOf(current.get(0).get("billTime")), DateUtil.DATE_FORMATE_STRING_A);
            } catch (java.text.ParseException invalid) {
                throw new IllegalStateException("历史账单时间无效，请先核查", invalid);
            }
            if (!last.before(cutoff)) throw new IllegalStateException("当前周期已经出过账");
        }
        if (!Objects.equals(previous, snapshot.getPreviousBillId())) {
            throw new IllegalStateException("历史账单已改变，请重新计算后提交");
        }
        if (feeServiceDaoImpl.insertBill(BeanConvertUtil.beanCovertMap(bill)) != 1) {
            throw new IllegalStateException("账单汇总保存失败");
        }
        for (BillOweFeeDto detail : snapshot.getDetails()) {
            if (feeServiceDaoImpl.insertBillOweFees(BeanConvertUtil.beanCovertMap(detail)) != 1) {
                throw new IllegalStateException("账单明细保存失败");
            }
        }
        return 1;
    }

    private void validate(BillSnapshotDto snapshot) {
        if (snapshot == null || snapshot.getBill() == null || snapshot.getDetails() == null) {
            throw new IllegalArgumentException("账单快照不完整");
        }
        BillDto bill = snapshot.getBill();
        required(bill.getBillId()); required(bill.getConfigId()); required(bill.getCommunityId());
        required(bill.getBillTime()); required(bill.getBillName()); required(snapshot.getBillType());
        BillCycleWindow.start(snapshot.getBillType(), new Date());
        if (!"T".equals(bill.getCurBill())) throw new IllegalArgumentException("账单状态错误");
        BigDecimal total = BigDecimal.ZERO, cycleTotal = BigDecimal.ZERO;
        Set<String> ids = new HashSet<>(), fees = new HashSet<>();
        for (BillOweFeeDto detail : snapshot.getDetails()) {
            if (detail == null) throw new IllegalArgumentException("账单明细为空");
            required(detail.getOweId()); required(detail.getFeeId()); required(detail.getOwnerId());
            required(detail.getPayerObjName()); required(detail.getPayerObjType());
            required(detail.getFeeEndTime()); required(detail.getDeadlineTime());
            if (!bill.getBillId().equals(detail.getBillId()) || !bill.getCommunityId().equals(detail.getCommunityId())
                    || !"1000".equals(detail.getState()) || !ids.add(detail.getOweId()) || !fees.add(detail.getFeeId())) {
                throw new IllegalArgumentException("账单明细归属、状态或唯一性错误");
            }
            total = total.add(money(detail.getBillAmountOwed()));
            cycleTotal = cycleTotal.add(money(detail.getAmountOwed()));
        }
        if (total.compareTo(money(bill.getReceivable())) != 0
                || cycleTotal.compareTo(money(bill.getCurReceivable())) != 0) {
            throw new IllegalArgumentException("账单汇总与明细金额不一致");
        }
        money(bill.getReceipts());
    }

    private static void required(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("账单必要字段为空");
    }

    private static BigDecimal money(String value) {
        required(value);
        BigDecimal amount = new BigDecimal(value);
        if (amount.signum() < 0) throw new IllegalArgumentException("账单金额不能为负数");
        return amount;
    }
}
