package com.java110.front.smo.payment.v3;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.core.util.GsonUtil;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.*;
import com.wechat.pay.java.service.payments.model.Transaction;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Official SDK adapter in pinned WeChat Pay public-key mode. */
public final class WechatPayV3Client implements WechatPayV3Provider {
    private final WechatPayV3Settings settings;
    private final RSAPublicKeyConfig config;
    private final JsapiServiceExtension service;
    private final NotificationParser parser;

    public WechatPayV3Client(WechatPayV3Settings settings) {
        this(settings, config -> new DefaultHttpClientBuilder().config(config)
            .connectTimeoutMs(5000).readTimeoutMs(10000).writeTimeoutMs(10000)
            .disableRetryOnConnectionFailure().build());
    }

    // Allows the offline suite to supply a signed fixture transport with the SDK's genuine validator.
    WechatPayV3Client(WechatPayV3Settings settings, Function<Config, HttpClient> transportFactory) {
        try {
            // Disabling new orders must not strand previously submitted payments.
            if (settings == null) throw new IllegalArgumentException();
            this.settings = settings;
            X509Certificate certificate;
            try (InputStream input = Files.newInputStream(settings.merchantCertificate())) {
                certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
            }
            config = new RSAPublicKeyConfig.Builder().merchantId(settings.mchId())
                .merchantSerialNumber(certificate.getSerialNumber().toString(16).toUpperCase(java.util.Locale.ROOT))
                .privateKeyFromPath(settings.merchantPrivateKey().toString())
                .publicKeyId(settings.publicKeyId()).publicKeyFromPath(settings.publicKey().toString())
                .apiV3Key(settings.apiV3Key()).build();
            service = new JsapiServiceExtension.Builder().config(config)
                .httpClient(transportFactory.apply(config)).build();
            parser = new NotificationParser(config);
        } catch (Exception ignored) {
            throw new IllegalStateException("WeChat Pay V3 client is unavailable");
        }
    }

    @Override public Map<String, String> prepay(String description, String orderId, long cents, String openId) {
        try {
            required(description); required(orderId); required(openId);
            if (cents <= 0 || cents > Integer.MAX_VALUE) throw new IllegalArgumentException();
            PrepayRequest request = new PrepayRequest();
            request.setAppid(settings.appId()); request.setMchid(settings.mchId());
            request.setDescription(description); request.setOutTradeNo(orderId); request.setNotifyUrl(settings.notifyUrl());
            Amount amount = new Amount(); amount.setTotal((int) cents); amount.setCurrency("CNY"); request.setAmount(amount);
            Payer payer = new Payer(); payer.setOpenid(openId); request.setPayer(payer);
            PrepayWithRequestPaymentResponse response = service.prepayWithRequestPayment(request);
            if (response.getPackageVal() == null || response.getPackageVal().equals("prepay_id=null")
                || response.getPackageVal().equals("prepay_id=")) throw new IllegalArgumentException();
            Map<String, String> result = new LinkedHashMap<>();
            result.put("appId", response.getAppId()); result.put("timeStamp", response.getTimeStamp());
            result.put("nonceStr", response.getNonceStr()); result.put("package", response.getPackageVal());
            result.put("signType", response.getSignType()); result.put("sign", response.getPaySign());
            result.put("paySign", response.getPaySign()); result.put("code", "0");
            result.put("msg", "success"); result.put("orderId", orderId);
            return result;
        } catch (Exception ignored) { throw new IllegalStateException("WeChat Pay V3 prepay failed"); }
    }

    @Override public JSONObject query(String orderId) {
        try {
            required(orderId);
            QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
            request.setMchid(settings.mchId()); request.setOutTradeNo(orderId);
            return normalized(service.queryOrderByOutTradeNo(request));
        } catch (Exception ignored) { throw new IllegalStateException("WeChat Pay V3 query failed"); }
    }

    @Override public void close(String orderId) {
        try {
            required(orderId);
            CloseOrderRequest request = new CloseOrderRequest(); request.setMchid(settings.mchId()); request.setOutTradeNo(orderId);
            service.closeOrder(request);
        } catch (Exception ignored) { throw new IllegalStateException("WeChat Pay V3 close failed"); }
    }

    @Override public JSONObject notification(String rawBody, Map<String, String> headers) {
        try {
            required(rawBody);
            String timestamp = header(headers, "Wechatpay-Timestamp", true);
            String nonce = header(headers, "Wechatpay-Nonce", true);
            String serial = header(headers, "Wechatpay-Serial", true);
            String signature = header(headers, "Wechatpay-Signature", true);
            String signType = header(headers, "Wechatpay-Signature-Type", false);
            long now = Instant.now().getEpochSecond(), supplied = Long.parseLong(timestamp);
            // Bounds avoid abs/subtraction overflow and reject future timestamps too.
            if (supplied < now - 300 || supplied > now + 300 || !settings.publicKeyId().equals(serial)) throw new IllegalArgumentException();
            if (signType != null && !"WECHATPAY2-SHA256-RSA2048".equals(signType)) throw new IllegalArgumentException();
            RequestParam request = new RequestParam.Builder().timestamp(timestamp).nonce(nonce).serialNumber(serial)
                .signature(signature).signType(signType).body(rawBody).build();
            // Verify before parsing the envelope or decrypting. The SDK also verifies during parse().
            if (!config.createVerifier().verify(serial, request.getMessage(), signature)) throw new IllegalArgumentException();
            JSONObject event = JSON.parseObject(rawBody);
            JSONObject resource = event.getJSONObject("resource");
            if (!"TRANSACTION.SUCCESS".equals(event.getString("event_type"))
                || !"encrypt-resource".equals(event.getString("resource_type")) || resource == null
                || !"AEAD_AES_256_GCM".equals(resource.getString("algorithm"))
                || !"transaction".equals(resource.getString("original_type"))) throw new IllegalArgumentException();
            JSONObject transaction = normalized(parser.parse(request, Transaction.class));
            if (!"SUCCESS".equals(transaction.getString("trade_state"))) throw new IllegalArgumentException();
            return transaction;
        } catch (Exception ignored) { throw new IllegalArgumentException("WeChat Pay V3 notification is invalid"); }
    }

    private static JSONObject normalized(Transaction transaction) {
        if (transaction == null) throw new IllegalArgumentException();
        // The SDK's Gson configuration preserves API field names (including out_trade_no).
        return JSON.parseObject(GsonUtil.toJson(transaction));
    }

    private static String header(Map<String, String> headers, String name, boolean mandatory) {
        if (headers == null) throw new IllegalArgumentException();
        String value = null; int count = 0;
        for (Map.Entry<String, String> entry : headers.entrySet()) if (name.equalsIgnoreCase(entry.getKey())) {
            value = entry.getValue(); count++;
        }
        if (count > 1 || (mandatory && (value == null || value.trim().isEmpty()))) throw new IllegalArgumentException();
        return value;
    }
    private static void required(String value) { if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(); }
}
