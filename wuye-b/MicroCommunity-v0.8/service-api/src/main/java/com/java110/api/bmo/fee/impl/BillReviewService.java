package com.java110.api.bmo.fee.impl;

import com.alibaba.fastjson.JSONObject;
import com.java110.dto.CommunityMemberDto;
import com.java110.dto.fee.BillDto;
import com.java110.intf.community.ICommunityInnerServiceSMO;
import com.java110.intf.fee.IFeeInnerServiceSMO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

/** Publishing is a staff action; it never sends a notification or accepts browser amounts. */
@Service
public class BillReviewService {
    @Autowired private ICommunityInnerServiceSMO communityInnerServiceSMOImpl;
    @Autowired private IFeeInnerServiceSMO feeInnerServiceSMOImpl;

    public JSONObject publish(String community,String billId,String remark,String storeId,String userId) {
        if (empty(community) || empty(billId) || empty(storeId) || empty(userId) || "-1".equals(userId))
            return result(403,"请使用有权限的物业员工账号审核");
        if (remark != null && remark.length() > 500) return result(400,"审核意见不能超过500字");
        CommunityMemberDto member = new CommunityMemberDto();
        member.setCommunityId(community); member.setMemberId(storeId);
        member.setMemberTypeCd(CommunityMemberDto.MEMBER_TYPE_PROPERTY);
        member.setAuditStatusCd(CommunityMemberDto.AUDIT_STATUS_NORMAL);
        List<CommunityMemberDto> relations = communityInnerServiceSMOImpl.getCommunityMembers(member);
        if (relations == null || relations.isEmpty()) return result(403,"无权审核该小区账单");
        BillDto query = new BillDto(); query.setCommunityId(community); query.setBillId(billId); query.setCurBill("T");
        List<BillDto> rows = feeInnerServiceSMOImpl.queryBills(query);
        if (rows == null || rows.size() != 1 || !community.equals(rows.get(0).getCommunityId())
                || !billId.equals(rows.get(0).getBillId())) return result(400,"未找到本小区的当期账单");
        if (BillDto.REVIEW_PUBLISHED.equals(rows.get(0).getReviewState())) return result(0,"账单已经审核发布");
        if (!BillDto.REVIEW_DRAFT.equals(rows.get(0).getReviewState())) return result(409,"账单审核状态异常，请刷新核对");
        query.setReviewState(BillDto.REVIEW_PUBLISHED); query.setReviewUserId(userId);
        query.setReviewRemark(remark == null ? "" : remark);
        if (feeInnerServiceSMOImpl.reviewBill(query) == 1) return result(0,"审核发布成功，可向业主推送账单");
        rows = feeInnerServiceSMOImpl.queryBills(query);
        if (rows != null && rows.size() == 1 && BillDto.REVIEW_PUBLISHED.equals(rows.get(0).getReviewState()))
            return result(0,"账单已经审核发布");
        return result(409,"账单已变化或没有明细，请刷新核对后重试");
    }
    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
    private static JSONObject result(int code,String message) {
        JSONObject result = new JSONObject(); result.put("code",code); result.put("msg",message); return result;
    }
}
