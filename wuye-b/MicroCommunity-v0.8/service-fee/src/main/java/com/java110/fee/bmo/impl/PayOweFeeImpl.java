package com.java110.fee.bmo.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.java110.core.annotation.Java110Transactional;
import com.java110.core.factory.GenerateCodeFactory;
import com.java110.core.smo.IComputeFeeSMO;
import com.java110.dto.fee.*;
import com.java110.dto.owner.OwnerDto;
import com.java110.fee.bmo.IPayOweFee;
import com.java110.fee.listener.fee.UpdateFeeInfoListener;
import com.java110.intf.fee.IFeeReceiptDetailInnerServiceSMO;
import com.java110.intf.community.IParkingSpaceInnerServiceSMO;
import com.java110.intf.community.IRoomInnerServiceSMO;
import com.java110.intf.fee.*;
import com.java110.intf.user.IOwnerCarInnerServiceSMO;
import com.java110.po.fee.PayFeeDetailPo;
import com.java110.po.fee.PayFeePo;
import com.java110.po.feeReceipt.FeeReceiptPo;
import com.java110.po.feeReceiptDetail.FeeReceiptDetailPo;
import com.java110.utils.constant.ResponseConstant;
import com.java110.utils.exception.ListenerExecuteException;
import com.java110.utils.lock.DistributedLock;
import com.java110.utils.util.Assert;
import com.java110.utils.util.DateUtil;
import com.java110.utils.util.StringUtil;
import com.java110.vo.ResultVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.ParseException;
import java.util.Date;
import java.util.List;

/**
 * 欠费缴费实现类
 */
@Service
public class PayOweFeeImpl implements IPayOweFee {

    private static Logger logger = LoggerFactory.getLogger(UpdateFeeInfoListener.class);


    @Autowired
    private IFeeInnerServiceSMO feeInnerServiceSMOImpl;

    @Autowired
    private IFeeDetailInnerServiceSMO feeDetailInnerServiceSMOImpl;

    @Autowired
    private IFeeConfigInnerServiceSMO feeConfigInnerServiceSMOImpl;

    @Autowired
    private IRoomInnerServiceSMO roomInnerServiceSMOImpl;

    @Autowired
    private IParkingSpaceInnerServiceSMO parkingSpaceInnerServiceSMOImpl;

    @Autowired
    private IFeeReceiptDetailInnerServiceSMO feeReceiptDetailInnerServiceSMOImpl;

    @Autowired
    private IFeeReceiptInnerServiceSMO feeReceiptInnerServiceSMOImpl;

    @Autowired
    private IOwnerCarInnerServiceSMO ownerCarInnerServiceSMOImpl;

    @Autowired
    private IComputeFeeSMO computeFeeSMOImpl;

    /**
     * 欠费缴费
     *
     * @param reqJson 缴费报文 {"communityId":"7020181217000001","fees":[{"feeId":"902020073149140091","feePrice":90},{"feeId":"902020072844020741","feePrice":1500}]}
     * @return
     */
    @Override
    @Java110Transactional
    public ResponseEntity<String> pay(JSONObject reqJson) {

        //小区ID
        String communityId = reqJson.getString("communityId");

        JSONArray fees = reqJson.getJSONArray("fees");

        JSONObject feeObj = null;
        FeeReceiptPo feeReceiptPo = new FeeReceiptPo();
        feeReceiptPo.setReceiptId(GenerateCodeFactory.getGeneratorId(GenerateCodeFactory.CODE_PREFIX_receiptId));
        feeReceiptPo.setAmount("0.0");

        feeReceiptPo.setPayObjId("-1");
        feeReceiptPo.setPayObjName("未知");

        for (int feeIndex = 0; feeIndex < fees.size(); feeIndex++) {
            feeObj = fees.getJSONObject(feeIndex);
            Assert.hasKeyAndValue(feeObj, "feeId", "未包含费用项ID");
            Assert.hasKeyAndValue(feeObj, "feePrice", "未包含缴费金额");

            feeObj.put("communityId", communityId);
            doPayOweFee(feeObj, feeReceiptPo);
        }
        if (fees.size() > 0) {
            feeReceiptInnerServiceSMOImpl.saveFeeReceipt(feeReceiptPo);
        }
        return ResultVo.createResponseEntity(feeReceiptPo);
    }

    private void doPayOweFee(JSONObject feeObj, FeeReceiptPo feeReceiptPo) {
        //开启全局锁
        String requestId = DistributedLock.getLockUUID();
        String key = this.getClass().getSimpleName() + feeObj.get("feeId");
        try {
            DistributedLock.waitGetDistributedLock(key, requestId);

            addFeeDetail(feeObj, feeReceiptPo);

            modifyFee(feeObj);

            //将有账单下的 状态改为已经缴费
            modifyFeeBill(feeObj);

        } catch (Exception e) {
            logger.error("缴费失败", e);
            throw new IllegalArgumentException("缴费失败" + e);
        } finally {
            DistributedLock.releaseDistributedLock(requestId, key);
        }
    }

    /**
     * @param feeObj
     */
    private void modifyFeeBill(JSONObject feeObj) {

        if (FeeConfigDto.BILL_TYPE_EVERY.equals(feeObj.getString("billType"))) {
            return;
        }
        BillOweFeeDto billOweFeeDto = new BillOweFeeDto();
        billOweFeeDto.setCommunityId(feeObj.getString("communityId"));
        billOweFeeDto.setFeeId(feeObj.getString("feeId"));
        billOweFeeDto.setCurBill("T");
        List<BillOweFeeDto> details = feeInnerServiceSMOImpl.queryBillOweFees(billOweFeeDto);
        if (details == null) throw new IllegalStateException("当前账单明细查询失败");
        if (details.isEmpty()) return;
        Assert.listOnlyOne(details, "费用存在重复当前账单，请先核查");
        billOweFeeDto.setState(BillOweFeeDto.STATE_FINISH_FEE);
        billOweFeeDto.setBillId(details.get(0).getBillId());
        billOweFeeDto.setOweId(details.get(0).getOweId());
        if (feeInnerServiceSMOImpl.updateBillOweFees(billOweFeeDto) != 1) {
            throw new IllegalStateException("缴费后更新对应账单状态失败");
        }

    }

    /**
     * @param feeObj
     */
    private void modifyFee(JSONObject feeObj) throws ParseException {

        PayFeePo payFeePo = new PayFeePo();
        FeeDto feeInfo = (FeeDto) feeObj.get("feeInfo");
        FeeConfigDto feeConfigDto = new FeeConfigDto();
        feeConfigDto.setConfigId(feeInfo.getConfigId());
        feeConfigDto.setCommunityId(feeInfo.getCommunityId());
        List<FeeConfigDto> feeConfigDtos = feeConfigInnerServiceSMOImpl.queryFeeConfigs(feeConfigDto);
        Assert.listOnlyOne(feeConfigDtos, "未找到费用配置");
        feeObj.put("billType", feeConfigDtos.get(0).getBillType());
        feeObj.put("configId", feeConfigDtos.get(0).getConfigId());
        Date targetEndTime = computeFeeSMOImpl.getFeeEndTimeByCycles(feeInfo, feeObj.getString("tmpCycles"));
        String state = computeFeeSMOImpl.getFeeStateByCycles(feeInfo, feeObj.getString("tmpCycles"));
        payFeePo.setFeeId(feeObj.getString("feeId"));
        payFeePo.setEndTime(DateUtil.getFormatTimeString(targetEndTime, DateUtil.DATE_FORMATE_STRING_A));
        payFeePo.setState(state);
        payFeePo.setCommunityId(feeObj.getString("communityId"));
        payFeePo.setStatusCd("0");
        int saveFlag = feeInnerServiceSMOImpl.updateFee(payFeePo);
        if (saveFlag < 1) {
            throw new IllegalArgumentException("缴费失败" + payFeePo.toString());
        }
    }

    /**
     * 添加 费用缴费明细
     *
     * @param paramInJson
     */
    private void addFeeDetail(JSONObject paramInJson, FeeReceiptPo feeReceiptPo) {

        PayFeeDetailPo payFeeDetailPo = new PayFeeDetailPo();
        payFeeDetailPo.setDetailId(GenerateCodeFactory.getGeneratorId(GenerateCodeFactory.CODE_PREFIX_detailId));
        payFeeDetailPo.setPrimeRate("1.00");
        //计算 应收金额
        FeeDto feeDto = new FeeDto();
        feeDto.setFeeId(paramInJson.getString("feeId"));
        feeDto.setCommunityId(paramInJson.getString("communityId"));
        List<FeeDto> feeDtos = feeInnerServiceSMOImpl.queryFees(feeDto);
        if (feeDtos == null || feeDtos.size() != 1) {
            throw new ListenerExecuteException(ResponseConstant.RESULT_CODE_ERROR, "查询费用信息失败，未查到数据或查到多条数据");
        }
        feeDto = feeDtos.get(0);
        paramInJson.put("feeInfo", feeDto);
        payFeeDetailPo.setStartTime(DateUtil.getFormatTimeString(feeDto.getEndTime(), DateUtil.DATE_FORMATE_STRING_A));
        BigDecimal feePrice = new BigDecimal(computeFeeSMOImpl.getFeePrice(feeDto));

        BigDecimal receivedAmount = new BigDecimal(Double.parseDouble(paramInJson.getString("feePrice")));
        BigDecimal cycles = receivedAmount.divide(feePrice, 2, BigDecimal.ROUND_HALF_EVEN);
        paramInJson.put("tmpCycles", cycles);
        payFeeDetailPo.setEndTime(DateUtil.getFormatTimeString(computeFeeSMOImpl.getFeeEndTimeByCycles(feeDto, cycles.doubleValue() + ""), DateUtil.DATE_FORMATE_STRING_A));
        payFeeDetailPo.setCommunityId(paramInJson.getString("communityId"));
        payFeeDetailPo.setCycles(cycles.doubleValue() + "");
        payFeeDetailPo.setReceivableAmount(receivedAmount.doubleValue() + "");
        payFeeDetailPo.setReceivedAmount(receivedAmount.doubleValue() + "");
        payFeeDetailPo.setFeeId(feeDto.getFeeId());

        int saveFeeDetail = feeDetailInnerServiceSMOImpl.saveFeeDetail(payFeeDetailPo);

        if (saveFeeDetail < 1) {
            throw new IllegalArgumentException("保存费用详情失败" + payFeeDetailPo.toString());
        }
        FeeReceiptDetailPo feeReceiptDetailPo = new FeeReceiptDetailPo();
        feeReceiptDetailPo.setAmount(payFeeDetailPo.getReceivableAmount());
        feeReceiptDetailPo.setCommunityId(feeDto.getCommunityId());
        feeReceiptDetailPo.setCycle(payFeeDetailPo.getCycles());
        feeReceiptDetailPo.setDetailId(payFeeDetailPo.getDetailId());
        feeReceiptDetailPo.setEndTime(payFeeDetailPo.getEndTime());
        feeReceiptDetailPo.setFeeId(feeDto.getFeeId());
        feeReceiptDetailPo.setFeeName(StringUtil.isEmpty(feeDto.getImportFeeName()) ? feeDto.getFeeName() : feeDto.getImportFeeName());
        feeReceiptDetailPo.setStartTime(payFeeDetailPo.getStartTime());
        feeReceiptDetailPo.setReceiptId(feeReceiptPo.getReceiptId());
        computeFeeSMOImpl.freshFeeReceiptDetail(feeDto, feeReceiptDetailPo);
        feeReceiptDetailInnerServiceSMOImpl.saveFeeReceiptDetail(feeReceiptDetailPo);

        BigDecimal amount = new BigDecimal(Double.parseDouble(feeReceiptPo.getAmount()));
        amount = amount.add(receivedAmount);
        feeReceiptPo.setAmount(amount.setScale(2, BigDecimal.ROUND_HALF_EVEN).doubleValue() + "");
        feeReceiptPo.setCommunityId(feeReceiptDetailPo.getCommunityId());
        feeReceiptPo.setObjType(feeDto.getPayerObjType());
        feeReceiptPo.setObjId(feeDto.getPayerObjId());
        if (StringUtil.isEmpty(feeReceiptPo.getObjName())) {
            feeReceiptPo.setObjName(computeFeeSMOImpl.getFeeObjName(feeDto));
        }
        OwnerDto ownerDto = computeFeeSMOImpl.getFeeOwnerDto(feeDto);
        feeReceiptPo.setPayObjId(ownerDto.getOwnerId());
        feeReceiptPo.setPayObjName(ownerDto.getName());
    }
}
