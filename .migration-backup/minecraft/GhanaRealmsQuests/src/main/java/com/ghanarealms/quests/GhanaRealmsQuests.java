package com.ghanarealms.quests;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public class GhanaRealmsQuests extends JavaPlugin implements Listener {

    private Map<String, QuestDefinition> dailyQuests;
    private Map<String, QuestDefinition> weeklyQuests;
    private ProgressStore progressStore;
    private Economy economy;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("quests.yml", false);
        reloadQuestDefinitions();

        progressStore = new ProgressStore(this);
        setupVault();

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("quests").setExecutor(this::onQuestsCommand);
        getCommand("questadmin").setExecutor(this::onAdminCommand);

        getLogger().info("GhanaRealmsQuests enabled with " + dailyQuests.size() + " daily and " + weeklyQuests.size() + " weekly quests.");
    }

    private void setupVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found - quest money rewards will be skipped.");
            return;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    private void reloadQuestDefinitions() {
        var questsYml = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(new java.io.File(getDataFolder(), "quests.yml"));
        dailyQuests = QuestDefinition.loadAll(questsYml.getConfigurationSection("DAILY"), QuestDefinition.Cycle.DAILY);
        weeklyQuests = QuestDefinition.loadAll(questsYml.getConfigurationSection("WEEKLY"), QuestDefinition.Cycle.WEEKLY);
    }

    // -- cycle keys: a stable string that changes exactly when the quest set should reset --
    private String currentDailyKey() {
        return LocalDate.now(ZoneOffset.UTC).toString(); // e.g. 2026-09-19
    }

    private String currentWeeklyKey() {
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        int week = now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int year = now.get(IsoFields.WEEK_BASED_YEAR);
        return year + "-W" + week;
    }

    // -- objective progress plumbing --

    private void progressObjective(Player player, QuestDefinition.Type type, java.util.function.Predicate<QuestDefinition> matcher, int amount) {
        UUID uuid = player.getUniqueId();
        for (var entry : dailyQuests.entrySet()) {
            tryProgress(player, uuid, entry.getValue(), QuestDefinition.Cycle.DAILY, currentDailyKey(), type, matcher, amount);
        }
        for (var entry : weeklyQuests.entrySet()) {
            tryProgress(player, uuid, entry.getValue(), QuestDefinition.Cycle.WEEKLY, currentWeeklyKey(), type, matcher, amount);
        }
    }

    private void tryProgress(Player player, UUID uuid, QuestDefinition quest, QuestDefinition.Cycle cycle,
                              String cycleKey, QuestDefinition.Type type,
                              java.util.function.Predicate<QuestDefinition> matcher, int amount) {
        if (quest.type != type || !matcher.test(quest)) return;
        if (progressStore.isCompleted(uuid, cycle, quest.id, cycleKey)) return;

        int current = progressStore.getProgress(uuid, cycle, quest.id, cycleKey) + amount;
        boolean completed = current >= quest.amount;
        progressStore.setProgress(uuid, cycle, quest.id, cycleKey, Math.min(current, quest.amount), completed);

        if (completed) {
            if (economy != null && quest.rewardMoney > 0) {
                economy.depositPlayer(player, quest.rewardMoney);
            }
            player.sendMessage(color(getConfig().getString("MESSAGES.COMPLETED", "&aCompleted {quest}!")
                    .replace("{quest}", quest.displayName)
                    .replace("{reward}", String.valueOf((long) quest.rewardMoney))));
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        String material = event.getBlock().getType().name();
        progressObjective(event.getPlayer(), QuestDefinition.Type.BREAK_BLOCK,
                q -> material.equals(q.material), 1);
    }

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        progressObjective(event.getPlayer(), QuestDefinition.Type.FISH, q -> true, 1);
    }

    @EventHandler
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        boolean hostile = event.getEntity() instanceof org.bukkit.entity.Monster;
        String type = event.getEntity().getType().name();
        progressObjective(killer, QuestDefinition.Type.KILL_ENTITY,
                q -> "ANY_HOSTILE".equals(q.entityType) ? hostile : type.equals(q.entityType), 1);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        // Only check on block-boundary crossings, not every pixel of movement
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        progressObjective(event.getPlayer(), QuestDefinition.Type.VISIT_REGION,
                q -> q.matchesLocation(event.getPlayer().getLocation()), 1);
    }

    private boolean onQuestsCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        player.sendMessage(color(getConfig().getString("MESSAGES.LIST-HEADER", "&6Your Quests")));
        String dailyKey = currentDailyKey();
        String weeklyKey = currentWeeklyKey();
        player.sendMessage(color("&e--- Daily ---"));
        printList(player, dailyQuests, QuestDefinition.Cycle.DAILY, dailyKey);
        player.sendMessage(color("&e--- Weekly ---"));
        printList(player, weeklyQuests, QuestDefinition.Cycle.WEEKLY, weeklyKey);
        return true;
    }

    private void printList(Player player, Map<String, QuestDefinition> quests, QuestDefinition.Cycle cycle, String cycleKey) {
        for (QuestDefinition q : quests.values()) {
            boolean done = progressStore.isCompleted(player.getUniqueId(), cycle, q.id, cycleKey);
            int progress = progressStore.getProgress(player.getUniqueId(), cycle, q.id, cycleKey);
            String line = done
                    ? color("&a✔ " + ChatColor.stripColor(color(q.displayName)) + " - complete")
                    : color(getConfig().getString("MESSAGES.PROGRESS", "&7{quest}: {progress}/{target}")
                        .replace("{quest}", q.displayName)
                        .replace("{progress}", String.valueOf(progress))
                        .replace("{target}", String.valueOf(q.amount)));
            player.sendMessage(line);
        }
    }

    private boolean onAdminCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /questadmin <reload|resetall>");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            reloadQuestDefinitions();
            sender.sendMessage(color("&aQuest definitions reloaded."));
        } else if (args[0].equalsIgnoreCase("resetall")) {
            // Resetting is implicit: cycle keys are date-derived, so nothing to
            // delete - progress for a past cycleKey is already treated as stale.
            sender.sendMessage(color("&aNothing to do - progress resets automatically each cycle based on the date."));
        }
        return true;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
