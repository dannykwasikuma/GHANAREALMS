package com.ghanarealms.casino;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cedi Toss requires the TARGET to explicitly /cedistoss accept - a
 * challenger can never withdraw money from another player without that
 * player's own action. (An earlier draft of this resolved instantly on the
 * challenger's command alone, which would have let anyone force a wager
 * and withdraw from a target without consent - caught and fixed before
 * this ever shipped.)
 */
public class CediTossManager {

    private record PendingToss(UUID challenger, UUID target, double amount, long expiresAt) {}

    private final JavaPlugin plugin;
    private final Economy economy;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, PendingToss> pending = new HashMap<>(); // keyed by target

    public CediTossManager(JavaPlugin plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    public void challenge(Player challenger, Player target, double amount) {
        double min = plugin.getConfig().getDouble("GENERAL.MIN-WAGER", 10);
        double max = plugin.getConfig().getDouble("GENERAL.MAX-WAGER", 100000);
        if (amount < min || amount > max) {
            challenger.sendMessage(color("&cWager must be between \u20B5" + min + " and \u20B5" + max + "."));
            return;
        }
        if (economy == null) {
            challenger.sendMessage(color("&cEconomy plugin not available - cannot wager."));
            return;
        }
        if (!economy.has(challenger, amount)) {
            challenger.sendMessage(color(plugin.getConfig().getString("MESSAGES.INSUFFICIENT-FUNDS")));
            return;
        }

        int timeout = plugin.getConfig().getInt("GENERAL.CHALLENGE-TIMEOUT-SECONDS", 60);
        pending.put(target.getUniqueId(), new PendingToss(challenger.getUniqueId(), target.getUniqueId(), amount, System.currentTimeMillis() + timeout * 1000L));

        challenger.sendMessage(color("&aCedi Toss challenge sent to " + target.getName() + " for \u20B5" + amount + "."));
        target.sendMessage(color("&e" + challenger.getName() + " challenged you to a Cedi Toss for \u20B5" + amount
                + "! Type &f/cedistoss accept " + challenger.getName() + "&e to play."));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingToss p = pending.get(target.getUniqueId());
            if (p != null && p.challenger.equals(challenger.getUniqueId())) {
                pending.remove(target.getUniqueId());
                challenger.sendMessage(color("&cCedi Toss challenge expired."));
            }
        }, timeout * 20L);
    }

    public void accept(Player target, Player challenger) {
        PendingToss p = pending.get(target.getUniqueId());
        if (p == null || !p.challenger.equals(challenger.getUniqueId())) {
            target.sendMessage(color("&cNo pending Cedi Toss challenge from that player."));
            return;
        }
        pending.remove(target.getUniqueId());

        if (economy == null) return;
        if (!economy.has(challenger, p.amount) || !economy.has(target, p.amount)) {
            target.sendMessage(color(plugin.getConfig().getString("MESSAGES.INSUFFICIENT-FUNDS")));
            return;
        }

        economy.withdrawPlayer(challenger, p.amount);
        economy.withdrawPlayer(target, p.amount);

        boolean challengerWins = random.nextBoolean();
        Player winner = challengerWins ? challenger : target;
        Player loser = challengerWins ? target : challenger;
        double pot = p.amount * 2;
        economy.depositPlayer(winner, pot);

        winner.sendMessage(color("&aThe Cedi landed in your favour! You won &6\u20B5" + pot + "&a from " + loser.getName() + "."));
        loser.sendMessage(color("&cThe Cedi landed against you. You lost &6\u20B5" + p.amount + "&c to " + winner.getName() + "."));
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
