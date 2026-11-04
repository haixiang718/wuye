package com.java110.job.smo.impl;

import com.alibaba.fastjson.JSONObject;
import com.java110.dto.fee.BillDto;
import com.java110.dto.fee.FeeConfigDto;
import com.java110.intf.fee.IFeeConfigInnerServiceSMO;
import com.java110.intf.fee.IFeeInnerServiceSMO;
import com.java110.intf.job.IBillGenerateInnerServiceSMO;
import com.java110.job.task.fee.GenerateBillProTemplate;
import com.java110.utils.util.BillCycleWindow;
import com.java110.utils.util.DateUtil;
import com.java110.utils.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

/** Generates one trusted configuration through the same calculator and atomic snapshot as scheduled jobs. */
@RestController
public class BillGenerateInnerServiceSMOImpl implements IBillGenerateInnerServiceSMO {
    @Autowired
    private IFeeConfigInnerServiceSMO feeConfigInnerServiceSMOImpl;
    @Autowired
    private IFeeInnerServiceSMO feeInnerServiceSMOImpl;
    @Autowired
    private GenerateBillProTemplate generateBillProTemplate;

    @Override
    public JSONObject generate(@RequestBody FeeConfigDto input) {
        if (input == null || StringUtil.isEmpty(input.getCommunityId()) || StringUtil.isEmpty(input.getConfigId())) {
            throw new IllegalArgumentException("小区和收费项目不能为空");
        }
        // Never forward caller-controlled prices, owners, cycle settings or review state.
        FeeConfigDto query = new FeeConfigDto();
        query.setCommunityId(input.getCommunityId());
        query.setConfigId(input.getConfigId());
        query.setStatusCd("0");
        List<FeeConfigDto> configs = feeConfigInnerServiceSMOImpl.queryFeeConfigs(query);
        if (configs == null || configs.size() != 1) {
            throw new IllegalArgumentException("当前小区有效收费项目不存在或不唯一");
        }
        FeeConfigDto config = configs.get(0);
        if (config == null || !input.getCommunityId().equals(config.getCommunityId())
                || !input.getConfigId().equals(config.getConfigId()) || !"0".equals(config.getStatusCd())) {
            throw new IllegalArgumentException("收费项目与当前小区归属不一致或已失效");
        }
        if (!Arrays.asList("001", "002", "003", "005").contains(config.getBillType())) {
            throw new IllegalArgumentException("当前收费项目不支持手动出账");
        }

        BillDto existing = currentBill(config);
        if (existing != null) return result(existing, true);
        BillDto bill;
        try {
            bill = generateBillProTemplate.generateByFeeConfig(config);
        } catch (Exception failure) {
            // The snapshot transaction serializes writers. Resolve a concurrent winner only
            // when its persisted current-cycle bill can actually be read back.
            try {
                existing = currentBill(config);
            } catch (RuntimeException readFailure) {
                failure.addSuppressed(readFailure);
                throw propagate(failure);
            }
            if (existing != null) return result(existing, true);
            throw propagate(failure);
        }
        if (bill == null) {
            JSONObject response = new JSONObject();
            response.put("code", 1);
            response.put("msg", "当前收费项目没有可出账的欠费明细，未生成账单");
            return response;
        }
        return result(bill, false);
    }

    private BillDto currentBill(FeeConfigDto config) {
        BillDto query = new BillDto();
        query.setCommunityId(config.getCommunityId());
        query.setConfigId(config.getConfigId());
        query.setCurBill("T");
        query.setCurBillTime(DateUtil.getFormatTimeString(BillCycleWindow.start(config.getBillType(), new Date()),
                DateUtil.DATE_FORMATE_STRING_A));
        List<BillDto> bills = feeInnerServiceSMOImpl.queryBills(query);
        if (bills == null || bills.isEmpty()) return null;
        if (bills.size() != 1) throw new IllegalStateException("当前收费项目存在多个有效账单，请先核查");
        BillDto bill = bills.get(0);
        if (bill == null || StringUtil.isEmpty(bill.getBillId())
                || !config.getCommunityId().equals(bill.getCommunityId())
                || !config.getConfigId().equals(bill.getConfigId()) || !"T".equals(bill.getCurBill())) {
            throw new IllegalStateException("当前账单归属或状态异常，请先核查");
        }
        return bill;
    }

    private JSONObject result(BillDto bill, boolean existing) {
        JSONObject data = new JSONObject();
        data.put("billId", bill.getBillId());
        data.put("reviewState", bill.getReviewState());
        data.put("existing", existing);
        data.put("receivable", bill.getReceivable());
        JSONObject response = new JSONObject();
        response.put("code", 0);
        response.put("msg", existing ? "当前周期已出账，返回已有账单" : "待审核账单已生成");
        response.put("data", data);
        return response;
    }

    private RuntimeException propagate(Exception failure) {
        return failure instanceof RuntimeException ? (RuntimeException) failure
                : new IllegalStateException("账单生成失败", failure);
    }
}
