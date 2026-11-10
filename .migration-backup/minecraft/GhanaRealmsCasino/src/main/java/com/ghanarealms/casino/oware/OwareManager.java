package com.ghanarealms.casino.oware;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class OwareManager implements Listener {

    private record Challenge(UUID challenger, UUID target, double wager, long expiresAt) {}

    private final JavaPlugin plugin;
    private final Economy economy;
    private final Map<UUID, Challenge> pendingChallenges = new HashMap<>(); // keyed by target
    private final Map<UUID, OwareBoard> activeGames = new HashMap<>(); // keyed by either player's uuid
    private final Map<UUID, org.bukkit.inventory.Inventory> openInventories = new HashMap<>();
    private static final String TITLE_PREFIX = "\u00A76Oware vs ";

    public OwareManager(JavaPlugin plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void challenge(Player challenger, Player target, double wager) {
        if (economy != null && (!economy.has(challenger, wager) || !economy.has(target, wager))) {
            challenger.sendMessage(color(plugin.getConfig().getString("MESSAGES.INSUFFICIENT-FUNDS")));
            return;
        }
        int timeout = plugin.getConfig().getInt("GENERAL.CHALLENGE-TIMEOUT-SECONDS", 60);
        pendingChallenges.put(target.getUniqueId(), new Challenge(challenger.getUniqueId(), target.getUniqueId(), wager, System.currentTimeMillis() + timeout * 1000L));

        challenger.sendMessage(color(plugin.getConfig().getString("MESSAGES.CHALLENGE-SENT")
                .replace("{opponent}", target.getName()).replace("{amount}", String.valueOf(wager)).replace("{timeout}", String.valueOf(timeout))));
        target.sendMessage(color(plugin.getConfig().getString("MESSAGES.CHALLENGE-RECEIVED")
                .replace("{challenger}", challenger.getName()).replace("{amount}", String.valueOf(wager))));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Challenge c = pendingChallenges.get(target.getUniqueId());
            if (c != null && c.challenger.equals(challenger.getUniqueId())) {
                pendingChallenges.remove(target.getUniqueId());
                challenger.sendMessage(color(plugin.getConfig().getString("MESSAGES.CHALLENGE-EXPIRED")));
            }
        }, timeout * 20L);
    }

    public void accept(Player target, Player challenger) {
        Challenge c = pendingChallenges.get(target.getUniqueId());
        if (c == null || !c.challenger.equals(challenger.getUniqueId())) {
            target.sendMessage(color("&cNo pending challenge from that player."));
            return;
        }
        pendingChallenges.remove(target.getUniqueId());

        if (economy != null) {
            if (!economy.has(challenger, c.wager) || !economy.has(target, c.wager)) {
                target.sendMessage(color(plugin.getConfig().getString("MESSAGES.INSUFFICIENT-FUNDS")));
                return;
            }
            economy.withdrawPlayer(challenger, c.wager);
            economy.withdrawPlayer(target, c.wager);
        }

        OwareBoard board = new OwareBoard(c.challenger, c.target, c.wager);
        activeGames.put(c.challenger, board);
        activeGames.put(c.target, board);

        openBoardFor(challenger, board, true);
        openBoardFor(target, board, false);

        String startMsg = color(plugin.getConfig().getString("MESSAGES.GAME-STARTED").replace("{turn}", challenger.getName()));
        challenger.sendMessage(startMsg);
        target.sendMessage(startMsg);
    }

    private void openBoardFor(Player p, OwareBoard board, boolean isA) {
        String opponentName = Bukkit.getOfflinePlayer(isA ? board.playerB : board.playerA).getName();
        var inv = OwareGui.render(board, isA, TITLE_PREFIX + opponentName);
        openInventories.put(p.getUniqueId(), inv);
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!event.getView().getTitle().startsWith(TITLE_PREFIX)) return;
        event.setCancelled(true);

        OwareBoard board = activeGames.get(player.getUniqueId());
        if (board == null) return;

        boolean isA = board.playerA.equals(player.getUniqueId());
        int slot = event.getRawSlot();
        if (slot < 18 || slot > 23) return; // only the player's own row is clickable

        int localPit = slot - 18;
        String error = board.play(isA, localPit);
        if (error != null) {
            player.sendMessage(color("&c" + error.replace('_', ' ').toLowerCase()));
            return;
        }

        refreshBothViews(board);

        if (board.isOver()) {
            board.sweepRemaining();
            finishGame(board);
        }
    }

    private void refreshBothViews(OwareBoard board) {
        Player a = Bukkit.getPlayer(board.playerA);
        Player b = Bukkit.getPlayer(board.playerB);
        if (a != null && a.getOpenInventory().getTitle().startsWith(TITLE_PREFIX)) {
            a.getOpenInventory().getTopInventory().setContents(OwareGui.render(board, true, "").getContents());
        }
        if (b != null && b.getOpenInventory().getTitle().startsWith(TITLE_PREFIX)) {
            b.getOpenInventory().getTopInventory().setContents(OwareGui.render(board, false, "").getContents());
        }
    }

    private void finishGame(OwareBoard board) {
        activeGames.remove(board.playerA);
        activeGames.remove(board.playerB);

        UUID winner = board.winner();
        double pot = board.wager * 2;
        Player a = Bukkit.getPlayer(board.playerA);
        Player b = Bukkit.getPlayer(board.playerB);

        if (winner == null) {
            // draw - refund both wagers rather than an arbitrary tiebreak
            if (economy != null) {
                economy.depositPlayer(Bukkit.getOfflinePlayer(board.playerA), board.wager);
                economy.depositPlayer(Bukkit.getOfflinePlayer(board.playerB), board.wager);
            }
            if (a != null) a.sendMessage(color("&eDraw - your wager was refunded."));
            if (b != null) b.sendMessage(color("&eDraw - your wager was refunded."));
            return;
        }

        if (economy != null) {
            economy.depositPlayer(Bukkit.getOfflinePlayer(winner), pot);
        }
        String winnerName = Bukkit.getOfflinePlayer(winner).getName();
        String msg = color(plugin.getConfig().getString("MESSAGES.GAME-WON")
                .replace("{winner}", winnerName).replace("{pot}", String.valueOf(pot)));
        if (a != null) a.sendMessage(msg);
        if (b != null) b.sendMessage(msg);
    }

    public void forfeit(Player player) {
        OwareBoard board = activeGames.get(player.getUniqueId());
        if (board == null) {
            player.sendMessage(color("&cYou are not in a game."));
            return;
        }
        activeGames.remove(board.playerA);
        activeGames.remove(board.playerB);
        UUID winner = board.playerA.equals(player.getUniqueId()) ? board.playerB : board.playerA;
        double pot = board.wager * 2;
        if (economy != null) economy.depositPlayer(Bukkit.getOfflinePlayer(winner), pot);
        String msg = color(plugin.getConfig().getString("MESSAGES.FORFEITED")
                .replace("{player}", player.getName())
                .replace("{winner}", Bukkit.getOfflinePlayer(winner).getName())
                .replace("{pot}", String.valueOf(pot)));
        Player w = Bukkit.getPlayer(winner);
        if (w != null) w.sendMessage(msg);
        player.sendMessage(msg);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Quitting mid-game forfeits, same as /oware forfeit, rather than
        // leaving the opponent's wager stuck in limbo.
        if (activeGames.containsKey(event.getPlayer().getUniqueId())) {
            forfeit(event.getPlayer());
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
