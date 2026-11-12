package com.ghanarealms.link;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * GhanaRealmsLinkPlugin
 *
 * This is the one piece flagged as missing across every pass of this
 * project so far: the in-game half of account linking. A player runs
 * /link <CODE> after getting a code from the website's /account/link page;
 * this plugin calls the store's /api/bridge/verify-link endpoint with the
 * player's SERVER-KNOWN (trusted) UUID and username - never anything typed
 * by the player themselves beyond the code - using the same shared-secret
 * header (x-bridge-secret / MINECRAFT_BRIDGE_SECRET) the existing
 * /api/bridge/ack-delivery and /api/bridge/pending-deliveries routes
 * already use.
 *
 * NOT COMPILED OR TESTED. This sandbox has no internet access to fetch the
 * Paper API, and no Java compiler was available in earlier passes either
 * (confirmed directly). This is real, complete source written against the
 * standard Paper/Bukkit API and Java's built-in java.net.http.HttpClient
 * (no extra dependencies needed) - it needs to be built with a real Paper
 * API dependency (see pom.xml/build.gradle you'll need to add) and tested
 * on an actual server before trusting it.
 *
 * Config (config.yml, generated on first run via saveDefaultConfig()):
 *   store-base-url: the GhanaRealms store's base URL (same STORE_BASE_URL
 *     value the store itself uses)
 *   bridge-secret: must exactly match MINECRAFT_BRIDGE_SECRET in the
 *     store's environment - treat this file like any other secret, do not
 *     commit a real value to version control
 */
public class GhanaRealmsLinkPlugin extends JavaPlugin implements CommandExecutor {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("link").setExecutor(this);
        getCommand("myaccount").setExecutor(this);
        getLogger().info("GhanaRealmsLinkPlugin enabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in-game.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("myaccount")) {
            handleStore(player);
            return true;
        }

        if (args.length != 1) {
            player.sendMessage("§eUsage: /link <CODE>  §7(get your code from the GhanaRealms website)");
            return true;
        }

        String code = args[0].trim().toUpperCase();
        if (code.length() != 6) {
            player.sendMessage("§cThat doesn't look like a valid link code.");
            return true;
        }

        FileConfiguration config = getConfig();
        String baseUrl = config.getString("BRIDGE.STORE-BASE-URL");
        String bridgeSecret = config.getString("BRIDGE.BRIDGE-SECRET");

        if (baseUrl == null || baseUrl.isBlank() || bridgeSecret == null || bridgeSecret.isBlank()) {
            getLogger().warning("GhanaRealmsLinkPlugin is not configured - set BRIDGE.STORE-BASE-URL and BRIDGE.BRIDGE-SECRET in config.yml");
            player.sendMessage("§cAccount linking is not configured on this server yet. Please contact staff.");
            return true;
        }

        // Use the server's OWN record of this player's UUID/name - the one
        // thing this command must never do is trust client-submitted
        // identity, per every pass of this project's stated requirements.
        String minecraftUuid = player.getUniqueId().toString();
        String minecraftUsername = player.getName();

        player.sendMessage("§7Verifying your link code...");

        // Run off the main thread - this is a blocking network call and
        // must never stall the server tick.
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String body = "{"
                        + "\"code\":\"" + escapeJson(code) + "\","
                        + "\"minecraftUsername\":\"" + escapeJson(minecraftUsername) + "\","
                        + "\"minecraftUuid\":\"" + escapeJson(minecraftUuid) + "\""
                        + "}";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/bridge/verify-link"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("x-bridge-secret", bridgeSecret)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                getServer().getScheduler().runTask(this, () -> {
                    if (response.statusCode() == 200) {
                        player.sendMessage("§aYour Minecraft account is now linked to your GhanaRealms website account!");
                    } else if (response.body().contains("expired")) {
                        player.sendMessage("§cThat code has expired. Generate a new one on the website.");
                    } else if (response.body().contains("already_consumed")) {
                        player.sendMessage("§cThat code has already been used.");
                    } else if (response.body().contains("already_linked")) {
                        player.sendMessage("§cThis Minecraft account is already linked to a different website account.");
                    } else if (response.body().contains("username_mismatch")) {
                        player.sendMessage("§cThat code was issued for a different username.");
                    } else {
                        player.sendMessage("§cInvalid code. Double-check it on the website and try again.");
                    }
                });
            } catch (Exception e) {
                getLogger().warning("Link verification request failed: " + e.getMessage());
                getServer().getScheduler().runTask(this, () ->
                        player.sendMessage("§cCouldn't reach the GhanaRealms store right now. Try again shortly."));
            }
        });

        return true;
    }

    /**
     * /myaccount - server-first account claiming. Asks the store for a one-time
     * link bound to this player's server-known UUID. Refuses outright on an
     * offline-mode server: without Mojang verification a UUID/username proves
     * nothing, so letting it create an account would let anyone claim
     * someone else's name. NOT COMPILED OR TESTED (see class header).
     */
    private void handleStore(Player player) {
        if (!getServer().getOnlineMode()) {
            player.sendMessage("§cAccount claiming needs an online-mode server. Please contact staff.");
            return;
        }
        FileConfiguration config = getConfig();
        String baseUrl = config.getString("BRIDGE.STORE-BASE-URL");
        String bridgeSecret = config.getString("BRIDGE.BRIDGE-SECRET");
        if (baseUrl == null || baseUrl.isBlank() || bridgeSecret == null || bridgeSecret.isBlank()) {
            player.sendMessage("§cThe store is not configured on this server yet. Please contact staff.");
            return;
        }
        String uuid = player.getUniqueId().toString();
        String name = player.getName();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String body = "{\"minecraftUuid\":\"" + escapeJson(uuid) + "\","
                        + "\"minecraftUsername\":\"" + escapeJson(name) + "\","
                        + "\"onlineMode\":true}";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/bridge/claim-token"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("x-bridge-secret", bridgeSecret)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
                getServer().getScheduler().runTask(this, () -> {
                    if (response.statusCode() == 200 && m.find()) {
                        String url = m.group(1);
                        player.sendMessage(net.kyori.adventure.text.Component
                                .text("§6Open your GhanaRealms account (valid 15 minutes): ")
                                .append(net.kyori.adventure.text.Component.text("§b§n" + url)
                                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url))));
                    } else {
                        player.sendMessage("§cCouldn't create your account link right now. Try again shortly.");
                    }
                });
            } catch (Exception e) {
                getLogger().warning("Claim link request failed: " + e.getMessage());
                getServer().getScheduler().runTask(this, () ->
                        player.sendMessage("§cCouldn't reach the GhanaRealms store right now. Try again shortly."));
            }
        });
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
