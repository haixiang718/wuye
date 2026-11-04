package com.java110.api.bmo.fee.impl;

import com.alibaba.fastjson.JSONObject;
import com.java110.dto.CommunityMemberDto;
import com.java110.dto.fee.FeeConfigDto;
import com.java110.intf.community.ICommunityInnerServiceSMO;
import com.java110.intf.job.IBillGenerateInnerServiceSMO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

/** Staff may create a draft only for a charge item in their managed community. */
@Service
public class BillGenerationService {
    @Autowired private ICommunityInnerServiceSMO communityInnerServiceSMOImpl;
    @Autowired private IBillGenerateInnerServiceSMO billGenerateInnerServiceSMOImpl;

    public JSONObject generate(String community,String config,String store,String user) {
        if(empty(store)||empty(user)||"-1".equals(user)||"-1".equals(store))return result(403,"请使用有权限的物业员工账号出账");
        if(!identifier(community)||!identifier(config))return result(400,"请选择当前小区的收费项目");
        try {
            CommunityMemberDto query=new CommunityMemberDto();query.setCommunityId(community);query.setMemberId(store);
            query.setMemberTypeCd(CommunityMemberDto.MEMBER_TYPE_PROPERTY);query.setAuditStatusCd(CommunityMemberDto.AUDIT_STATUS_NORMAL);
            List<CommunityMemberDto> rows=communityInnerServiceSMOImpl.getCommunityMembers(query);
            boolean allowed=false;
            if(rows!=null)for(CommunityMemberDto row:rows) {
                if(community.equals(row.getCommunityId())&&store.equals(row.getMemberId())
                        &&CommunityMemberDto.MEMBER_TYPE_PROPERTY.equals(row.getMemberTypeCd())
                        &&CommunityMemberDto.AUDIT_STATUS_NORMAL.equals(row.getAuditStatusCd())&&"0".equals(row.getStatusCd()))allowed=true;
            }
            if(!allowed)return result(403,"无权为该小区生成账单");
            FeeConfigDto command=new FeeConfigDto();command.setCommunityId(community);command.setConfigId(config);
            JSONObject reply=billGenerateInnerServiceSMOImpl.generate(command);
            if(reply==null||reply.getInteger("code")==null)return result(500,"出账结果暂未确认，请刷新账单列表核对后重试");
            return reply;
        } catch(Exception failure) {
            return result(500,"出账暂未完成，请刷新账单列表核对后重试或联系管理员");
        }
    }
    private static boolean identifier(String value){return value!=null&&value.matches("[A-Za-z0-9_-]{1,30}");}
    private static boolean empty(String value){return value==null||value.trim().isEmpty();}
    private static JSONObject result(int code,String message){JSONObject result=new JSONObject();result.put("code",code);result.put("msg",message);return result;}
}
