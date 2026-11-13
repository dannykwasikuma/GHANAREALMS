package com.ghanarealms.paystack;

import com.ghanarealms.paystack.storage.PurchaseStore;
import com.ghanarealms.paystack.util.MinimalJson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Receives Paystack's webhook POST and verifies its signature before trusting
 * anything in the payload. This is the "secure webhook handling" and
 * "duplicate transaction protection" the audit flagged as missing from every
 * cloned repository - none of them had this.
 *
 * IMPORTANT: Paystack calls this server directly over the internet, so:
 *   1. WEBHOOK.PORT must be reachable from the internet (port-forward / reverse proxy).
 *   2. Set this exact URL (https://yourdomain/ + WEBHOOK.PATH) in the Paystack
 *      dashboard under Settings -> API Keys & Webhooks -> Webhook URL.
 *   3. Paystack requires HTTPS in production - put this behind a reverse proxy
 *      (nginx/Caddy) that terminates TLS and forwards to this plugin's HTTP port.
 *      This plugin deliberately does NOT implement TLS itself.
 */
public class WebhookServer {

    private final JavaPlugin plugin;
    private final PurchaseStore store;
    private final PaystackClient client;
    private final String secretKey;
    private final Consumer<PurchaseStore.Purchase> onVerifiedSuccess;
    private HttpServer server;

    public WebhookServer(JavaPlugin plugin, PurchaseStore store, PaystackClient client,
                          String secretKey, Consumer<PurchaseStore.Purchase> onVerifiedSuccess) {
        this.plugin = plugin;
        this.store = store;
        this.client = client;
        this.secretKey = secretKey;
        this.onVerifiedSuccess = onVerifiedSuccess;
    }

    public void start(int port, String path) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext(path, new Handler());
            server.setExecutor(null);
            server.start();
            plugin.getLogger().info("Paystack webhook listener started on port " + port + path);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not start webhook server on port " + port + ": " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                byte[] rawBody = readAll(exchange.getRequestBody());
                String signatureHeader = exchange.getRequestHeaders().getFirst("x-paystack-signature");

                if (signatureHeader == null || !verifySignature(rawBody, signatureHeader)) {
                    plugin.getLogger().warning("Rejected webhook with invalid/missing x-paystack-signature");
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }

                // Respond 200 immediately - Paystack expects a fast ack. Do the actual
                // work (which includes a second HTTP call to Paystack to VERIFY, and
                // Bukkit API calls) on the main server thread afterwards.
                exchange.sendResponseHeaders(200, -1);

                String bodyStr = new String(rawBody, StandardCharsets.UTF_8);
                Bukkit.getScheduler().runTask(plugin, () -> processVerifiedPayload(bodyStr));
            } catch (Exception e) {
                plugin.getLogger().severe("Webhook handler error: " + e.getMessage());
                try {
                    exchange.sendResponseHeaders(500, -1);
                } catch (IOException ignored) {
                }
            } finally {
                exchange.close();
            }
        }
    }

    private boolean verifySignature(byte[] rawBody, String signatureHeader) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] computed = mac.doFinal(rawBody);
            String computedHex = toHex(computed);
            // Constant-time-ish comparison to avoid trivial timing attacks
            return constantTimeEquals(computedHex, signatureHeader.trim());
        } catch (Exception e) {
            plugin.getLogger().severe("Signature verification error: " + e.getMessage());
            return false;
        }
    }

    /**
     * The webhook payload tells us WHICH reference to look at, but we never
     * trust its status/amount directly - we call Paystack's verify endpoint
     * (server-to-server, authenticated with our secret key) and act on THAT
     * response. This defeats a forged webhook body even if someone guessed
     * a valid-looking signature format, and it's the same reference-then-
     * verify pattern Paystack's own docs recommend.
     */
    private void processVerifiedPayload(String bodyStr) {
        Map<String, Object> payload;
        try {
            payload = MinimalJson.parseObject(bodyStr);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not parse webhook payload: " + e.getMessage());
            return;
        }

        String event = String.valueOf(payload.get("event"));
        if (!"charge.success".equals(event)) {
            return; // ignore everything except successful charges
        }

        Object refObj = MinimalJson.getPath(payload, "data.reference");
        if (refObj == null) return;
        String reference = String.valueOf(refObj);

        PurchaseStore.Purchase pending = store.find(reference);
        if (pending == null) {
            plugin.getLogger().warning("Webhook for unknown reference " + reference + " (not created by this server)");
            return;
        }
        if (pending.status() == PurchaseStore.Status.SUCCESS) {
            plugin.getLogger().info("Webhook for already-processed reference " + reference + " - ignoring (duplicate protection)");
            return;
        }

        PaystackClient.VerifyResult verify = client.verifyTransaction(reference);
        if (!verify.ok() || !"success".equals(verify.status())) {
            store.markFailed(reference);
            plugin.getLogger().info("Verification did not confirm success for " + reference + ": " + verify.message());
            return;
        }

        // Amount sanity check: what Paystack actually confirms must match what we
        // recorded as pending, or something is wrong (price tampering, wrong package).
        if (Math.abs(verify.amountGhs() - pending.amountGhs()) > 0.01) {
            plugin.getLogger().severe("Amount mismatch for " + reference + ": expected " + pending.amountGhs() + " got " + verify.amountGhs() + " - NOT delivering, needs manual review");
            return;
        }

        boolean firstTime = store.markSuccessOnce(reference);
        if (!firstTime) {
            plugin.getLogger().info("Reference " + reference + " was already marked SUCCESS by another path - skipping delivery");
            return;
        }

        onVerifiedSuccess.accept(pending);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) result |= a.charAt(i) ^ b.charAt(i);
        return result == 0;
    }
}
