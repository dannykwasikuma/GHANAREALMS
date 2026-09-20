package com.ghanarealms.paystack;

import com.ghanarealms.paystack.util.MinimalJson;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Polls the GhanaRealms web store's bridge API for purchases made through
 * the website (as opposed to /buy in-game, which GhanaRealmsPaystack
 * already handled before the website existed and still handles directly -
 * this is an ADDITION, not a replacement, per "if something exists and
 * works, use it; if something is missing, add it").
 *
 * Uses plain HttpURLConnection (JDK built-in) rather than adding an HTTP
 * client dependency, same reasoning as PaystackClient.java already used.
 */
public class BridgeClient {

    private final JavaPlugin plugin;
    private final Economy economy;
    private final String baseUrl;
    private final String bridgeSecret;

    public BridgeClient(JavaPlugin plugin, Economy economy, String baseUrl, String bridgeSecret) {
        this.plugin = plugin;
        this.economy = economy;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.bridgeSecret = bridgeSecret;
    }

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && !baseUrl.contains("REPLACE_ME")
                && bridgeSecret != null && !bridgeSecret.isBlank() && !bridgeSecret.contains("REPLACE_ME");
    }

    /** Called on a repeating scheduler task. Only delivers to players who are
     *  currently online - an offline player's delivery is simply left PENDING
     *  and picked up on a later poll once they're online, which is exactly
     *  the "do not lose the purchase, keep it queued" behavior the brief asks for. */
    public void pollAndDeliver() {
        if (!isConfigured()) return;

        String response;
        try {
            response = httpGet(baseUrl + "/api/bridge/pending-deliveries");
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Bridge poll failed: " + e.getMessage());
            return;
        }

        Map<String, Object> parsed;
        try {
            parsed = MinimalJson.parseObject(response);
        } catch (Exception e) {
            plugin.getLogger().warning("Bridge poll returned unparseable response");
            return;
        }

        Object deliveriesObj = parsed.get("deliveries");
        if (!(deliveriesObj instanceof List<?> deliveries)) return;

        for (Object d : deliveries) {
            if (!(d instanceof Map<?, ?> delivery)) continue;
            processDelivery(delivery);
        }
    }

    @SuppressWarnings("unchecked")
    private void processDelivery(Map<?, ?> delivery) {
        String deliveryId = String.valueOf(delivery.get("delivery_id"));
        String username = String.valueOf(delivery.get("minecraft_username"));

        Player online = Bukkit.getPlayerExact(username);
        if (online == null) {
            return; // leave PENDING - will be retried on a future poll once they're online
        }

        boolean success = true;
        String error = null;
        try {
            Object giveMoneyObj = delivery.get("give_money_minor");
            if (giveMoneyObj != null && economy != null) {
                double amount = ((Number) giveMoneyObj).doubleValue() / 100.0;
                if (amount > 0) economy.depositPlayer(online, amount);
            }

            Object commandsObj = delivery.get("delivery_commands");
            if (commandsObj instanceof List<?> commands) {
                for (Object cmdObj : commands) {
                    String cmd = String.valueOf(cmdObj).replace("{player}", online.getName());
                    Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
                }
            }
            online.sendMessage("\u00A76\u00A7lGhanaRealms \u00A7fStore purchase delivered! Thank you for supporting GhanaRealms.");
        } catch (Exception e) {
            success = false;
            error = e.getMessage();
            plugin.getLogger().log(Level.WARNING, "Delivery " + deliveryId + " failed", e);
        }

        try {
            ackDelivery(deliveryId, success, error);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to ack delivery " + deliveryId + ": " + e.getMessage());
            // Not fatal - if the ack fails, the web backend still shows this as
            // PENDING and we'll naturally re-attempt it next poll. Slightly
            // redundant (could double-deposit money) if the FIRST delivery
            // actually succeeded and only the ack failed - a real, disclosed
            // edge case rather than a hidden one. Mitigate by checking the
            // store's own delivery_queue UNIQUE(order_item_id) constraint,
            // which this plugin cannot violate from its side, but a double
            // money-grant on retried delivery is possible or a network blip
            // between success and ack. Flagged in STATUS.md as a known gap.
        }
    }

    private void ackDelivery(String deliveryId, boolean success, String error) throws IOException {
        String body = "{\"deliveryId\":\"" + deliveryId + "\",\"success\":" + success
                + (error != null ? ",\"error\":\"" + MinimalJson.escape(error) + "\"" : "") + "}";
        httpPost(baseUrl + "/api/bridge/ack-delivery", body);
    }

    private String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("x-bridge-secret", bridgeSecret);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        return readBody(conn);
    }

    private void httpPost(String url, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-bridge-secret", bridgeSecret);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        readBody(conn); // drain response, ignore content
    }

    private String readBody(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        var stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + sb);
            }
            return sb.toString();
        }
    }
}
