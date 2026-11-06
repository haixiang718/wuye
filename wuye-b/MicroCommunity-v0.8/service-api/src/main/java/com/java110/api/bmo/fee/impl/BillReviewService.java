package com.java110.api.bmo.fee.impl;

import com.alibaba.fastjson.JSONObject;
import com.java110.dto.CommunityMemberDto;
import com.java110.dto.fee.BillDto;
import com.java110.dto.store.StoreUserDto;
import com.java110.intf.community.ICommunityInnerServiceSMO;
import com.java110.intf.fee.IFeeInnerServiceSMO;
import com.java110.intf.store.IStoreInnerServiceSMO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

/** Publishing is a staff action; it never sends a notification or accepts browser amounts. */
@Service
public class BillReviewService {
    @Autowired private ICommunityInnerServiceSMO communityInnerServiceSMOImpl;
    @Autowired private IFeeInnerServiceSMO feeInnerServiceSMOImpl;
    @Autowired private IStoreInnerServiceSMO storeInnerServiceSMOImpl;

    public JSONObject publish(String community,String billId,String remark,String storeId,String userId) {
        return transition(community,billId,remark,storeId,userId,BillDto.REVIEW_DRAFT,BillDto.REVIEW_PUBLISHED);
    }
    public JSONObject reject(String community,String billId,String reason,String storeId,String userId) {
        if (empty(reason)) return result(400,"请填写审核不通过原因");
        return transition(community,billId,reason,storeId,userId,BillDto.REVIEW_DRAFT,BillDto.REVIEW_REJECTED);
    }
    public JSONObject resubmit(String community,String billId,String remark,String storeId,String userId) {
        return transition(community,billId,remark,storeId,userId,BillDto.REVIEW_REJECTED,BillDto.REVIEW_DRAFT);
    }
    private JSONObject transition(String community,String billId,String remark,String storeId,String userId,String from,String to) {
        if (!identifier(community) || !identifier(billId) || !identifier(storeId) || !identifier(userId)
                || "-1".equals(storeId) || "-1".equals(userId) || !allowed(community,storeId,userId))
            return result(403,"请使用有权限的物业员工账号审核");
        if (remark != null && remark.length() > 500) return result(400,"审核意见不能超过500字");
        BillDto query = new BillDto(); query.setCommunityId(community); query.setBillId(billId); query.setCurBill("T");
        List<BillDto> rows = feeInnerServiceSMOImpl.queryBills(query);
        if (rows == null || rows.size() != 1 || rows.get(0) == null || !community.equals(rows.get(0).getCommunityId())
                || !billId.equals(rows.get(0).getBillId()) || !"T".equals(rows.get(0).getCurBill())) return result(400,"未找到本小区的当期账单");
        if (BillDto.REVIEW_PUBLISHED.equals(to) && to.equals(rows.get(0).getReviewState())) return success(billId,to,"账单已经审核发布");
        if (!from.equals(rows.get(0).getReviewState())) return result(409,"账单审核状态已变化，请刷新核对");
        query.setReviewState(to); query.setReviewUserId(userId);
        query.setReviewRemark(remark == null ? "" : remark);
        if (feeInnerServiceSMOImpl.reviewBill(query) == 1) return success(billId,to,
            BillDto.REVIEW_PUBLISHED.equals(to)?"审核发布成功，可向业主推送账单":BillDto.REVIEW_REJECTED.equals(to)?"账单审核不通过，已记录原因":"账单已重新提交审核");
        // A failed compare-and-set must never turn a competing action into this action's success.
        return result(409,"账单已变化或没有明细，请刷新核对后重试");
    }
    private boolean allowed(String community,String store,String user) {
        try {
            StoreUserDto staffQuery=new StoreUserDto();staffQuery.setStoreId(store);staffQuery.setUserId(user);staffQuery.setStatusCd("0");
            List<StoreUserDto> staff=storeInnerServiceSMOImpl.getStoreUserInfo(staffQuery);
            boolean employee=false;
            // The SQL filters active su.user_id by this trusted user parameter, turning its left join into a membership check.
            // Its returned userId is the store owner, and the unaliased membership ID is omitted by DTO conversion.
            if(staff!=null)for(StoreUserDto row:staff)if(row!=null&&store.equals(row.getStoreId())&&!"48002".equals(row.getState()))employee=true;
            if(!employee)return false;
            CommunityMemberDto member=new CommunityMemberDto();member.setCommunityId(community);member.setMemberId(store);
            member.setMemberTypeCd(CommunityMemberDto.MEMBER_TYPE_PROPERTY);member.setAuditStatusCd(CommunityMemberDto.AUDIT_STATUS_NORMAL);
            List<CommunityMemberDto> relations=communityInnerServiceSMOImpl.getCommunityMembers(member);
            if(relations!=null)for(CommunityMemberDto row:relations)if(row!=null&&community.equals(row.getCommunityId())&&store.equals(row.getMemberId())
                &&CommunityMemberDto.MEMBER_TYPE_PROPERTY.equals(row.getMemberTypeCd())&&CommunityMemberDto.AUDIT_STATUS_NORMAL.equals(row.getAuditStatusCd())&&"0".equals(row.getStatusCd()))return true;
        } catch(Exception unavailable) {return false;}
        return false;
    }
    private static boolean identifier(String value) {return value!=null&&value.matches("[A-Za-z0-9_-]{1,30}");}
    private static JSONObject success(String billId,String state,String message) {
        JSONObject out=result(0,message),data=new JSONObject();data.put("billId",billId);data.put("reviewState",state);out.put("data",data);return out;
    }
    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
    private static JSONObject result(int code,String message) {
        JSONObject result = new JSONObject(); result.put("code",code); result.put("msg",message); return result;
    }
}
