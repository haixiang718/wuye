package com.java110.job.smo.impl;

import com.alibaba.fastjson.JSONObject;
import com.java110.dto.fee.BillDto;
import com.java110.dto.fee.FeeConfigDto;
import com.java110.intf.fee.IFeeConfigInnerServiceSMO;
import com.java110.intf.job.IBillGenerateInnerServiceSMO;
import com.java110.job.task.fee.GenerateBillProTemplate;
import com.java110.utils.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/** Generates one trusted configuration through the same calculator and atomic snapshot as scheduled jobs. */
@RestController
public class BillGenerateInnerServiceSMOImpl implements IBillGenerateInnerServiceSMO {
    @Autowired
    private IFeeConfigInnerServiceSMO feeConfigInnerServiceSMOImpl;
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

        GenerateBillProTemplate.GenerationResult generated;
        try {
            generated = generateBillProTemplate.generateManual(config);
        } catch (Exception failure) {
            // Recompute once against persisted coverage. A concurrent winner is only
            // returned when every remaining eligible fee has actually been included.
            try {
                generated = generateBillProTemplate.generateManual(config);
            } catch (Exception retryFailure) {
                failure.addSuppressed(retryFailure);
                throw propagate(failure);
            }
        }
        BillDto bill = generated.bill;
        if (bill == null) {
            JSONObject response = new JSONObject();
            response.put("code", 1);
            response.put("msg", "当前收费项目没有可出账的欠费明细，未生成账单");
            return response;
        }
        return result(bill, generated.existing, generated.supplemental);
    }

    private JSONObject result(BillDto bill, boolean existing, boolean supplemental) {
        JSONObject data = new JSONObject();
        data.put("billId", bill.getBillId());
        data.put("reviewState", bill.getReviewState());
        data.put("existing", existing);
        data.put("supplemental", supplemental);
        data.put("receivable", bill.getReceivable());
        JSONObject response = new JSONObject();
        response.put("code", 0);
        response.put("msg", existing ? "没有新增可出账费用，返回已有账单" : supplemental ? "新增费用的补充待审核账单已生成" : "待审核账单已生成");
        response.put("data", data);
        return response;
    }

    private RuntimeException propagate(Exception failure) {
        return failure instanceof RuntimeException ? (RuntimeException) failure
                : new IllegalStateException("账单生成失败", failure);
    }
}
