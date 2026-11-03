package com.java110.front.smo.login.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.java110.core.base.smo.front.AbstractFrontServiceSMO;
import com.java110.core.context.IPageData;
import com.java110.core.context.PageData;
import com.java110.core.factory.AuthenticationFactory;
import com.java110.core.factory.WechatFactory;
import com.java110.core.factory.OwnerWechatSession;
import com.java110.dto.owner.OwnerAppUserDto;
import com.java110.dto.app.AppDto;
import com.java110.dto.smallWeChat.SmallWeChatDto;
import com.java110.dto.user.UserDto;
import com.java110.front.properties.WechatAuthProperties;
import com.java110.front.smo.login.IOwnerAppLoginSMO;
import com.java110.utils.cache.CommonCache;
import com.java110.utils.constant.*;
import com.java110.utils.exception.SMOException;
import com.java110.utils.util.Assert;
import com.java110.utils.util.Base64Convert;
import com.java110.utils.util.BeanConvertUtil;
import com.java110.utils.util.StringUtil;
import com.java110.vo.ResultVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URL;
import java.net.URLEncoder;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * wx登录
 */
@Service("ownerAppLoginSMOImpl")
public class OwnerAppLoginSMOImpl extends AbstractFrontServiceSMO implements IOwnerAppLoginSMO {

    private final static Logger logger = LoggerFactory.getLogger(OwnerAppLoginSMOImpl.class);

    private final static int expireTime = 7200;

    private final static int LOGIN_PAGE = 1;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private RestTemplate outRestTemplate;

    @Autowired
    private WechatAuthProperties wechatAuthProperties;

    @Override
    public ResponseEntity<String> doLogin(IPageData pd) throws SMOException {
        JSONObject paramIn = JSONObject.parseObject(pd.getReqData());
        Assert.hasKeyAndValue(paramIn, "username", "请求报文中未包含用户名");
        Assert.hasKeyAndValue(paramIn, "password", "请求报文中未包含密码");
        ResponseEntity<String> responseEntity;

        JSONObject loginInfo = JSONObject.parseObject(pd.getReqData());
        boolean loginByPhone = false;
        if (paramIn.containsKey("loginByPhone")) {
            loginByPhone = paramIn.getBoolean("loginByPhone");
        }

        loginInfo.put("passwd", AuthenticationFactory.passwdMd5(loginInfo.getString("password")));
        UserDto userDto = new UserDto();
        userDto.setUserName(loginInfo.getString("username"));
        userDto.setPassword(loginInfo.getString("password"));
        userDto.setLoginByPhone(loginByPhone);
        userDto.setLevelCd("02");
        userDto = super.postForApi(pd, userDto, ServiceCodeConstant.SERVICE_CODE_USER_LOGIN, UserDto.class);

        if (userDto == null) {
            responseEntity = new ResponseEntity<>("用户名或密码错误", HttpStatus.BAD_REQUEST);
            return responseEntity;
        }

        //根据用户查询商户信息
        String userId = userDto.getUserId();

        pd = PageData.newInstance().builder(userId, "", "", pd.getReqData(),
                "", "", "", "",
                pd.getAppId());
        OwnerAppUserDto ownerAppUserDto = new OwnerAppUserDto();
        ownerAppUserDto.setUserId(userId);
        List<OwnerAppUserDto> ownerAppUserDtos = approvedBindings(super.getForApis(pd, ownerAppUserDto, ServiceCodeConstant.LIST_APPUSERBINDINGOWNERS, OwnerAppUserDto.class));


        if (ownerAppUserDtos == null || ownerAppUserDtos.size() < 1) {
            responseEntity = new ResponseEntity<>("尚无审核通过的房产绑定，请联系物业审核", HttpStatus.FORBIDDEN);
            return responseEntity;
        }

        JSONObject appUser = JSONObject.parseObject(JSONObject.toJSONString(ownerAppUserDtos.get(0)));
        appUser.put("userId", userId);
        appUser.put("userName", paramIn.getString("username"));
        JSONObject paramOut = new JSONObject();
        paramOut.put("result", 0);
        paramOut.put("owner", appUser);
        paramOut.put("token", userDto.getToken());

        userDto = new UserDto();
        userDto.setUserId(ownerAppUserDtos.get(0).getUserId());
        UserDto tmpUserDto = super.getForApi(pd, userDto, ServiceCodeConstant.QUERY_USER_SECRET, UserDto.class);
        paramOut.put("key", tmpUserDto.getKey());

        String appId = pd.getAppId();

        if ("992020061452450002".equals(appId)) { //公众号
            return wechat(pd, paramIn, paramOut, userId, ownerAppUserDtos);
        } else if ("992019111758490006".equals(appId)) { //小程序
            return mina(pd, paramIn, paramOut, userId, ownerAppUserDtos);
        } else {//app
            return new ResponseEntity<>(paramOut.toJSONString(), HttpStatus.OK);
        }
    }

    @Override
    public ResponseEntity<String> doLoginByKey(IPageData pd) throws SMOException {
        JSONObject paramIn = JSONObject.parseObject(pd.getReqData());
        String wechatSession = null;
        String preferredMemberId = null;
        JSONObject wechatIdentity = null;
        if (paramIn.containsKey("authTicket")) {
            String ticket = CommonCache.getAndRemoveValue("owner-wechat-login:" + paramIn.getString("authTicket"));
            if (StringUtil.isEmpty(ticket)) {
                return new ResponseEntity<>("登录票据已失效，请重新授权", HttpStatus.UNAUTHORIZED);
            }
            JSONObject loginTicket = JSONObject.parseObject(ticket);
            if (!pd.getAppId().equals(loginTicket.getString("appId"))) {
                return new ResponseEntity<>("登录票据与应用不匹配", HttpStatus.UNAUTHORIZED);
            }
            wechatSession = loginTicket.getString("wechatSession");
            preferredMemberId = loginTicket.getString("preferredMemberId");
            if (!StringUtil.isEmpty(wechatSession)) {
                try { wechatIdentity=OwnerWechatSession.read(wechatSession); }
                catch (IllegalArgumentException expired) { return new ResponseEntity<>(expired.getMessage(),HttpStatus.UNAUTHORIZED); }
                if (StringUtil.isEmpty(loginTicket.getString("key"))) {
                    JSONObject guest=new JSONObject();guest.put("code",0);guest.put("hasBinding",false);
                    guest.put("owner",null);guest.put("wechatSession",wechatSession);guest.put("expiresIn",Math.max(1,(wechatIdentity.getLongValue("expiresAt")-System.currentTimeMillis())/1000));
                    return new ResponseEntity<>(guest.toJSONString(),HttpStatus.OK);
                }
            }
            paramIn.put("key", loginTicket.getString("key"));
        }
        Assert.hasKeyAndValue(paramIn, "key", "请求报文中未包含临时秘钥");
        ResponseEntity<String> responseEntity;

        JSONObject loginInfo = JSONObject.parseObject(pd.getReqData());

        UserDto userDto = new UserDto();
        userDto.setKey(paramIn.getString("key"));
        userDto = super.postForApi(pd, userDto, ServiceCodeConstant.SERVICE_CODE_USER_LOGIN, UserDto.class);
        if (userDto == null) {
            return new ResponseEntity<>("登录票据已失效，请重新授权", HttpStatus.UNAUTHORIZED);
        }
        //根据用户查询商户信息
        String userId = userDto.getUserId();

        pd = PageData.newInstance().builder(userId, "", "", pd.getReqData(),
                "", "", "", "",
                pd.getAppId());
        OwnerAppUserDto ownerAppUserDto = new OwnerAppUserDto();
        ownerAppUserDto.setUserId(userId);
        List<OwnerAppUserDto> ownerAppUserDtos = approvedBindings(super.getForApis(pd, ownerAppUserDto, ServiceCodeConstant.LIST_APPUSERBINDINGOWNERS, OwnerAppUserDto.class));

        if(wechatIdentity!=null)ownerAppUserDtos=wechatBindings(ownerAppUserDtos,wechatIdentity.getString("openId"),wechatIdentity.getString("communityId"));


        if (ownerAppUserDtos == null || ownerAppUserDtos.size() < 1) {
            responseEntity = new ResponseEntity<>("尚无审核通过的房产绑定，请联系物业审核", HttpStatus.FORBIDDEN);
            return responseEntity;
        }

        JSONArray owners = new JSONArray();
        JSONObject appUser = null;
        for (OwnerAppUserDto binding : ownerAppUserDtos) {
            JSONObject owner = JSONObject.parseObject(JSONObject.toJSONString(binding));
            owner.put("userId", userId);
            owner.put("userName", paramIn.getString("username"));
            owner.remove("openId");owner.remove("idCard");
            owners.add(owner);
            // Preference comes only from the consumed server ticket and must match a verified binding.
            if (!StringUtil.isEmpty(preferredMemberId) && preferredMemberId.equals(binding.getMemberId())) appUser = owner;
        }
        if (appUser == null) appUser = owners.getJSONObject(0);
        JSONObject paramOut = new JSONObject();
        paramOut.put("code", 0);
        paramOut.put("msg", "成功");
        paramOut.put("owner", appUser);
        paramOut.put("owners", owners);
        paramOut.put("token", userDto.getToken());
        paramOut.put("hasBinding",true);
        if (!StringUtil.isEmpty(wechatSession)) {
            paramOut.put("wechatSession",wechatSession);paramOut.put("expiresIn",Math.max(1,(wechatIdentity.getLongValue("expiresAt")-System.currentTimeMillis())/1000));
        }
        if (!paramIn.containsKey("authTicket")) {
            paramOut.put("key", userDto.getKey());
        }
        // The selected owner also appears in owners; browsers require complete JSON objects.
        return new ResponseEntity<>(JSONObject.toJSONString(paramOut, SerializerFeature.DisableCircularReferenceDetect), HttpStatus.OK);

    }

    @Override
    public ResponseEntity<String> getPageAccessToken(IPageData pd, HttpServletRequest request) throws SMOException {
        JSONObject paramIn = JSONObject.parseObject(pd.getReqData());
        String authCode = paramIn.getString("code");
        String state = paramIn.getString("state");
        String urlCode = paramIn.getString("urlCode");
        if (StringUtil.isEmpty(urlCode) || !urlCode.equals(state)) {
            return ResultVo.redirectPage("/");
        }
        String paramStr = CommonCache.getAndRemoveValue(urlCode);

        if (StringUtil.isEmpty(paramStr)) {
            return ResultVo.redirectPage("/");
        }

        JSONObject param = JSONObject.parseObject(paramStr);
        String redirectUrl = param.getString("redirectUrl");
        String errorUrl = param.getString("errorUrl");
        if (StringUtil.isEmpty(authCode)) {
            return ResultVo.redirectPage(errorUrl);
        }
        pd = PageData.newInstance().builder("-1", "", "", pd.getReqData(), "", "", "", "", param.getString("internalAppId"));
        SmallWeChatDto smallWeChatDto = getPublicWechat(pd, param.getString("wechatAppId"));

        String url = WechatConstant.APP_GET_ACCESS_TOKEN_URL.replace("APPID", smallWeChatDto.getAppId())
                .replace("SECRET", smallWeChatDto.getAppSecret())
                .replace("CODE", authCode);

        ResponseEntity<String> paramOut = requestOAuth(url);

        if (paramOut == null || paramOut.getStatusCode() != HttpStatus.OK) {
            return ResultVo.redirectPage(errorUrl);

        }

        JSONObject paramObj = JSONObject.parseObject(paramOut.getBody());
        if (paramObj == null || StringUtil.isEmpty(paramObj.getString("openid"))
                || StringUtil.isEmpty(paramObj.getString("access_token"))) {
            return ResultVo.redirectPage(errorUrl);
        }

        //获取 openId
        String openId = paramObj.getString("openid");
        String userinfo_url = WechatConstant.APP_GET_USER_INFO_URL
                .replace("ACCESS_TOKEN", paramObj.getString("access_token"))
                .replace("OPENID", openId);

        ResponseEntity<String> userinfo_paramOut = requestOAuth(userinfo_url);
        if (userinfo_paramOut == null || userinfo_paramOut.getStatusCode() != HttpStatus.OK) {
            return ResultVo.redirectPage(errorUrl);
        }
        JSONObject userinfo_paramObj = JSONObject.parseObject(userinfo_paramOut.getBody());
        if (userinfo_paramObj == null || userinfo_paramObj.containsKey("errcode")) {
            return ResultVo.redirectPage(errorUrl);
        }

        //处理昵称有特殊符号导致 入库失败问题
        String nickname = userinfo_paramObj.getString("nickname");
        userinfo_paramObj.put("nickname", Base64Convert.byteToBase64((nickname == null ? "" : nickname).getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        int loginFlag = param.getIntValue("loginFlag");

        String identitySession=OwnerWechatSession.issue(smallWeChatDto.getAppId(),openId,smallWeChatDto.getObjId());

        //判断当前openId 是否绑定了业主
        pd = PageData.newInstance().builder("-1", "", "", pd.getReqData(),
                "", "", "", "",
                pd.getAppId());
        OwnerAppUserDto ownerAppUserDto = new OwnerAppUserDto();
        ownerAppUserDto.setOpenId(openId);
        ownerAppUserDto.setAppType(OwnerAppUserDto.APP_TYPE_WECHAT);
        if(!StringUtil.isEmpty(smallWeChatDto.getObjId()))ownerAppUserDto.setCommunityId(smallWeChatDto.getObjId());
        List<OwnerAppUserDto> ownerAppUserDtos = wechatBindings(super.getForApis(pd, ownerAppUserDto, ServiceCodeConstant.LIST_APPUSERBINDINGOWNERS, OwnerAppUserDto.class),openId,smallWeChatDto.getObjId());

        if (ownerAppUserDtos == null || ownerAppUserDtos.size() < 1) {
            return identityRedirect(redirectUrl,pd.getAppId(),identitySession,null);
        }

        // String accessToken = paramObj.getString("access_token");//暂时不用
        Map userMap = new HashMap();
        userMap.put(CommonConstant.LOGIN_USER_ID, ownerAppUserDtos.get(0).getUserId());
        userMap.put(CommonConstant.LOGIN_USER_NAME, ownerAppUserDtos.get(0).getAppUserName());
        String token = "";
        try {
            token = AuthenticationFactory.createAndSaveToken(userMap);
            pd.setToken(token);
        } catch (Exception e) {
            logger.error("创建token失败");
        }
        //查询用户key
        UserDto userDto = new UserDto();
        userDto.setUserId(ownerAppUserDtos.get(0).getUserId());
        UserDto tmpUserDto = super.getForApi(pd, userDto, ServiceCodeConstant.QUERY_USER_SECRET, UserDto.class);

        if (tmpUserDto == null || StringUtil.isEmpty(tmpUserDto.getKey())) {
            return identityRedirect(redirectUrl,pd.getAppId(),identitySession,null);
        }
        // Only a short-lived ticket crosses the browser redirect; the user key stays in Redis.
        String authTicket = UUID.randomUUID().toString();
        JSONObject loginTicket = new JSONObject();
        loginTicket.put("key", tmpUserDto.getKey());
        loginTicket.put("appId", pd.getAppId());
        loginTicket.put("wechatSession",identitySession);
        CommonCache.setValue("owner-wechat-login:" + authTicket, loginTicket.toJSONString(), 300);
        redirectUrl = appendOAuthParameter(redirectUrl, "authTicket", authTicket);
        return ResultVo.redirectPage(redirectUrl);

    }

    private ResponseEntity<String> identityRedirect(String redirectUrl,String appId,String identitySession,String key) {
        String ticket=UUID.randomUUID().toString();JSONObject value=new JSONObject();
        value.put("appId",appId);value.put("wechatSession",identitySession);if(key!=null)value.put("key",key);
        CommonCache.setValue("owner-wechat-login:"+ticket,value.toJSONString(),300);
        return ResultVo.redirectPage(appendOAuthParameter(redirectUrl,"authTicket",ticket));
    }

    private List<OwnerAppUserDto> wechatBindings(List<OwnerAppUserDto> rows,String openId,String communityId) {
        List<OwnerAppUserDto> result=new java.util.ArrayList<>();
        if(rows!=null)for(OwnerAppUserDto row:rows)if("12000".equals(row.getState())&&OwnerAppUserDto.APP_TYPE_WECHAT.equals(row.getAppType())
                &&openId.equals(row.getOpenId())&&(StringUtil.isEmpty(communityId)||communityId.equals(row.getCommunityId())))result.add(row);
        return result;
    }

    @Override
    public ResponseEntity<String> bindOwnerWechat(IPageData pd) {
        try {
            JSONObject input=JSONObject.parseObject(pd.getReqData());
            try { OwnerWechatSession.read(input.getString("wechatSession")); }
            catch(IllegalArgumentException expired){return new ResponseEntity<>(expired.getMessage(),HttpStatus.UNAUTHORIZED);}
            JSONObject command=new JSONObject();
            for(String name:new String[]{"wechatSession","name","phone","idCard","communityId"})
                if(input.containsKey(name))command.put(name,input.get(name));
            ResponseEntity<String> api=callCenterService(restTemplate,pd,command.toJSONString(),ServiceConstant.SERVICE_API_URL+"/api/owner.bindWechatIdentity",HttpMethod.POST);
            if(api.getStatusCode()!=HttpStatus.OK)return new ResponseEntity<>("绑定暂未完成，请稍后重试",HttpStatus.BAD_REQUEST);
            JSONObject reply=JSONObject.parseObject(api.getBody());
            if(reply==null||reply.getInteger("code")==null)return new ResponseEntity<>("绑定暂未完成，请稍后重试",HttpStatus.BAD_REQUEST);
            if(reply.getIntValue("code")!=0){
                int code=reply.getIntValue("code");
                String message=code==429?"尝试次数过多，请十五分钟后再试":code==409?"正在处理房产绑定，请稍后重试":code==400?"资料不匹配或登记信息不完整，请核对后联系物业":"绑定暂未完成，请稍后重试或联系物业";
                return ResultVo.createResponseEntity(code,message);
            }
            JSONObject bound=reply.getJSONObject("data");
            if(bound==null||StringUtil.isEmpty(bound.getString("userId")))
                return new ResponseEntity<>("绑定暂未完成，请稍后重试",HttpStatus.BAD_REQUEST);
            IPageData ownerPd=PageData.newInstance().builder(bound.getString("userId"),"","",pd.getReqData(),"","","","",AppDto.WECHAT_OWNER_APP_ID);
            UserDto query=new UserDto();query.setUserId(bound.getString("userId"));
            UserDto user=super.getForApi(ownerPd,query,ServiceCodeConstant.QUERY_USER_SECRET,UserDto.class);
            if(user==null||StringUtil.isEmpty(user.getKey())) {
                return new ResponseEntity<>("绑定已保存，登录信息暂未同步，请稍后重新微信授权",HttpStatus.CONFLICT);
            }
            JSONObject ticket=new JSONObject();ticket.put("appId",AppDto.WECHAT_OWNER_APP_ID);
            ticket.put("key",user.getKey());ticket.put("wechatSession",input.getString("wechatSession"));
            ticket.put("preferredMemberId",bound.getString("memberId"));
            String authTicket=UUID.randomUUID().toString();CommonCache.setValue("owner-wechat-login:"+authTicket,ticket.toJSONString(),300);
            JSONObject exchange=new JSONObject();exchange.put("authTicket",authTicket);
            return doLoginByKey(PageData.newInstance().builder("","","",exchange.toJSONString(),"","","","",AppDto.WECHAT_OWNER_APP_ID));
        } catch(IllegalArgumentException failure) {
            return new ResponseEntity<>(failure.getMessage(),HttpStatus.BAD_REQUEST);
        } catch(Exception failure) {
            return new ResponseEntity<>("绑定暂未完成，请核对资料或稍后重试",HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 刷新token
     *
     * @param pd
     * @param redirectUrl
     * @param request
     * @param response
     * @return
     * @throws SMOException
     */
    @Override
    public ResponseEntity<String> refreshToken(IPageData pd, String redirectUrl,
                                               String errorUrl,
                                               String loginFlag,
                                               HttpServletRequest request, HttpServletResponse response) throws SMOException {
        //分配urlCode
        URI target = httpRedirect(redirectUrl);
        URI failure = target.resolve(errorUrl);
        httpRedirect(failure.toString());
        if (!sameOrigin(target, failure)) {
            throw new IllegalArgumentException("OAuth redirects must use the same origin");
        }
        if (!"0".equals(loginFlag) && !"1".equals(loginFlag)) {
            throw new IllegalArgumentException("Invalid OAuth loginFlag");
        }
        String wAppId = request.getHeader("w-app-id");
        SmallWeChatDto smallWeChatDto = getPublicWechat(pd, wAppId);
        String urlCode = UUID.randomUUID().toString();
        JSONObject param = new JSONObject();
        param.put("redirectUrl", redirectUrl);
        param.put("errorUrl", failure.toString());
        param.put("loginFlag", loginFlag);
        param.put("wechatAppId", wAppId);
        param.put("internalAppId", AppDto.WECHAT_OWNER_APP_ID);
        CommonCache.setValue(urlCode, param.toJSONString(), 300);
        String callback = target.getScheme() + "://" + target.getRawAuthority()
                + "/app/loginOwnerWechatAuth?appId=" + AppDto.WECHAT_OWNER_APP_ID + "&urlCode=" + urlCode;
        try {
            String openUrl = WechatConstant.OPEN_AUTH
                    .replace("APPID", smallWeChatDto.getAppId())
                    .replace("SCOPE", "snsapi_userinfo")
                    .replace("REDIRECT_URL", URLEncoder.encode(callback, "UTF-8"))
                    .replace("STATE", urlCode);
            JSONObject urlObj = new JSONObject();
            urlObj.put("openUrl", openUrl);
            return ResultVo.createResponseEntity(ResultVo.CODE_MACHINE_OK, ResultVo.MSG_OK, urlObj);
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private SmallWeChatDto getPublicWechat(IPageData pd, String appId) {
        if(StringUtil.isEmpty(appId)) {
            appId=wechatAuthProperties.getWechatAppId();
            if(StringUtil.isEmpty(appId)||StringUtil.isEmpty(wechatAuthProperties.getWechatAppSecret()))
                throw new IllegalArgumentException("Public WeChat AppID and AppSecret are required");
        }
        JSONObject lookup=new JSONObject();lookup.put("appId",appId);
        SmallWeChatDto config=getSmallWechat(pd,lookup);
        if(config==null||!appId.equals(config.getAppId())||!SmallWeChatDto.WECHAT_TYPE_PUBLIC.equals(config.getWeChatType())
                || !SmallWeChatDto.OBJ_TYPE_COMMUNITY.equals(config.getObjType())||StringUtil.isEmpty(config.getObjId())
                ||StringUtil.isEmpty(config.getAppSecret())) {
            throw new IllegalArgumentException("Public WeChat community account is not configured");
        }
        return config;
    }

    private ResponseEntity<String> requestOAuth(String url) {
        try {
            return outRestTemplate.getForEntity(url, String.class);
        } catch (org.springframework.web.client.RestClientException failure) {
            // Upstream exception messages include the URL with AppSecret or access_token.
            logger.warn("WeChat OAuth request failed");
            return null;
        }
    }

    private static URI httpRedirect(String url) {
        try {
            URI uri = new URI(url);
            if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Invalid OAuth redirect URL");
            }
            return uri;
        } catch (java.net.URISyntaxException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid OAuth redirect URL");
        }
    }

    private static boolean sameOrigin(URI first, URI second) {
        int firstPort = first.getPort() < 0 ? ("https".equals(first.getScheme()) ? 443 : 80) : first.getPort();
        int secondPort = second.getPort() < 0 ? ("https".equals(second.getScheme()) ? 443 : 80) : second.getPort();
        return first.getScheme().equals(second.getScheme()) && first.getHost().equalsIgnoreCase(second.getHost())
                && firstPort == secondPort;
    }

    /** uni-app uses a fragment router; its page parameters belong after the fragment. */
    private static String appendOAuthParameter(String url, String name, String value) {
        int fragment = url.indexOf('#');
        String prefix = fragment < 0 ? "" : url.substring(0, fragment + 1);
        String route = fragment < 0 ? url : url.substring(fragment + 1);
        // Remove stale login credentials before returning to the browser.
        route = route.replaceAll("([?&])(key|authTicket|code)=[^&]*", "$1")
                .replace("?&", "?").replace("&&", "&");
        if (route.endsWith("?") || route.endsWith("&")) route = route.substring(0, route.length() - 1);
        return prefix + route + (route.contains("?") ? "&" : "?") + name + "=" + value;
    }

    /**
     * 公众号登录
     *
     * @param pd
     * @param paramIn
     * @param paramOut
     * @param userId
     * @param ownerAppUserDtos
     * @return
     */
    private List<OwnerAppUserDto> approvedBindings(List<OwnerAppUserDto> bindings) {
        List<OwnerAppUserDto> approved = new java.util.ArrayList<>();
        if (bindings != null) for (OwnerAppUserDto binding : bindings) {
            if ("12000".equals(binding.getState())) approved.add(binding);
        }
        return approved;
    }

    private ResponseEntity<String> wechat(IPageData pd, JSONObject paramIn, JSONObject paramOut, String userId,
                                          List<OwnerAppUserDto> ownerAppUserDtos) {

        ResponseEntity<String> responseEntity = null;
        //查询微信信息
        pd = PageData.newInstance().builder(userId, "", "", pd.getReqData(),
                "", "", "", "",
                pd.getAppId());


        String code = paramIn.getString("code");

        String openId = CommonCache.getAndRemoveValue(code);
        String nickname = CommonCache.getValue(code + "-nickname");
        String headimgurl = CommonCache.getValue(code + "-headimgurl");

        if (StringUtil.isEmpty(openId)) {
            responseEntity = new ResponseEntity<>("页面失效，请刷新后重试", HttpStatus.UNAUTHORIZED);
            return responseEntity;
        }

        OwnerAppUserDto curOwnerApp = judgeCurrentOwnerBind(ownerAppUserDtos, OwnerAppUserDto.APP_TYPE_WECHAT);

        //说明 当前的openId 就是最新的
        if (curOwnerApp != null && openId.equals(curOwnerApp.getOpenId())) {
            return new ResponseEntity<>(paramOut.toJSONString(), HttpStatus.OK);
        }

        JSONObject userOwnerInfo = new JSONObject();
        OwnerAppUserDto ownerAppUserDto = new OwnerAppUserDto();
        ownerAppUserDto.setOpenId(openId);
        // ownerAppUserDto.setNickName(StringUtil.encodeEmoji(nickname));
        ownerAppUserDto.setNickName(nickname);
        ownerAppUserDto.setHeadImgUrl(headimgurl);
        ownerAppUserDto.setAppType(OwnerAppUserDto.APP_TYPE_WECHAT);
        if (curOwnerApp != null) {
            ownerAppUserDto.setAppUserId(curOwnerApp.getAppUserId());
            ownerAppUserDto.setCommunityId(curOwnerApp.getCommunityId());
        } else {
            ownerAppUserDto.setOldAppUserId(ownerAppUserDtos.get(0).getAppUserId());
            ownerAppUserDto.setAppUserId("-1");
            ownerAppUserDto.setCommunityId(ownerAppUserDtos.get(0).getCommunityId());
        }

        //查询微信信息
        pd = PageData.newInstance().builder(userId, "", "", pd.getReqData(),
                "", "", "", "",
                pd.getAppId());

        super.postForApi(pd, ownerAppUserDto, ServiceCodeConstant.REFRESH_APP_USER_BINDING_OWNER_OPEN_ID,
                OwnerAppUserDto.class);
        return new ResponseEntity<>(paramOut.toJSONString(), HttpStatus.OK);
    }

    private ResponseEntity<String> mina(IPageData pd, JSONObject paramIn, JSONObject paramOut, String userId, List<OwnerAppUserDto> ownerAppUserDtos) {

        ResponseEntity<String> responseEntity = null;
        //查询微信信息
        pd = PageData.newInstance().builder(userId, "", "", pd.getReqData(),
                "", "", "", "",
                pd.getAppId());
        responseEntity = this.callCenterService(restTemplate, pd, "",
                ServiceConstant.SERVICE_API_URL + "/api/smallWeChat.listSmallWeChats?appId="
                        + paramIn.getString("appId") + "&page=1&row=1&communityId=" + ownerAppUserDtos.get(0).getCommunityId(), HttpMethod.GET);

        if (responseEntity.getStatusCode() != HttpStatus.OK) {
            return responseEntity;
        }
        JSONObject smallWechatObj = JSONObject.parseObject(responseEntity.getBody().toString());
        JSONArray smallWeChats = smallWechatObj.getJSONArray("smallWeChats");
        String appId = wechatAuthProperties.getAppId();
        String secret = wechatAuthProperties.getSecret();
        if (smallWeChats.size() > 0) {
            appId = smallWeChats.getJSONObject(0).getString("appId");
            secret = smallWeChats.getJSONObject(0).getString("appSecret");
        }

        String code = paramIn.getString("code");
        String urlString = "?appid={appId}&secret={secret}&js_code={code}&grant_type={grantType}";
        String response = outRestTemplate.getForObject(
                wechatAuthProperties.getSessionHost() + urlString, String.class,
                appId,
                secret,
                code,
                wechatAuthProperties.getGrantType());



        //Assert.jsonObjectHaveKey(response, "errcode", "返回报文中未包含 错误编码，接口出错");
        JSONObject responseObj = JSONObject.parseObject(response);

        if (responseObj.containsKey("errcode") && !"0".equals(responseObj.getString("errcode"))) {
            throw new IllegalArgumentException("微信验证失败，可能是code失效" + responseObj);
        }

        String openId = responseObj.getString("openid");

        OwnerAppUserDto ownerAppUserDto = judgeCurrentOwnerBind(ownerAppUserDtos, OwnerAppUserDto.APP_TYPE_WECHAT_MINA);

        //说明 当前的openId 就是最新的
        if (ownerAppUserDto != null && openId.equals(ownerAppUserDto.getOpenId())) {
            return new ResponseEntity<>(paramOut.toJSONString(), HttpStatus.OK);
        }

        OwnerAppUserDto tmpOwnerAppUserDto = new OwnerAppUserDto();
        tmpOwnerAppUserDto.setOpenId(openId);
        tmpOwnerAppUserDto.setAppType(OwnerAppUserDto.APP_TYPE_WECHAT_MINA);
        if (ownerAppUserDto != null) {
            tmpOwnerAppUserDto.setAppUserId(ownerAppUserDto.getAppUserId());
            tmpOwnerAppUserDto.setCommunityId(ownerAppUserDto.getCommunityId());
        } else {
            tmpOwnerAppUserDto.setOldAppUserId(ownerAppUserDtos.get(0).getAppUserId());
            tmpOwnerAppUserDto.setAppUserId("-1");
            tmpOwnerAppUserDto.setCommunityId(ownerAppUserDtos.get(0).getCommunityId());
        }
        //查询微信信息
        pd = PageData.newInstance().builder(userId, "", "", pd.getReqData(),
                "", "", "", "",
                pd.getAppId());

        super.postForApi(pd, tmpOwnerAppUserDto, ServiceCodeConstant.REFRESH_APP_USER_BINDING_OWNER_OPEN_ID,
                OwnerAppUserDto.class);
        return new ResponseEntity<>(paramOut.toJSONString(), HttpStatus.OK);
    }

    /**
     * 判断 绑定表里是否存在当前 端 绑定信息
     *
     * @param ownerAppUserDtos
     * @param appType
     * @return
     */
    private OwnerAppUserDto judgeCurrentOwnerBind(List<OwnerAppUserDto> ownerAppUserDtos, String appType) {

        for (OwnerAppUserDto ownerAppUserDto : ownerAppUserDtos) {
            if (appType.equals(ownerAppUserDto.getAppType())) {
                return ownerAppUserDto;
            }
        }
        return null;
    }

    private SmallWeChatDto getSmallWechat(IPageData pd, JSONObject paramIn) {

        ResponseEntity responseEntity = null;

        pd = PageData.newInstance().builder(pd.getUserId(), "", "", pd.getReqData(),
                "", "", "", "",
                pd.getAppId());
        responseEntity = this.callCenterService(restTemplate, pd, "",
                ServiceConstant.SERVICE_API_URL + "/api/smallWeChat.listSmallWeChats?appId="
                        + paramIn.getString("appId") + "&page=1&row=2", HttpMethod.GET);

        if (responseEntity.getStatusCode() != HttpStatus.OK) {
            return null;
        }
        JSONObject smallWechatObj = JSONObject.parseObject(responseEntity.getBody().toString());
        JSONArray smallWeChats = smallWechatObj.getJSONArray("smallWeChats");

        if (smallWeChats == null || smallWeChats.size() != 1) {
            return null;
        }

        return BeanConvertUtil.covertBean(smallWeChats.get(0), SmallWeChatDto.class);
    }

    public RestTemplate getRestTemplate() {
        return restTemplate;
    }

    public void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }
    
}
