/*
 * Copyright 2017-2020 吴学文 and java110 team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.java110.front.controller.app;

import com.java110.core.base.controller.BaseController;
import com.java110.core.context.IPageData;
import com.java110.core.context.PageData;
import com.java110.front.smo.payment.*;
import com.alibaba.fastjson.JSONObject;
import com.java110.front.smo.api.impl.OwnerDataAccessService;
import com.java110.utils.constant.CommonConstant;
import com.java110.utils.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * 支付 处理类
 */
@RestController
@RequestMapping(path = "/app/payment")
public class PaymentController extends BaseController {
    private final static Logger logger = LoggerFactory.getLogger(PaymentController.class);

    @Autowired
    private IToPaySMO toPaySMOImpl;

    @Autowired
    private IToPayOweFeeSMO toPayOweFeeSMOImpl;

    @Autowired
    private IRentingToPaySMO rentingToPaySMOImpl;

    @Autowired
    private IToPayTempCarInoutSMO toPayTempCarInoutSMOImpl;

    @Autowired
    private IToNotifySMO toNotifySMOImpl;

    @Autowired
    private IRentingToNotifySMO rentingToNotifySMOImpl;

    @Autowired
    private IOweFeeToNotifySMO oweFeeToNotifySMOImpl;

    @Autowired
    private IToQrPayOweFeeSMO toQrPayOweFeeSMOImpl;

    @Value("${java110.payment.enabled:false}")
    private boolean paymentEnabled;
    @Autowired private OwnerDataAccessService ownerDataAccessService;

    private ResponseEntity<String> paymentGuard(String body,IPageData pd,boolean single) {
        if (!paymentEnabled) return new ResponseEntity<>("{\"code\":503,\"msg\":\"微信支付未开通\"}",HttpStatus.SERVICE_UNAVAILABLE);
        if (pd == null || StringUtil.isEmpty(pd.getUserId()) || "-1".equals(pd.getUserId()))
            return new ResponseEntity<>("{\"code\":401,\"msg\":\"请先登录\"}",HttpStatus.UNAUTHORIZED);
        try {
            JSONObject scoped = JSONObject.parseObject(body);
            if (single) ownerDataAccessService.authorize(pd,"fee.queryFeeDetail",scoped);
            else {
                scoped.put("payObjId",scoped.getString("roomId"));
                if (StringUtil.isEmpty(scoped.getString("payObjType"))) scoped.put("payObjType","3333");
                ownerDataAccessService.authorize(pd,"feeApi/listOweFees",scoped);
            }
            return null;
        } catch (Exception denied) {
            return new ResponseEntity<>("{\"code\":403,\"msg\":\"未绑定该房产或无权支付该账单\"}",HttpStatus.FORBIDDEN);
        }
    }

    /**
     * <p>统一下单入口</p>
     *
     * @param request
     * @throws Exception
     */
    @RequestMapping(path = "/toPay", method = RequestMethod.POST)
    public ResponseEntity<String> toPay(@RequestBody String postInfo, HttpServletRequest request) {
        IPageData pd = (IPageData) request.getAttribute(CommonConstant.CONTEXT_PAGE_DATA);
        ResponseEntity<String> rejected = paymentGuard(postInfo,pd,true);
        if (rejected != null) return rejected;
        /*IPageData pd = (IPageData) request.getAttribute(CommonConstant.CONTEXT_PAGE_DATA);*/
        String appId = request.getHeader("APP_ID");
        if (StringUtil.isEmpty(appId)) {
            appId = request.getHeader("APP-ID");
        }

        IPageData newPd = PageData.newInstance().builder(pd.getUserId(), pd.getUserName(), pd.getToken(), postInfo,
                "", "", "", pd.getSessionId(), appId, pd.getPayerObjId(), pd.getPayerObjType(), pd.getEndTime());
        return toPaySMOImpl.toPay(newPd);
    }

    /**
     * <p>统一下单入口</p>
     *
     * @param request
     * @throws Exception
     */
    @RequestMapping(path = "/toOweFeePay", method = RequestMethod.POST)
    public ResponseEntity<String> toOweFeePay(@RequestBody String postInfo, HttpServletRequest request) {
        IPageData pd = (IPageData) request.getAttribute(CommonConstant.CONTEXT_PAGE_DATA);
        ResponseEntity<String> rejected = paymentGuard(postInfo,pd,false);
        if (rejected != null) return rejected;
        /*IPageData pd = (IPageData) request.getAttribute(CommonConstant.CONTEXT_PAGE_DATA);*/
        String appId = request.getHeader("APP_ID");
        if (StringUtil.isEmpty(appId)) {
            appId = request.getHeader("APP-ID");
        }

        IPageData newPd = PageData.newInstance().builder(pd.getUserId(), pd.getUserName(), pd.getToken(), postInfo,
                "", "", "", pd.getSessionId(),
                appId);
        return toPayOweFeeSMOImpl.toPay(newPd);
    }

    /**
     * <p>统一下单入口</p>
     *
     * @param request
     * @throws Exception
     */
    @RequestMapping(path = "/toPayTempCarInout", method = RequestMethod.POST)
    public ResponseEntity<String> toPayTempCarInout(@RequestBody String postInfo, HttpServletRequest request) {
        IPageData pd = (IPageData) request.getAttribute(CommonConstant.CONTEXT_PAGE_DATA);
        /*IPageData pd = (IPageData) request.getAttribute(CommonConstant.CONTEXT_PAGE_DATA);*/
        String appId = request.getHeader("APP_ID");
        if (StringUtil.isEmpty(appId)) {
            appId = request.getHeader("APP-ID");
        }
        IPageData newPd = PageData.newInstance().builder(pd.getUserId(), pd.getUserName(), pd.getToken(), postInfo,
                "", "", "", pd.getSessionId(),
                appId);
        return toPayTempCarInoutSMOImpl.toPay(newPd);
    }


    /**
     * <p>支付回调Api</p>
     *
     * @param request
     * @throws Exception
     */
    @RequestMapping(path = "/notify", method = RequestMethod.POST)
    public ResponseEntity<String> notify(@RequestBody String postInfo, HttpServletRequest request) {


        return toNotifySMOImpl.toNotify(postInfo, request);


    }

    /**
     * <p>出租统一下单入口</p>
     *
     * @param request
     * @throws Exception
     */
    @RequestMapping(path = "/rentingToPay", method = RequestMethod.POST)
    public ResponseEntity<String> rentingToPay(@RequestBody String postInfo, HttpServletRequest request) {
        IPageData pd = (IPageData) request.getAttribute(CommonConstant.CONTEXT_PAGE_DATA);
        String appId = request.getHeader("APP_ID");
        if (StringUtil.isEmpty(appId)) {
            appId = request.getHeader("APP-ID");
        }

        IPageData newPd = PageData.newInstance().builder(pd.getUserId(), pd.getUserName(), pd.getToken(), postInfo,
                "", "", "", pd.getSessionId(),
                appId);
        return rentingToPaySMOImpl.toPay(newPd);
    }

    /**
     * <p>出租统一下单入口</p>
     *
     * @param request
     * @throws Exception
     */
    @RequestMapping(path = "/oweFeeNotify", method = RequestMethod.POST)
    public ResponseEntity<String> oweFeeNotify(@RequestBody String postInfo, HttpServletRequest request) {

        return oweFeeToNotifySMOImpl.toNotify(postInfo, request);
    }

    /**
     * <p>支付回调Api</p>
     *
     * @param request
     * @throws Exception
     */
    @RequestMapping(path = "/rentingNotify", method = RequestMethod.POST)
    public ResponseEntity<String> rentingNotify(@RequestBody String postInfo, HttpServletRequest request) {


        return rentingToNotifySMOImpl.toNotify(postInfo, request);


    }

    /**
     * <p>出租统一下单入口</p>
     *
     * @param request
     * @throws Exception
     */
    @RequestMapping(path = "/toQrOweFeePay", method = RequestMethod.POST)
    public ResponseEntity<String> toQrOweFeePay(@RequestBody String postInfo, HttpServletRequest request) {
        IPageData pd = (IPageData) request.getAttribute(CommonConstant.CONTEXT_PAGE_DATA);
        ResponseEntity<String> rejected = paymentGuard(postInfo,pd,false);
        if (rejected != null) return rejected;
        String appId = request.getHeader("APP_ID");
        if (StringUtil.isEmpty(appId)) {
            appId = request.getHeader("APP-ID");
        }

        IPageData newPd = PageData.newInstance().builder(pd.getUserId(), pd.getUserName(), pd.getToken(), postInfo,
                "", "", "", pd.getSessionId(),
                appId);
        return toQrPayOweFeeSMOImpl.toPay(newPd);
    }

}
