package com.java110.front.smo.payment.v3;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.http.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.*;
import static org.junit.Assert.*;

public class WechatPayV3ClientTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String ID = "PUB_KEY_ID_OFFLINE_TEST";
    private static Path folder;
    private static KeyPair platform, wrongPlatform;
    private static PublicKey merchantPublic;
    private JSONObject config;
    private WechatPayV3Settings settings;
    private WechatPayV3Client client;
    private FixtureTransport transport;

    @BeforeClass public static void keys() throws Exception {
        folder = Files.createTempDirectory("wuye-v3-keys-");
        String keytool = Paths.get(System.getProperty("java.home"), "bin",
            java.io.File.separatorChar == '\\' ? "keytool.exe" : "keytool").toString();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA",
            "-keysize", "2048", "-dname", "CN=Offline Synthetic Test", "-validity", "2", "-storetype", "JKS",
            "-keystore", folder.resolve("synthetic.jks").toString(), "-storepass", "test-only-password",
            "-keypass", "test-only-password", "-noprompt").redirectErrorStream(true).start();
        byte[] buffer = new byte[1024]; while (p.getInputStream().read(buffer) != -1) { }
        assertEquals(0, p.waitFor());
        KeyStore store = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(folder.resolve("synthetic.jks"))) {
            store.load(in, "test-only-password".toCharArray());
        }
        Certificate cert = store.getCertificate("test"); merchantPublic = cert.getPublicKey();
        Files.write(folder.resolve("merchant.pem"), pem("CERTIFICATE", cert.getEncoded()).getBytes(StandardCharsets.US_ASCII));
        Files.write(folder.resolve("private.pem"), pem("PRIVATE KEY", store.getKey("test", "test-only-password".toCharArray()).getEncoded()).getBytes(StandardCharsets.US_ASCII));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        platform = generator.generateKeyPair(); wrongPlatform = generator.generateKeyPair();
        Files.write(folder.resolve("platform.pem"), pem("PUBLIC KEY", platform.getPublic().getEncoded()).getBytes(StandardCharsets.US_ASCII));
    }

    @AfterClass public static void cleanup() throws Exception {
        if (folder != null) { try (java.util.stream.Stream<Path> files = Files.walk(folder)) {
            for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(file);
        } }
    }

    @Before public void setup() throws Exception {
        config = JSON.parseObject("{\"enabled\":true,\"appId\":\"wx-offline\",\"mchId\":\"1900000001\",\"notifyUrl\":\"https://example.invalid/payment/v3/notify\",\"pilotUserIds\":[\"owner-1\"]}");
        config.put("apiV3Key", KEY); config.put("merchantCertificatePath", "merchant.pem");
        config.put("merchantPrivateKeyPath", "private.pem"); config.put("wechatPayPublicKeyId", ID);
        config.put("wechatPayPublicKeyPath", "platform.pem");
        settings = load(); client = new WechatPayV3Client(settings, c -> transport = new FixtureTransport(c));
    }

    private WechatPayV3Settings load() throws Exception {
        Path path = folder.resolve("config.json");
        Files.write(path, JSON.toJSONString(config, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8));
        return WechatPayV3Settings.load(path);
    }

    @Test public void disabledConfigurationLoadsWithoutSecretsAndDeniesOwners() throws Exception {
        config = JSON.parseObject("{\"enabled\":false,\"pilotUserIds\":[\"owner-1\"],\"liveForAllOwners\":true}");
        WechatPayV3Settings disabled = load();
        assertFalse(disabled.enabled()); assertFalse(disabled.permitsUser("owner-1"));
        rejects(() -> new WechatPayV3Client(disabled));
    }

    @Test public void onlyEnabledPilotsOrExplicitAllOwnersArePermitted() throws Exception {
        assertTrue(settings.permitsUser("owner-1")); assertFalse(settings.permitsUser("owner-2"));
        assertFalse(settings.permitsUser(null)); assertFalse(settings.permitsUser(""));
        assertEquals("wx-offline", settings.appId()); assertEquals("1900000001", settings.mchId());
        assertTrue(settings.notifyUrl().startsWith("https://"));
        config.put("liveForAllOwners", true); assertTrue(load().permitsUser("owner-2"));
    }

    @Test public void pilotAmountPolicyDefaultsToOneFenWhileEnabledOrDisabled() throws Exception {
        assertEquals(1L, maximum(settings));
        config = JSON.parseObject("{\"enabled\":false}");
        assertEquals(1L, maximum(load()));
    }

    @Test public void pilotAmountPolicyAcceptsOnlyPositiveIntegerFenWithinProductionLimit() throws Exception {
        for (long amount : new long[]{1L, 10L, 999999999L}) {
            config.put("pilotMaxTotalFen", amount);
            assertEquals(amount, maximum(load()));
        }
        config.put("enabled", false); config.put("pilotMaxTotalFen", 9);
        assertEquals(9L, maximum(load()));
    }

    @Test public void pilotAmountPolicyRejectsDecimalsStringsOverflowAndNonPositiveValues() throws Exception {
        Object[] invalid = {0, -1, 1000000000L, Long.MAX_VALUE,
            new java.math.BigInteger("18446744073709551617"), new java.math.BigDecimal("1.0"), "1", true, null};
        for (boolean enabled : new boolean[]{true, false}) {
            config.put("enabled", enabled);
            for (Object value : invalid) {
                config.put("pilotMaxTotalFen", value); rejects(() -> load());
            }
        }
    }

    @Test public void allOwnersPolicyUsesProductionCapEvenWithLowerPilotLimit() throws Exception {
        config.put("liveForAllOwners", true); config.put("pilotMaxTotalFen", 1);
        assertEquals(999999999L, maximum(load()));
        config = JSON.parseObject("{\"enabled\":false,\"liveForAllOwners\":true}");
        assertEquals(999999999L, maximum(load()));
    }

    private static long maximum(WechatPayV3Settings settings) throws Exception {
        try { return (Long) WechatPayV3Settings.class.getMethod("maximumTotalFee").invoke(settings); }
        catch (NoSuchMethodException missing) { throw new AssertionError("Missing trusted amount policy API", missing); }
    }

    @Test public void disabledNewOrdersRetainAuthenticatedReconciliation() throws Exception {
        config.put("enabled", false);
        WechatPayV3Settings disabled = load(); assertFalse(disabled.permitsUser("owner-1"));
        WechatPayV3Client reconciliation = new WechatPayV3Client(disabled, c -> transport = new FixtureTransport(c));
        transport.responseBody = transaction().toJSONString();
        assertEquals("SUCCESS", reconciliation.query("order-1").getString("trade_state"));
        String body = envelope(transaction());
        assertEquals("SUCCESS", reconciliation.notification(body, signed(body, Instant.now().getEpochSecond(), platform)).getString("trade_state"));
    }

    @Test public void incompleteLiveConfigurationAndUnsafeUrlFailWithoutSecretDetails() throws Exception {
        config.remove("wechatPayPublicKeyId"); rejects(() -> load());
        config.put("wechatPayPublicKeyId", ID); config.put("notifyUrl", "http://example.invalid/notify"); rejects(() -> load());
    }

    @Test public void validNotificationVerifiesExactRawBodyAndDecryptsTransaction() throws Exception {
        String body = " \n" + envelope(transaction()) + "\n ";
        JSONObject result = client.notification(body, signed(body, Instant.now().getEpochSecond(), platform));
        assertEquals("SUCCESS", result.getString("trade_state")); assertEquals("order-1", result.getString("out_trade_no"));
        assertEquals(123, result.getJSONObject("amount").getIntValue("total"));
    }

    @Test public void callbackRejectsTamperingWrongPublicKeyAndWrongId() throws Exception {
        String body = envelope(transaction()); Map<String,String> headers = signed(body, Instant.now().getEpochSecond(), platform);
        rejects(() -> client.notification(body + " ", headers));
        rejects(() -> client.notification(body, signed(body, Instant.now().getEpochSecond(), wrongPlatform)));
        headers.put("Wechatpay-Serial", "PUB_KEY_ID_WRONG"); rejects(() -> client.notification(body, headers));
    }

    @Test public void callbackRejectsMissingAmbiguousHeadersAndStaleOrFutureTimestamp() throws Exception {
        String body = envelope(transaction());
        rejects(() -> client.notification(body, Collections.emptyMap()));
        rejects(() -> client.notification(body, signed(body, Instant.now().getEpochSecond() - 301, platform)));
        rejects(() -> client.notification(body, signed(body, Instant.now().getEpochSecond() + 301, platform)));
        final Map<String,String> headers = signed(body, Instant.now().getEpochSecond(), platform);
        headers.put("wechatpay-serial", "PUB_KEY_ID_OTHER"); rejects(() -> client.notification(body, headers));
        final Map<String,String> huge = signed(body, Instant.now().getEpochSecond(), platform);
        huge.put("Wechatpay-Timestamp", "9223372036854775807"); rejects(() -> client.notification(body, huge));
    }

    @Test public void callbackRejectsUnsupportedEventResourceAlgorithmAndFailedTransaction() throws Exception {
        JSONObject event = JSON.parseObject(envelope(transaction()));
        event.put("event_type", "REFUND.SUCCESS"); rejectsEvent(event);
        event.put("event_type", "TRANSACTION.SUCCESS"); event.put("resource_type", "plaintext"); rejectsEvent(event);
        event.put("resource_type", "encrypt-resource"); event.getJSONObject("resource").put("algorithm", "OTHER"); rejectsEvent(event);
        JSONObject transaction = transaction(); transaction.put("trade_state", "NOTPAY"); rejectsEvent(JSON.parseObject(envelope(transaction)));
    }

    @Test public void callbackRejectsAuthenticatedButUndecryptableCiphertext() throws Exception {
        JSONObject event = JSON.parseObject(envelope(transaction()));
        event.getJSONObject("resource").put("ciphertext", Base64.getEncoder().encodeToString(new byte[32])); rejectsEvent(event);
    }

    @Test public void prepayUsesSdkAndReturnsVerifiableRsaBrowserSignature() throws Exception {
        transport.responseBody = "{\"prepay_id\":\"synthetic-prepay\"}";
        Map<String,String> payment = client.prepay("物业费", "order-1", 123L, "openid-offline");
        assertEquals("RSA", payment.get("signType")); assertEquals("0", payment.get("code"));
        assertEquals("order-1", payment.get("orderId")); assertEquals(payment.get("paySign"), payment.get("sign"));
        assertEquals("prepay_id=synthetic-prepay", payment.get("package"));
        String message = payment.get("appId") + "\n" + payment.get("timeStamp") + "\n" + payment.get("nonceStr") + "\n" + payment.get("package") + "\n";
        Signature verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(merchantPublic);
        verifier.update(message.getBytes(StandardCharsets.UTF_8)); assertTrue(verifier.verify(Base64.getDecoder().decode(payment.get("paySign"))));
        JSONObject request = JSON.parseObject(((JsonRequestBody) transport.request.getBody()).getBody());
        assertEquals(123, request.getJSONObject("amount").getIntValue("total"));
        assertEquals("CNY", request.getJSONObject("amount").getString("currency"));
        assertEquals("openid-offline", request.getJSONObject("payer").getString("openid"));
        assertEquals(settings.notifyUrl(), request.getString("notify_url"));
    }

    @Test public void invalidPrepayNeverCallsTransport() throws Exception {
        rejects(() -> client.prepay("fee", "order-1", 0, "openid"));
        rejects(() -> client.prepay("fee", "order-1", (long) Integer.MAX_VALUE + 1, "openid"));
        rejects(() -> client.prepay("fee", "order-1", 1, "")); assertNull(transport.request);
    }

    @Test public void queryReturnsProviderFieldNamesAndCloseUsesValidated204() throws Exception {
        transport.responseBody = transaction().toJSONString(); JSONObject result = client.query("order-1");
        assertEquals("wx-offline", result.getString("appid")); assertEquals("1900000001", result.getString("mchid"));
        assertEquals("transaction-test", result.getString("transaction_id"));
        assertEquals("CNY", result.getJSONObject("amount").getString("currency"));
        assertTrue(transport.request.getUrl().toString().contains("out-trade-no/order-1?mchid=1900000001"));
        transport.status = 204; transport.responseBody = ""; client.close("order-1");
        assertTrue(transport.request.getUrl().toString().endsWith("/order-1/close"));
    }

    @Test public void closedQueryPreservesSignedIdentityWithoutInventingAmount() throws Exception {
        transport.responseBody = "{\"appid\":\"wx-offline\",\"mchid\":\"1900000001\",\"out_trade_no\":\"order-1\",\"trade_state\":\"CLOSED\"}";
        JSONObject result = client.query("order-1");
        assertEquals("CLOSED", result.getString("trade_state"));
        assertEquals("wx-offline", result.getString("appid"));
        assertEquals("1900000001", result.getString("mchid"));
        assertEquals("order-1", result.getString("out_trade_no"));
        assertNull(result.getJSONObject("amount"));
        assertNull(result.getString("trade_type"));
    }

    @Test public void tamperedPrepayQueryAndCloseResponsesAreRejected() throws Exception {
        transport.tamper = true; transport.responseBody = "{\"prepay_id\":\"synthetic\"}";
        rejects(() -> client.prepay("fee", "order-1", 123, "openid"));
        transport.responseBody = transaction().toJSONString(); rejects(() -> client.query("order-1"));
        transport.status = 204; transport.responseBody = ""; rejects(() -> client.close("order-1"));
    }

    private void rejectsEvent(JSONObject event) throws Exception {
        String body = event.toJSONString(); rejects(() -> client.notification(body, signed(body, Instant.now().getEpochSecond(), platform)));
    }
    private static JSONObject transaction() {
        return JSON.parseObject("{\"appid\":\"wx-offline\",\"mchid\":\"1900000001\",\"out_trade_no\":\"order-1\",\"transaction_id\":\"transaction-test\",\"trade_state\":\"SUCCESS\",\"amount\":{\"total\":123,\"currency\":\"CNY\"}}");
    }
    private static String envelope(JSONObject transaction) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES"), new GCMParameterSpec(128, "nonce-123456".getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD("transaction".getBytes(StandardCharsets.UTF_8));
        JSONObject event = JSON.parseObject("{\"event_type\":\"TRANSACTION.SUCCESS\",\"resource_type\":\"encrypt-resource\",\"resource\":{\"algorithm\":\"AEAD_AES_256_GCM\",\"original_type\":\"transaction\",\"associated_data\":\"transaction\",\"nonce\":\"nonce-123456\"}}");
        event.getJSONObject("resource").put("ciphertext", Base64.getEncoder().encodeToString(cipher.doFinal(transaction.toJSONString().getBytes(StandardCharsets.UTF_8))));
        return event.toJSONString();
    }
    private static Map<String,String> signed(String body, long timestamp, KeyPair keys) throws Exception {
        Map<String,String> headers = new HashMap<>();
        headers.put("Wechatpay-Timestamp", String.valueOf(timestamp)); headers.put("Wechatpay-Nonce", "synthetic-nonce");
        headers.put("Wechatpay-Serial", ID); Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate()); signer.update((timestamp + "\nsynthetic-nonce\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        headers.put("Wechatpay-Signature", Base64.getEncoder().encodeToString(signer.sign())); return headers;
    }
    private static String pem(String label, byte[] bytes) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes) + "\n-----END " + label + "-----\n";
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked operation) throws Exception {
        try { operation.run(); fail("Expected rejection"); }
        catch (IllegalArgumentException | IllegalStateException expected) {
            assertNull("SDK details must not leak through cause", expected.getCause());
            assertFalse(expected.toString().contains(KEY)); assertFalse(expected.toString().contains("ciphertext"));
        }
    }
    private static class FixtureTransport extends AbstractHttpClient {
        String responseBody = ""; int status = 200; boolean tamper; HttpRequest request;
        FixtureTransport(Config config) { super(config.createCredential(), config.createValidator()); }
        protected OriginalResponse innerExecute(HttpRequest request) {
            this.request = request;
            try {
                Map<String,String> headers = signed(responseBody, Instant.now().getEpochSecond(), tamper ? wrongPlatform : platform);
                return new OriginalResponse.Builder().request(request).headers(headers).statusCode(status)
                    .contentType("application/json").body(responseBody).build();
            } catch (Exception e) { throw new AssertionError(e); }
        }
        protected InputStream innerDownload(HttpRequest request) { throw new AssertionError("No network permitted"); }
        protected String getHttpClientInfo() { return "offline-fixture"; }
    }
}
