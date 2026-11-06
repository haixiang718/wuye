package com.java110.front.smo.api.impl;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import com.java110.core.component.BaseComponentSMO;
import com.java110.core.context.IPageData;
import com.java110.entity.component.ComponentValidateResult;
import com.java110.front.smo.api.IApiSMO;
import com.java110.utils.constant.CommonConstant;
import com.java110.utils.constant.ServiceConstant;
import com.java110.utils.util.Assert;
import com.java110.utils.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

@Service("apiSMOImpl")
public class ApiSMOImpl extends BaseComponentSMO implements IApiSMO {


    private final static Logger logger = LoggerFactory.getLogger(ApiSMOImpl.class);

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private OwnerDataAccessService ownerDataAccessService;

    @Override
    protected ResponseEntity<String> getStoreInfo(IPageData pd, RestTemplate restTemplate) {

        if (StringUtil.isEmpty(pd.getUserId())) {
            return new ResponseEntity<>("未包含用户信息", HttpStatus.BAD_REQUEST);
        }
        return super.getStoreInfo(pd, restTemplate);
    }

    @Override
    protected ComponentValidateResult validateStoreStaffCommunityRelationship(IPageData pd, RestTemplate restTemplate) {
        // 校验 员工和商户是否有关系
        ResponseEntity responseEntity = getStoreInfo(pd, restTemplate);
        if (responseEntity.getStatusCode() != HttpStatus.OK) {
            return new ComponentValidateResult("", "", "", pd.getUserId(), pd.getUserName());
        }

        JSONObject storeInfo = JSONObject.parseObject(responseEntity.getBody().toString());
        if (!storeInfo.containsKey("storeId")) {
            return new ComponentValidateResult("", "", "", pd.getUserId(), pd.getUserName());
        }

        Assert.jsonObjectHaveKey(responseEntity.getBody().toString(), "storeId", "根据用户ID查询商户ID失败，未包含storeId节点");
        Assert.jsonObjectHaveKey(responseEntity.getBody().toString(), "storeTypeCd", "根据用户ID查询商户类型失败，未包含storeTypeCd节点");

        String storeId = JSONObject.parseObject(responseEntity.getBody().toString()).getString("storeId");
        String storeTypeCd = JSONObject.parseObject(responseEntity.getBody().toString()).getString("storeTypeCd");

        JSONObject paramIn = JSONObject.parseObject(pd.getReqData());

        String communityId = "";
        if (paramIn != null && paramIn.containsKey("communityId") && !StringUtil.isEmpty(paramIn.getString("communityId"))) {
            communityId = paramIn.getString("communityId");
            checkStoreEnterCommunity(pd, storeId, storeTypeCd, communityId, restTemplate);
        }
        return new ComponentValidateResult(storeId, storeTypeCd, communityId, pd.getUserId(), pd.getUserName());
    }

    @Override
    public ResponseEntity<String> doApi(String body, Map<String, String> headers, HttpServletRequest request) throws UnsupportedEncodingException {
        HttpHeaders header = new HttpHeaders();
        IPageData pd = (IPageData) request.getAttribute(CommonConstant.CONTEXT_PAGE_DATA);

        JSONObject scopedBody = JSONObject.parseObject(body);
        try {
            ownerDataAccessService.authorize(pd, headers.get(CommonConstant.HTTP_SERVICE), scopedBody);
            ResponseEntity<String> ownerReply = ownerDataAccessService.ownerBillResponse(pd, headers.get(CommonConstant.HTTP_SERVICE), scopedBody);
            if (ownerReply != null) return ownerReply;
        } catch (SecurityException denied) {
            JSONObject error = new JSONObject(); error.put("code", 403); error.put("msg", denied.getMessage());
            return new ResponseEntity<>(error.toJSONString(), HttpStatus.FORBIDDEN);
        }
        body = scopedBody.toJSONString();
        // Anonymous requests reach here only after the public-service allowlist passes.
        // HTTP header names are case-insensitive, including caller identity aliases.
        headers.keySet().removeIf(key -> Arrays.asList("user-id", "user_id", "userid", "user-name", "user_name", "username", "store-id", "content-length", "transfer-encoding")
                .contains(key.toLowerCase(Locale.ROOT)));
        boolean authenticated = pd != null && !StringUtil.isEmpty(pd.getUserId())
                && !CommonConstant.ORDER_DEFAULT_USER_ID.equals(pd.getUserId());
        String userId = authenticated ? pd.getUserId() : CommonConstant.ORDER_DEFAULT_USER_ID;
        headers.put("user-id", userId); headers.put("user_id", userId); headers.put("userid", userId);

        ComponentValidateResult result = authenticated
                ? this.validateStoreStaffCommunityRelationship(pd, restTemplate)
                : new ComponentValidateResult("", "", "", userId, "");
        if (!StringUtil.isEmpty(result.getUserId())) {
            header.add("user-id", result.getUserId());
            if (!StringUtil.isEmpty(result.getUserName())) {
                header.add("user-name", URLEncoder.encode(result.getUserName(), "UTF-8"));
            }
        }

        header.add("store-id", result.getStoreId());
        // Headers/body can contain merchant credentials and owner identity data.
        HttpMethod method = null;
        String service = headers.get(CommonConstant.HTTP_SERVICE);
        String url = ServiceConstant.SERVICE_API_URL + "/api/" + (service.startsWith("/") ? service.substring(1) : service);
        if (CommonConstant.HTTP_METHOD_POST.equals(headers.get(CommonConstant.HTTP_METHOD))) {
            method = HttpMethod.POST;
        } else if (CommonConstant.HTTP_METHOD_GET.equals(headers.get(CommonConstant.HTTP_METHOD))) {
            method = HttpMethod.GET;
            url += super.mapToUrlParam(JSONObject.parseObject(body));
        } else if (CommonConstant.HTTP_METHOD_DELETE.equals(headers.get(CommonConstant.HTTP_METHOD))) {
            method = HttpMethod.DELETE;
        } else if (CommonConstant.HTTP_METHOD_PUT.equals(headers.get(CommonConstant.HTTP_METHOD))) {
            method = HttpMethod.PUT;

        } else {
            throw new IllegalArgumentException("不支持的请求方式" + headers.get(CommonConstant.HTTP_METHOD));
        }


        for (String key : headers.keySet()
        ) {
            header.set(key, headers.get(key));
        }

        HttpEntity<String> httpEntity = new HttpEntity<String>(body, header);
        logger.debug("api request service={} method={}", service, method);
        ResponseEntity<String> responseEntity = null;
        try {
            responseEntity = restTemplate.exchange(url, method, httpEntity, String.class);
        } catch (HttpStatusCodeException e) { //这里spring 框架 在4XX 或 5XX 时抛出 HttpServerErrorException 异常，需要重新封装一下
            responseEntity = new ResponseEntity<String>(e.getResponseBodyAsString(), e.getStatusCode());
        } catch (Exception e) {
            responseEntity = new ResponseEntity<String>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
        // A caller-controlled app-id must never select an internal credential response.
        // Payment services call the internal API directly and retain their trusted lookup.
        if ("smallWeChat.listSmallWeChats".equals(service.startsWith("/") ? service.substring(1) : service)) {
            try {
                JSONObject safe = JSONObject.parseObject(responseEntity.getBody());
                if (safe == null) throw new IllegalArgumentException("Missing merchant response");
                redactMerchantSecrets(safe);
                responseEntity = new ResponseEntity<>(safe.toJSONString(), responseEntity.getStatusCode());
            } catch (RuntimeException invalid) {
                responseEntity = new ResponseEntity<>("{\"code\":502,\"msg\":\"公众号配置读取失败\"}", HttpStatus.BAD_GATEWAY);
            }
        }
        logger.debug("api response service={} status={}", service, responseEntity.getStatusCode());
        return responseEntity;
    }

    private void redactMerchantSecrets(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            for (String key : Arrays.asList("appSecret", "payPassword", "apiV3Key", "privateKey", "encodingAesKey")) {
                if (object.containsKey(key)) object.put(key, "");
            }
            for (Object child : object.values()) redactMerchantSecrets(child);
        } else if (value instanceof JSONArray) {
            for (Object child : (JSONArray)value) redactMerchantSecrets(child);
        }
    }


    public RestTemplate getRestTemplate() {
        return restTemplate;
    }

    public void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }
}
