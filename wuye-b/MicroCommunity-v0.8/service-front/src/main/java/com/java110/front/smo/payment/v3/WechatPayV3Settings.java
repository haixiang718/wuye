package com.java110.front.smo.payment.v3;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Immutable payment configuration; its string representation never contains credentials. */
public final class WechatPayV3Settings {
    private static final long PRODUCTION_MAXIMUM_FEN = 999999999L;
    private final boolean enabled, liveForAllOwners;
    private final long pilotMaxTotalFen;
    private final String appId, mchId, apiV3Key, publicKeyId, notifyUrl;
    private final Path merchantCertificate, merchantPrivateKey, publicKey;
    private final Set<String> pilotUserIds;

    private WechatPayV3Settings(JSONObject json, Path directory) {
        enabled = Boolean.TRUE.equals(json.get("enabled"));
        liveForAllOwners = Boolean.TRUE.equals(json.get("liveForAllOwners"));
        pilotMaxTotalFen = pilotMaximum(json);
        appId = json.getString("appId"); mchId = json.getString("mchId");
        apiV3Key = json.getString("apiV3Key"); publicKeyId = json.getString("wechatPayPublicKeyId");
        notifyUrl = json.getString("notifyUrl");
        merchantCertificate = path(directory, json.getString("merchantCertificatePath"));
        merchantPrivateKey = path(directory, json.getString("merchantPrivateKeyPath"));
        publicKey = path(directory, json.getString("wechatPayPublicKeyPath"));
        Set<String> pilots = new HashSet<>();
        JSONArray values = json.getJSONArray("pilotUserIds");
        if (values != null) for (Object value : values) {
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) throw invalid();
            pilots.add((String) value);
        }
        pilotUserIds = Collections.unmodifiableSet(pilots);
        if (enabled) {
            require(appId); require(mchId); require(apiV3Key); require(publicKeyId); require(notifyUrl);
            if (apiV3Key.getBytes(StandardCharsets.UTF_8).length != 32 || !publicKeyId.startsWith("PUB_KEY_ID_")) throw invalid();
            URI uri = URI.create(notifyUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) throw invalid();
            if (merchantCertificate == null || !Files.isRegularFile(merchantCertificate)
                || merchantPrivateKey == null || !Files.isRegularFile(merchantPrivateKey)
                || publicKey == null || !Files.isRegularFile(publicKey)) throw invalid();
        }
    }

    public static WechatPayV3Settings load(Path path) {
        try {
            Path absolute = path.toAbsolutePath().normalize();
            JSONObject json = JSON.parseObject(new String(Files.readAllBytes(absolute), StandardCharsets.UTF_8));
            if (json == null) throw invalid();
            return new WechatPayV3Settings(json, absolute.getParent());
        } catch (Exception ignored) {
            // JSON, filesystem and SDK exceptions may contain credentials or full source documents.
            throw invalid();
        }
    }

    public boolean enabled() { return enabled; }
    public String appId() { return appId; }
    public String mchId() { return mchId; }
    public String notifyUrl() { return notifyUrl; }
    public long maximumTotalFee() { return liveForAllOwners ? PRODUCTION_MAXIMUM_FEN : pilotMaxTotalFen; }
    public boolean permitsUser(String userId) {
        return enabled && userId != null && !userId.trim().isEmpty()
            && (liveForAllOwners || pilotUserIds.contains(userId));
    }

    String apiV3Key() { return apiV3Key; }
    String publicKeyId() { return publicKeyId; }
    Path merchantCertificate() { return merchantCertificate; }
    Path merchantPrivateKey() { return merchantPrivateKey; }
    Path publicKey() { return publicKey; }

    @Override public String toString() { return "WechatPayV3Settings[redacted]"; }
    private static Path path(Path directory, String value) {
        return value == null || value.trim().isEmpty() ? null : directory.resolve(value).normalize();
    }
    private static void require(String value) { if (value == null || value.trim().isEmpty()) throw invalid(); }
    private static long pilotMaximum(JSONObject json) {
        if (!json.containsKey("pilotMaxTotalFen")) return 1L;
        Object value = json.get("pilotMaxTotalFen");
        if (!(value instanceof Integer || value instanceof Long || value instanceof BigInteger)) throw invalid();
        BigInteger fen = new BigInteger(value.toString());
        if (fen.signum() <= 0 || fen.compareTo(BigInteger.valueOf(PRODUCTION_MAXIMUM_FEN)) > 0) throw invalid();
        return fen.longValue();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("WeChat Pay V3 configuration is invalid"); }
}
