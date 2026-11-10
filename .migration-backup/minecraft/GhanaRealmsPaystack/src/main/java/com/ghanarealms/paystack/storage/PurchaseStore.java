package com.ghanarealms.paystack.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Records every transaction reference GhanaRealmsPaystack has ever seen.
 * The UNIQUE constraint on `reference` is the actual duplicate-transaction
 * protection: a webhook (or admin /paystack verify) retry for a reference
 * that already has status SUCCESS is rejected before anything is delivered
 * to the player a second time.
 */
public class PurchaseStore {

    public enum Status { PENDING, SUCCESS, FAILED }

    public record Purchase(int id, String reference, String playerName, String playerUuid,
                            String packageId, double amountGhs, Status status, long createdAt) {}

    private final JavaPlugin plugin;
    private Connection connection;

    public PurchaseStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void connect(String fileName) {
        try {
            plugin.getDataFolder().mkdirs();
            File dbFile = new File(plugin.getDataFolder(), fileName);
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS purchases (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        reference TEXT UNIQUE NOT NULL,
                        player_name TEXT NOT NULL,
                        player_uuid TEXT,
                        package_id TEXT NOT NULL,
                        amount_ghs REAL NOT NULL,
                        status TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                """);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to open purchases database", e);
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {
        }
    }

    /** Inserts a PENDING row for a freshly-initialized checkout. Returns false if the
     *  reference somehow already exists (should not happen - references are generated
     *  by us and are random). */
    public synchronized boolean createPending(String reference, String playerName, String playerUuid,
                                               String packageId, double amountGhs) {
        String sql = "INSERT INTO purchases (reference, player_name, player_uuid, package_id, amount_ghs, status, created_at) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, reference);
            ps.setString(2, playerName);
            ps.setString(3, playerUuid);
            ps.setString(4, packageId);
            ps.setDouble(5, amountGhs);
            ps.setString(6, Status.PENDING.name());
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "createPending failed for " + reference, e);
            return false;
        }
    }

    public synchronized Status getStatus(String reference) {
        String sql = "SELECT status FROM purchases WHERE reference = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, reference);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Status.valueOf(rs.getString("status"));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "getStatus failed for " + reference, e);
        }
        return null;
    }

    /** Atomically marks a PENDING reference SUCCESS. Returns false (and does nothing)
     *  if the reference is missing or already SUCCESS - this is the duplicate-delivery
     *  guard: callers must only deliver the package/money when this returns true. */
    public synchronized boolean markSuccessOnce(String reference) {
        String sql = "UPDATE purchases SET status = ? WHERE reference = ? AND status != ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, Status.SUCCESS.name());
            ps.setString(2, reference);
            ps.setString(3, Status.SUCCESS.name());
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "markSuccessOnce failed for " + reference, e);
            return false;
        }
    }

    public synchronized void markFailed(String reference) {
        String sql = "UPDATE purchases SET status = ? WHERE reference = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, Status.FAILED.name());
            ps.setString(2, reference);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "markFailed failed for " + reference, e);
        }
    }

    public synchronized Purchase find(String reference) {
        String sql = "SELECT * FROM purchases WHERE reference = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, reference);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return fromRow(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "find failed for " + reference, e);
        }
        return null;
    }

    public synchronized List<Purchase> history(String playerName, int limit) {
        List<Purchase> out = new ArrayList<>();
        String sql = "SELECT * FROM purchases WHERE player_name = ? ORDER BY created_at DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerName);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(fromRow(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "history failed for " + playerName, e);
        }
        return out;
    }

    private Purchase fromRow(ResultSet rs) throws SQLException {
        return new Purchase(
                rs.getInt("id"),
                rs.getString("reference"),
                rs.getString("player_name"),
                rs.getString("player_uuid"),
                rs.getString("package_id"),
                rs.getDouble("amount_ghs"),
                Status.valueOf(rs.getString("status")),
                rs.getLong("created_at")
        );
    }
}
