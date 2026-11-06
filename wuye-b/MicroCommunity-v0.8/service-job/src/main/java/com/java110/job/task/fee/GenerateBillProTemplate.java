package com.java110.job.task.fee;

import com.java110.core.factory.GenerateCodeFactory;
import com.java110.core.smo.IComputeFeeSMO;
import com.java110.dto.RoomDto;
import com.java110.dto.community.CommunityDto;
import com.java110.dto.fee.*;
import com.java110.dto.owner.OwnerCarDto;
import com.java110.dto.owner.OwnerRoomRelDto;
import com.java110.dto.task.TaskDto;
import com.java110.intf.community.IParkingSpaceInnerServiceSMO;
import com.java110.intf.community.IRoomInnerServiceSMO;
import com.java110.intf.fee.IFeeConfigInnerServiceSMO;
import com.java110.intf.fee.IFeeDetailInnerServiceSMO;
import com.java110.intf.fee.IFeeInnerServiceSMO;
import com.java110.intf.user.IOwnerCarInnerServiceSMO;
import com.java110.intf.user.IOwnerRoomRelInnerServiceSMO;
import com.java110.job.quartz.TaskSystemQuartz;
import com.java110.utils.constant.ResponseConstant;
import com.java110.utils.exception.TaskTemplateException;
import com.java110.utils.util.DateUtil;
import com.java110.utils.util.BillCycleWindow;
import com.java110.utils.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * @ClassName GenerateOwnerBillTemplate
 * @Description TODO  房屋费用账单生成
 * @Author wuxw
 * @Date 2020/6/4 8:33
 * @Version 1.0
 * add by wuxw 2020/6/4
 **/
@Component
public class GenerateBillProTemplate extends TaskSystemQuartz {
    private static final String TASK_ATTR_BILL_TYPE = "10002"; // 出账类型

    private static final String TASK_ATTR_VALUE_MONTH = "002"; //按月出账

    private static final String TASK_ATTR_VALUE_DAY = "003"; //按日出账

    private static final String TASK_ATTR_VALUE_YEAR = "001"; //按年出账

    private static final String TASK_ATTR_VALUE_ONCE_MONTH = "005"; //一次性按月出账

    @Autowired
    private IFeeConfigInnerServiceSMO feeConfigInnerServiceSMOImpl;

    @Autowired
    private IFeeInnerServiceSMO feeInnerServiceSMOImpl;

    @Autowired
    private IFeeDetailInnerServiceSMO feeDetailInnerServiceSMOImpl;

    @Autowired
    private IRoomInnerServiceSMO roomInnerServiceSMOImpl;

    @Autowired
    private IOwnerRoomRelInnerServiceSMO ownerRoomRelInnerServiceSMOImpl;

    @Autowired
    private IParkingSpaceInnerServiceSMO parkingSpaceInnerServiceSMOImpl;

    @Autowired
    private IOwnerCarInnerServiceSMO ownerCarInnerServiceSMOImpl;

    @Autowired
    private IComputeFeeSMO computeFeeSMOImpl;
    @Autowired private com.java110.intf.store.IContractInnerServiceSMO contractInnerServiceSMOImpl;
    @Autowired private com.java110.intf.user.IOwnerInnerServiceSMO ownerInnerServiceSMOImpl;


    @Override
    protected void process(TaskDto taskDto) throws Exception {

        // 获取小区
        List<CommunityDto> communityDtos = getAllCommunity();

        for (CommunityDto communityDto : communityDtos) {
            GenerateBill(taskDto, communityDto);
        }

    }

    /**
     * 根据小区生成账单
     *
     * @param communityDto
     */
    private void GenerateBill(TaskDto taskDto, CommunityDto communityDto) {

        //查询费用项
        FeeConfigDto feeConfigDto = new FeeConfigDto();
        feeConfigDto.setCommunityId(communityDto.getCommunityId());
        feeConfigDto.setBillType(getCurTaskAttr(taskDto, TASK_ATTR_BILL_TYPE).getValue());

        if (StringUtil.isEmpty(feeConfigDto.getBillType())) {
            throw new IllegalArgumentException("配置错误 未拿到属性值");
        }
        List<FeeConfigDto> feeConfigDtos = feeConfigInnerServiceSMOImpl.queryFeeConfigs(feeConfigDto);

        for (FeeConfigDto tmpFeeConfigDto : feeConfigDtos) {
            try {
                GenerateBillByFeeConfig(taskDto, tmpFeeConfigDto);
            } catch (Exception e) {
                logger.error("费用出账失败" + tmpFeeConfigDto.getConfigId(), e);
            }
        }


    }

    /** Called only after the internal service has loaded and validated the trusted configuration. */
    public BillDto generateByFeeConfig(FeeConfigDto feeConfigDto) throws Exception {
        return GenerateBillByFeeConfig(null, feeConfigDto);
    }

    public static final class GenerationResult {
        public final BillDto bill;
        public final boolean existing;
        public final boolean supplemental;
        public final boolean incomplete;
        GenerationResult(BillDto bill, boolean existing, boolean supplemental) {
            this(bill,existing,supplemental,false);
        }
        GenerationResult(BillDto bill, boolean existing, boolean supplemental, boolean incomplete) {
            this.bill = bill; this.existing = existing; this.supplemental = supplemental;
            this.incomplete = incomplete;
        }
    }

    public GenerationResult generateManual(FeeConfigDto config) throws Exception {
        return generate(config, true);
    }
    public GenerationResult generateAutomatic(FeeConfigDto config) throws Exception {
        return generate(config,true,true);
    }

    /**
     * 按费用项来出账
     *
     * @param taskDto
     * @param feeConfigDto
     */
    private BillDto GenerateBillByFeeConfig(TaskDto taskDto, FeeConfigDto feeConfigDto) throws Exception {
        return generate(feeConfigDto, false).bill;
    }

    private GenerationResult generate(FeeConfigDto feeConfigDto, boolean allowSupplement) throws Exception {
        return generate(feeConfigDto,allowSupplement,false);
    }
    private GenerationResult generate(FeeConfigDto feeConfigDto, boolean allowSupplement, boolean isolateInvalidFees) throws Exception {

        //当前费用项是否
        BillDto tmpBillDto = new BillDto();
        tmpBillDto.setCurBill("T");
        tmpBillDto.setConfigId(feeConfigDto.getConfigId());
        tmpBillDto.setCommunityId(feeConfigDto.getCommunityId());
        Date startTime = getDefaultStartTime(feeConfigDto.getBillType());
        List<BillDto> billDtos = feeInnerServiceSMOImpl.queryBills(tmpBillDto);
        if (billDtos == null) throw new IllegalStateException("当前账单查询失败，请重试");
        for (BillDto current : billDtos) {
            if (current == null || StringUtil.isEmpty(current.getBillId()) || StringUtil.isEmpty(current.getBillTime())
                    || !feeConfigDto.getCommunityId().equals(current.getCommunityId())
                    || !feeConfigDto.getConfigId().equals(current.getConfigId()) || !"T".equals(current.getCurBill())) {
                throw new IllegalStateException("当前账单归属或状态异常，请核查");
            }
        }
        Collections.sort(billDtos, new Comparator<BillDto>() {
            public int compare(BillDto a, BillDto b) {
                if (StringUtil.isEmpty(a.getBaseBillId()) != StringUtil.isEmpty(b.getBaseBillId())) {
                    return StringUtil.isEmpty(a.getBaseBillId()) ? -1 : 1;
                }
                int byTime = a.getBillTime().compareTo(b.getBillTime());
                return byTime != 0 ? byTime : a.getBillId().compareTo(b.getBillId());
            }
        });
        if (!billDtos.isEmpty()) {
            String rootId = billDtos.get(0).getBillId();
            for (int i = 0; i < billDtos.size(); i++) {
                if ((i == 0 && !StringUtil.isEmpty(billDtos.get(i).getBaseBillId()))
                        || (i > 0 && !rootId.equals(billDtos.get(i).getBaseBillId()))) {
                    throw new IllegalStateException("当前账单批次关联异常，请先核查");
                }
            }
        }
        boolean unresolved = false;
        List<BillOweFeeDto> currentDetails = new ArrayList<>();
        for (BillDto current : billDtos) {
            unresolved |= !BillDto.REVIEW_PUBLISHED.equals(current.getReviewState());
            BillOweFeeDto detailQuery = new BillOweFeeDto();
            detailQuery.setCommunityId(feeConfigDto.getCommunityId());detailQuery.setBillId(current.getBillId());
            List<BillOweFeeDto> priorDetails = feeInnerServiceSMOImpl.queryBillOweFees(detailQuery);
            if(priorDetails==null)throw new IllegalStateException("已出账费用查询失败，请重试");
            for(BillOweFeeDto detail:priorDetails) {
                if(!current.getBillId().equals(detail.getBillId())||!feeConfigDto.getCommunityId().equals(detail.getCommunityId())
                    ||StringUtil.isEmpty(detail.getFeeId()))throw new IllegalStateException("已出账明细归属异常");
                currentDetails.add(detail);
                unresolved |= !BillOweFeeDto.STATE_FINISH_FEE.equals(detail.getState())
                    && new BigDecimal(detail.getBillAmountOwed()).signum()>0;
            }
        }
        // Unreviewed/rejected/unpaid snapshots stay actionable even across a cycle boundary.
        boolean supplemental = !billDtos.isEmpty()
                && (unresolved || !DateUtil.getDateFromString(billDtos.get(0).getBillTime(), DateUtil.DATE_FORMATE_STRING_A).before(startTime));
        if (supplemental && !allowSupplement) {
            throw new TaskTemplateException(ResponseConstant.RESULT_CODE_ERROR, "已经出过账了");
        }
        BillDto latest = billDtos.isEmpty() ? null : billDtos.get(billDtos.size() - 1);
        Set<String> billedFees = new HashSet<>();
        if (supplemental) {
            for(BillOweFeeDto detail:currentDetails)billedFees.add(detail.getFeeId());
        }

        String billId = GenerateCodeFactory.getGeneratorId(GenerateCodeFactory.CODE_PREFIX_billId);
        BillDto billDto = new BillDto();
        billDto.setBillId(billId);
        if (supplemental) billDto.setBaseBillId(billDtos.get(0).getBillId());
        billDto.setBillName(feeConfigDto.getFeeName() + "-" + DateUtil.getFormatTimeString(new Date(), DateUtil.DATE_FORMATE_STRING_H)
                + (supplemental ? "（补充）" : ""));
        billDto.setBillTime(DateUtil.getFormatTimeString(new Date(), DateUtil.DATE_FORMATE_STRING_A));
        billDto.setCommunityId(feeConfigDto.getCommunityId());
        billDto.setConfigId(feeConfigDto.getConfigId());
        billDto.setCurBill("T");
        billDto.setReviewState(BillDto.REVIEW_DRAFT);
        startTime = (billDtos == null || billDtos.size() < 1) ? getDefaultStartTime(feeConfigDto.getBillType())
                : DateUtil.getDateFromString(billDtos.get(0).getBillTime(), DateUtil.DATE_FORMATE_STRING_A);
        String previousBillId = (billDtos == null || billDtos.isEmpty()) ? null : billDtos.get(0).getBillId();

        FeeDto feeDto = new FeeDto();
        feeDto.setConfigId(feeConfigDto.getConfigId());
        feeDto.setCommunityId(feeConfigDto.getCommunityId());
        List<FeeDto> feeDtos = feeInnerServiceSMOImpl.queryFees(feeDto);

        //没有关联费用不做出账
        if (feeDtos == null || feeDtos.isEmpty()) {
            return new GenerationResult(supplemental ? latest : null, supplemental, false);
        }
        billDto.setReceivable("0");
        billDto.setReceipts("0");
        billDto.setCurReceivable("0");
        List<BillOweFeeDto> details = new ArrayList<>();
        boolean incomplete = false;
        for (FeeDto tmpFeeDto : feeDtos) {
            if (supplemental && covered(tmpFeeDto, currentDetails)) continue;
            try {
                if(isolateInvalidFees) {
                    BillDto isolated= com.alibaba.fastjson.JSON.parseObject(com.alibaba.fastjson.JSON.toJSONString(billDto),BillDto.class);
                    List<BillOweFeeDto> isolatedDetails=new ArrayList<>();
                    generateFee(startTime,tmpFeeDto,isolated,feeConfigDto,isolatedDetails);
                    details.addAll(isolatedDetails);
                    billDto.setReceivable(isolated.getReceivable());billDto.setCurReceivable(isolated.getCurReceivable());billDto.setReceipts(isolated.getReceipts());
                } else generateFee(startTime, tmpFeeDto, billDto, feeConfigDto, details);
            }
            catch(Exception invalid) {
                if(!isolateInvalidFees)throw invalid;
                incomplete=true;
            }
        }

        // Finished, prepaid and zero-price fees must not produce a reviewable empty header.
        if (details.isEmpty()) {
            if(incomplete)throw new IllegalStateException("部分费用资料未完整，请核查业主及计费信息");
            return new GenerationResult(supplemental ? latest : null, supplemental, false);
        }


        //生成本次账单
        Date billEndTime = DateUtil.getCurrentDate();
        billDto.setRemark(DateUtil.getFormatTimeString(startTime, DateUtil.DATE_FORMATE_STRING_A) +
                "-" + DateUtil.getFormatTimeString(billEndTime, DateUtil.DATE_FORMATE_STRING_A) + "账单数据");
        BillSnapshotDto snapshot = new BillSnapshotDto();
        snapshot.setBill(billDto);
        snapshot.setDetails(details);
        snapshot.setBillType(feeConfigDto.getBillType());
        snapshot.setPreviousBillId(previousBillId);
        snapshot.setSupplemental(supplemental);
        try {
            if (feeInnerServiceSMOImpl.saveBillSnapshot(snapshot) != 1) {
                throw new IllegalStateException("账单整批保存失败");
            }
        } catch (RuntimeException failure) {
            if (allowSupplement) {
                try {
                    GenerationResult committed = findCommittedWinner(feeConfigDto, details);
                    if (committed != null) return committed;
                } catch (RuntimeException lookupFailure) { failure.addSuppressed(lookupFailure); }
            }
            throw failure;
        }
        return new GenerationResult(billDto, false, supplemental, incomplete);
    }

    /** Resolve a concurrent/unknown save using the attempted fees, not header order. */
    private GenerationResult findCommittedWinner(FeeConfigDto config, List<BillOweFeeDto> attempted) {
        String winner = null;
        for (BillOweFeeDto detail : attempted) {
            BillOweFeeDto query = new BillOweFeeDto();
            query.setCommunityId(config.getCommunityId());query.setFeeId(detail.getFeeId());query.setCurBill("T");
            query.setFeeEndTime(detail.getFeeEndTime());
            List<BillOweFeeDto> matches = feeInnerServiceSMOImpl.queryBillOweFees(query);
            if (matches == null) return null;
            BillOweFeeDto found = null;
            for(BillOweFeeDto match:matches)if(detail.getFeeEndTime().equals(match.getFeeEndTime())
                &&detail.getDeadlineTime().equals(match.getDeadlineTime())) {
                if(found!=null)return null;found=match;
            }
            if(found==null)return null;
            if (!config.getCommunityId().equals(found.getCommunityId()) || !detail.getFeeId().equals(found.getFeeId())
                    || StringUtil.isEmpty(found.getBillId()) || (winner != null && !winner.equals(found.getBillId()))) return null;
            winner = found.getBillId();
        }
        BillDto query = new BillDto();query.setCommunityId(config.getCommunityId());query.setConfigId(config.getConfigId());
        query.setBillId(winner);query.setCurBill("T");
        List<BillDto> headers = feeInnerServiceSMOImpl.queryBills(query);
        if (headers == null || headers.size() != 1) return null;
        BillDto header = headers.get(0);
        if (!winner.equals(header.getBillId()) || !config.getCommunityId().equals(header.getCommunityId())
                || !config.getConfigId().equals(header.getConfigId()) || !"T".equals(header.getCurBill())) return null;
        return new GenerationResult(header, true, false);
    }

    /**
     * 生成 费用
     *
     * @param feeDto
     */
    private void generateFee(Date startTime, FeeDto feeDto, BillDto billDto, FeeConfigDto feeConfigDto,
                             List<BillOweFeeDto> details) {

        if (!billDto.getCommunityId().equals(feeDto.getCommunityId())
                || !billDto.getConfigId().equals(feeDto.getConfigId())) {
            throw new IllegalArgumentException("费用与收费项归属不一致");
        }

        if (FeeDto.STATE_FINISH.equals(feeDto.getState())) { //判断是否缴费结束
            return;
        }
        Date now = new Date();
        if(feeDto.getStartTime()==null||feeDto.getEndTime()==null)throw new IllegalArgumentException("费用计费日期缺失");
        if(feeDto.getStartTime().after(now)||feeDto.getEndTime().after(now))return;

        // 当前时间 - 开始时间  = 月份
        // 月份/ 周期 = 轮数（向上取整）
        // 轮数 * 周期 * 30 + 开始时间 = 目标 到期时间
        // 到期时间 >= 目标到期时间===>结束
        // 目标到期时间 - 到期时间 = 欠费月份
        // 月份 * 每月单价 = 欠费金额

        Map<String, Object> targetEndDateAndOweMonth = computeFeeSMOImpl.getTargetEndDateAndOweMonth(feeDto);
        Date targetEndDate = (Date) targetEndDateAndOweMonth.get("targetEndDate");
        double oweMonth = (double) targetEndDateAndOweMonth.get("oweMonth");

        //当前时间
        Date billEndTime = DateUtil.getCurrentDate();

        //缴费周期
        long paymentCycle = Long.parseLong(feeConfigDto.getPaymentCycle());


        //说明没有欠费
        if (oweMonth <= 0.0) {
            return;
        }
        double tmpFeePrice = computeFeeSMOImpl.getFeePrice(feeDto);
        feeDto.setFeePrice(tmpFeePrice);
        if (feeDto.getFeePrice() <= 0) {
            return;//这个没有欠费可算
        }

        BillOweFeeDto billOweFeeDto = new BillOweFeeDto();
        billOweFeeDto.setOweId(GenerateCodeFactory.getGeneratorId(GenerateCodeFactory.CODE_PREFIX_oweId));
        billOweFeeDto.setFeeId(feeDto.getFeeId());
        billOweFeeDto.setBillId(billDto.getBillId());
        BigDecimal curFeePrice = BigDecimal.valueOf(feeDto.getFeePrice());
        curFeePrice = curFeePrice.multiply(new BigDecimal(paymentCycle)).setScale(2, BigDecimal.ROUND_HALF_UP);
        billOweFeeDto.setAmountOwed(curFeePrice.toPlainString());
        BigDecimal feePrice = BigDecimal.valueOf(feeDto.getFeePrice());
        feePrice = feePrice.multiply(BigDecimal.valueOf(oweMonth)).setScale(2, BigDecimal.ROUND_HALF_UP);

        billOweFeeDto.setBillAmountOwed(feePrice.toPlainString());
        billOweFeeDto.setFeeEndTime(DateUtil.getFormatTimeString(feeDto.getEndTime(), DateUtil.DATE_FORMATE_STRING_A));
        billOweFeeDto.setCommunityId(feeDto.getCommunityId());
        billOweFeeDto.setPayerObjType(feeDto.getPayerObjType());
        billOweFeeDto.setState("1000");
        billOweFeeDto.setDeadlineTime(DateUtil.getFormatTimeString(targetEndDate, DateUtil.DATE_FORMATE_STRING_A));
        if (FeeDto.PAYER_OBJ_TYPE_ROOM.equals(feeDto.getPayerObjType())) {
            getRoomInfo(billOweFeeDto, feeDto);
        } else if (FeeDto.PAYER_OBJ_TYPE_CAR.equals(feeDto.getPayerObjType())) {
            getParkingSpaceInfo(billOweFeeDto, feeDto);
        } else if(FeeDto.PAYER_OBJ_TYPE_CONTRACT.equals(feeDto.getPayerObjType())) {
            getContractInfo(billOweFeeDto,feeDto);
        } else {
            throw new IllegalArgumentException("当前出账不支持该付款对象类型");
        }

        if (StringUtil.isEmpty(billOweFeeDto.getOwnerId())) {
            throw new IllegalArgumentException("费用未关联有效业主，请补齐后重新出账");
        }
        details.add(billOweFeeDto);

        BigDecimal recFeeBig = new BigDecimal(billDto.getReceivable());
        BigDecimal newRecFee = recFeeBig.add(feePrice);

        //应收
        billDto.setReceivable(newRecFee.toPlainString());

        //当期应收
        BigDecimal curRecFeeBig = new BigDecimal(billDto.getCurReceivable());
        BigDecimal curNewRecFee = curRecFeeBig.add(curFeePrice);
        billDto.setCurReceivable(curNewRecFee.toPlainString());

        FeeDetailDto feeDetailDto = new FeeDetailDto();
        feeDetailDto.setFeeId(feeDto.getFeeId());
        feeDetailDto.setCommunityId(feeDto.getCommunityId());
        feeDetailDto.setStartTime(startTime);
        feeDetailDto.setEndTime(billEndTime);
        List<FeeDetailDto> feeDetailDtos = feeDetailInnerServiceSMOImpl.queryFeeDetails(feeDetailDto);

        BigDecimal curReceipts = new BigDecimal(billDto.getReceipts());
        if (feeDetailDtos != null && feeDetailDtos.size() > 0) {
            for (FeeDetailDto tmpFeeDetailDto : feeDetailDtos) {
                BigDecimal recAmount = new BigDecimal(tmpFeeDetailDto.getReceivedAmount());
                curReceipts = recAmount.add(curReceipts);
            }
        }
        billDto.setReceipts(curReceipts.toPlainString());
    }

    private boolean covered(FeeDto fee, List<BillOweFeeDto> prior) throws Exception {
        for(BillOweFeeDto detail:prior)if(fee.getFeeId().equals(detail.getFeeId())) {
            if(!BillOweFeeDto.STATE_FINISH_FEE.equals(detail.getState()))return true;
            if(fee.getEndTime()==null||StringUtil.isEmpty(detail.getDeadlineTime()))return true;
            Date deadline=DateUtil.getDateFromString(detail.getDeadlineTime(),DateUtil.DATE_FORMATE_STRING_A);
            if(fee.getEndTime().before(deadline))return true;
        }
        return false;
    }

    /**
     * 查询车位信息
     *
     * @param billOweFeeDto
     * @param feeDto
     */
    private void getParkingSpaceInfo(BillOweFeeDto billOweFeeDto, FeeDto feeDto) {


        OwnerCarDto ownerCarDto = new OwnerCarDto();
        ownerCarDto.setWithOwner(true);
        ownerCarDto.setCarId(feeDto.getPayerObjId());
        ownerCarDto.setCommunityId(feeDto.getCommunityId());

        List<OwnerCarDto> ownerCarDtos = ownerCarInnerServiceSMOImpl.queryOwnerCars(ownerCarDto);


        if (ownerCarDtos == null || ownerCarDtos.size() < 1) {
            throw new IllegalArgumentException("费用未关联有效车辆，请补齐后重新出账");
        }

        billOweFeeDto.setPayerObjName(ownerCarDtos.get(0).getCarNum());
        billOweFeeDto.setOwnerId(ownerCarDtos.get(0).getOwnerId());
        billOweFeeDto.setOwnerName(ownerCarDtos.get(0).getOwnerName());
        billOweFeeDto.setOwnerTel(ownerCarDtos.get(0).getLink());
    }

    /**
     * 查询房屋信息
     *
     * @param billOweFeeDto
     * @param feeDto
     */
    private void getRoomInfo(BillOweFeeDto billOweFeeDto, FeeDto feeDto) {
        RoomDto roomDto = new RoomDto();
        roomDto.setRoomId(feeDto.getPayerObjId());
        roomDto.setCommunityId(feeDto.getCommunityId());
        List<RoomDto> roomDtos = roomInnerServiceSMOImpl.queryRooms(roomDto);

        if (roomDtos == null || roomDtos.size() < 1) {
            throw new IllegalArgumentException("费用未关联有效房屋，请补齐后重新出账");
        }

        RoomDto tmpRoomDto = roomDtos.get(0);
        if (RoomDto.ROOM_TYPE_ROOM.equals(tmpRoomDto.getRoomType())) {
            billOweFeeDto.setPayerObjName(tmpRoomDto.getFloorNum() + "栋" + tmpRoomDto.getUnitNum() + "单元" + tmpRoomDto.getRoomNum() + "室");
        } else {
            billOweFeeDto.setPayerObjName(tmpRoomDto.getFloorNum() + "栋" + tmpRoomDto.getRoomNum() + "室");
        }

        OwnerRoomRelDto ownerRoomRelDto = new OwnerRoomRelDto();
        ownerRoomRelDto.setByOwnerInfo(true);
        ownerRoomRelDto.setRoomId(tmpRoomDto.getRoomId());
        ownerRoomRelDto.setCommunityId(feeDto.getCommunityId());
        List<OwnerRoomRelDto> ownerRoomRelDtos = ownerRoomRelInnerServiceSMOImpl.queryOwnerRoomRels(ownerRoomRelDto);

        if (ownerRoomRelDtos == null || ownerRoomRelDtos.size() < 1) {
            throw new IllegalArgumentException("房屋未关联有效业主，请补齐后重新出账");
        }

        billOweFeeDto.setOwnerId(ownerRoomRelDtos.get(0).getOwnerId());
        billOweFeeDto.setOwnerName(ownerRoomRelDtos.get(0).getOwnerName());
        billOweFeeDto.setOwnerTel(ownerRoomRelDtos.get(0).getLink());

    }

    private Date getDefaultStartTime(String billType) {
        return BillCycleWindow.start(billType, new Date());

    }

    private void getContractInfo(BillOweFeeDto detail, FeeDto fee) {
        com.java110.dto.contract.ContractDto query=new com.java110.dto.contract.ContractDto();
        query.setCommunityId(fee.getCommunityId());query.setContractId(fee.getPayerObjId());
        List<com.java110.dto.contract.ContractDto> contracts=contractInnerServiceSMOImpl.queryContracts(query);
        if(contracts==null||contracts.size()!=1)throw new IllegalArgumentException("费用未关联有效合同");
        com.java110.dto.contract.ContractDto contract=contracts.get(0);
        if(!fee.getCommunityId().equals(contract.getCommunityId())||!fee.getPayerObjId().equals(contract.getContractId())
            ||StringUtil.isEmpty(contract.getObjId()))throw new IllegalArgumentException("合同归属或业主信息无效");
        com.java110.dto.owner.OwnerDto ownerQuery=new com.java110.dto.owner.OwnerDto();
        ownerQuery.setCommunityId(fee.getCommunityId());ownerQuery.setOwnerId(contract.getObjId());
        List<com.java110.dto.owner.OwnerDto> owners=ownerInnerServiceSMOImpl.queryOwners(ownerQuery);
        if(owners==null||owners.size()!=1||!contract.getObjId().equals(owners.get(0).getOwnerId())
            ||!fee.getCommunityId().equals(owners.get(0).getCommunityId()))throw new IllegalArgumentException("合同未关联本小区有效业主");
        detail.setOwnerId(owners.get(0).getOwnerId());detail.setOwnerName(owners.get(0).getName());detail.setOwnerTel(owners.get(0).getLink());
        detail.setPayerObjName(contract.getContractName());
    }


}
