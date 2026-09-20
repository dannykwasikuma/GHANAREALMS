package com.ghanarealms.parkour;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GhanaRealmsParkour extends JavaPlugin implements Listener {

    private record ActiveRun(String course, long startedAt, int checkpointIndex) {}

    private Map<String, CourseDefinition> courses;
    private LeaderboardStore leaderboards;
    private Economy economy;
    private final Map<UUID, ActiveRun> activeRuns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        courses = CourseDefinition.loadAll(getConfig().getConfigurationSection("COURSES"));
        leaderboards = new LeaderboardStore(this);
        setupVault();

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("parkour").setExecutor(this::onParkour);
        getCommand("parkouradmin").setExecutor(this::onAdmin);

        getLogger().info("GhanaRealmsParkour enabled with " + courses.size() + " course(s).");
    }

    private void setupVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        Player player = event.getPlayer();
        ActiveRun run = activeRuns.get(player.getUniqueId());
        if (run == null) return;

        CourseDefinition course = courses.get(run.course());
        if (course == null) return;
        double radius = getConfig().getDouble("GENERAL.CHECKPOINT-RADIUS", 1.5);

        if (course.atCheckpoint(player.getLocation(), run.checkpointIndex(), radius)) {
            int next = run.checkpointIndex() + 1;
            activeRuns.put(player.getUniqueId(), new ActiveRun(run.course(), run.startedAt(), next));
            player.sendMessage(color(getConfig().getString("MESSAGES.CHECKPOINT", "")
                    .replace("{index}", String.valueOf(next)).replace("{total}", String.valueOf(course.checkpoints.size()))));
            return;
        }

        if (run.checkpointIndex() >= course.checkpoints.size() && course.atFinish(player.getLocation())) {
            finishRun(player, run, course);
        }
    }

    private void finishRun(Player player, ActiveRun run, CourseDefinition course) {
        activeRuns.remove(player.getUniqueId());
        long elapsed = System.currentTimeMillis() - run.startedAt();
        boolean isBest = leaderboards.recordTime(course.id, player.getUniqueId(), elapsed);
        Long best = leaderboards.bestTime(course.id, player.getUniqueId());

        player.sendMessage(color(getConfig().getString("MESSAGES.FINISHED", "")
                .replace("{course}", course.displayName)
                .replace("{time}", formatTime(elapsed))
                .replace("{best}", formatTime(best))));

        boolean rewardOnce = getConfig().getBoolean("GENERAL.REWARD-ONCE-PER-PLAYER", true);
        boolean shouldReward = !rewardOnce || leaderboards.isFirstFinish(course.id, player.getUniqueId());
        if (shouldReward && economy != null) {
            double reward = getConfig().getDouble("GENERAL.REWARD-MONEY-ON-FINISH", 100);
            economy.depositPlayer(player, reward);
            player.sendMessage(color(getConfig().getString("MESSAGES.FINISHED-FIRST-TIME", "")
                    .replace("{reward}", String.valueOf(reward))));
        }
    }

    private String formatTime(Long millis) {
        if (millis == null) return "-";
        long totalSeconds = millis / 1000;
        long ms = millis % 1000;
        return String.format("%d:%02d.%03d", totalSeconds / 60, totalSeconds % 60, ms);
    }

    private boolean onParkour(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage(color("&7Usage: /parkour <start|leave|top> <course>"));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "start" -> {
                if (args.length < 2) { player.sendMessage(color("&7Usage: /parkour start <course>")); return true; }
                CourseDefinition course = courses.get(args[1].toUpperCase());
                if (course == null) { player.sendMessage(color(getConfig().getString("MESSAGES.UNKNOWN-COURSE"))); return true; }
                World world = Bukkit.getWorld(course.world);
                if (world == null) { player.sendMessage(color("&cCourse world not loaded.")); return true; }
                activeRuns.put(player.getUniqueId(), new ActiveRun(course.id, System.currentTimeMillis(), 0));
                player.teleport(course.startLocation(world));
                player.sendMessage(color(getConfig().getString("MESSAGES.STARTED", "").replace("{course}", course.displayName)));
            }
            case "leave" -> {
                if (activeRuns.remove(player.getUniqueId()) == null) {
                    player.sendMessage(color(getConfig().getString("MESSAGES.NO-ACTIVE-RUN")));
                } else {
                    player.sendMessage(color(getConfig().getString("MESSAGES.LEFT")));
                }
            }
            case "top" -> {
                if (args.length < 2) { player.sendMessage(color("&7Usage: /parkour top <course>")); return true; }
                String courseId = args[1].toUpperCase();
                var top = leaderboards.top(courseId, 10);
                if (top.isEmpty()) { player.sendMessage(color(getConfig().getString("MESSAGES.NO-TIMES"))); return true; }
                player.sendMessage(color(getConfig().getString("MESSAGES.TOP-HEADER", "").replace("{course}", courseId)));
                int rank = 1;
                for (var entry : top) {
                    String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                    player.sendMessage(color(getConfig().getString("MESSAGES.TOP-ENTRY", "")
                            .replace("{rank}", String.valueOf(rank++)).replace("{player}", String.valueOf(name)).replace("{time}", formatTime(entry.getValue()))));
                }
            }
            default -> player.sendMessage(color("&7Usage: /parkour <start|leave|top> <course>"));
        }
        return true;
    }

    /** Lets an admin actually build a course by standing where they want each
     *  point and running a command, rather than needing to hand-edit YAML
     *  coordinates - useful since I don't have your real Lobby's layout. */
    private boolean onAdmin(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(color("&7Usage: /parkouradmin <create|setstart|addcheckpoint|setfinish|reload> <course>"));
            return true;
        }
        String action = args[0].toLowerCase();
        String courseId = args[1].toUpperCase();
        var loc = player.getLocation();
        String path = "COURSES." + courseId;

        switch (action) {
            case "create" -> {
                getConfig().set(path + ".DISPLAY-NAME", "&b" + courseId);
                getConfig().set(path + ".WORLD", loc.getWorld().getName());
                saveConfig();
                player.sendMessage(color("&aCreated course " + courseId + ". Now use setstart/addcheckpoint/setfinish."));
            }
            case "setstart" -> {
                getConfig().set(path + ".START.X", loc.getX());
                getConfig().set(path + ".START.Y", loc.getY());
                getConfig().set(path + ".START.Z", loc.getZ());
                saveConfig();
                player.sendMessage(color("&aStart point set for " + courseId + "."));
            }
            case "addcheckpoint" -> {
                var list = getConfig().getMapList(path + ".CHECKPOINTS");
                Map<String, Object> point = new HashMap<>();
                point.put("X", loc.getX());
                point.put("Y", loc.getY());
                point.put("Z", loc.getZ());
                list.add(point);
                getConfig().set(path + ".CHECKPOINTS", list);
                saveConfig();
                player.sendMessage(color("&aCheckpoint #" + list.size() + " added to " + courseId + "."));
            }
            case "setfinish" -> {
                getConfig().set(path + ".FINISH.X", loc.getX());
                getConfig().set(path + ".FINISH.Y", loc.getY());
                getConfig().set(path + ".FINISH.Z", loc.getZ());
                saveConfig();
                player.sendMessage(color("&aFinish point set for " + courseId + "."));
            }
            case "reload" -> {
                reloadConfig();
                courses = CourseDefinition.loadAll(getConfig().getConfigurationSection("COURSES"));
                player.sendMessage(color("&aCourses reloaded from config (" + courses.size() + " total)."));
            }
            default -> player.sendMessage(color("&7Usage: /parkouradmin <create|setstart|addcheckpoint|setfinish|reload> <course>"));
        }
        return true;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
