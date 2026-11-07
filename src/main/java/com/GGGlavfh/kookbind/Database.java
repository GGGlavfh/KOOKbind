package com.GGGlavfh.kookbind;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

// SQLite 数据库管理类
public final class Database {
    private static final String CREATE_BINDINGS = """
            CREATE TABLE IF NOT EXISTS bindings (
                uuid        TEXT PRIMARY KEY,
                kook_id     TEXT NOT NULL,
                player_name TEXT NOT NULL,
                updated_at  INTEGER NOT NULL
            )
            """;
    private static final String CREATE_VERIFICATION_CODES = """
            CREATE TABLE IF NOT EXISTS verification_codes (
                uuid        TEXT PRIMARY KEY,
                code        TEXT NOT NULL,
                player_name TEXT NOT NULL,
                created_at  INTEGER NOT NULL
            )
            """;
    // 归档历史表，记录玩家解绑的 KOOK 账号和解绑时间。unbound_by 是操作者（玩家名 / 控制台）
    private static final String CREATE_BIND_HISTORY = """
            CREATE TABLE IF NOT EXISTS bind_history (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid        TEXT NOT NULL,
                kook_id     TEXT NOT NULL,
                player_name TEXT NOT NULL,
                bound_at    INTEGER NOT NULL,
                unbound_at  INTEGER NOT NULL,
                unbound_by  TEXT
            )
            """;

    private final File file;
    private final Logger logger;
    private Connection connection;

    public Database(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "kookbind.db");
        this.logger = logger;
    }

    //连接数据库并建表
    public synchronized boolean connect() {
        try {
            Class.forName("org.sqlite.JDBC");

            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warning("无法创建数据目录: " + parent.getAbsolutePath());
            }

            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute(CREATE_BINDINGS);
                statement.execute(CREATE_VERIFICATION_CODES);
                statement.execute(CREATE_BIND_HISTORY);
            }
            try (Statement statement = connection.createStatement()) {
                //加速按验证码反查
                statement.execute("CREATE INDEX IF NOT EXISTS idx_verification_codes_code "
                        + "ON verification_codes(code)");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_bindings_kook_id "
                        + "ON bindings(kook_id)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_bind_history_uuid "
                        + "ON bind_history(uuid)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_bind_history_kook_id "
                        + "ON bind_history(kook_id)");
            } catch (SQLException e) {
                logger.log(Level.WARNING, "创建索引失败", e);
            }
            return true;
        } catch (ClassNotFoundException e) {
            logger.log(Level.SEVERE, "未找到 SQLite JDBC 驱动，请检查 plugin.yml 中的 libraries 配置", e);
            return false;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "初始化 SQLite 数据库失败: " + file.getAbsolutePath(), e);
            return false;
        }
    }

    //关闭数据库连接
    public synchronized void disconnect() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "关闭 SQLite 数据库失败", e);
        } finally {
            connection = null;
        }
    }

    public File getFile() {
        return file;
    }

    //保存一条玩家绑定数据。返回是否写入成功：
    public synchronized boolean saveBinding(UUID uuid, String kookId, String playerName) {
        String sql = """
                INSERT INTO bindings (uuid, kook_id, player_name, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    kook_id     = excluded.kook_id,
                    player_name = excluded.player_name,
                    updated_at  = excluded.updated_at
                """;
        try (PreparedStatement statement = prepare(sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, kookId);
            statement.setString(3, playerName);
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "保存绑定数据失败: " + uuid, e);
            return false;
        }
    }

    //删除一条玩家绑定数据，返回是否删除成功（不存在也算成功）
    public synchronized boolean removeBinding(UUID uuid) {
        try (PreparedStatement statement = prepare("DELETE FROM bindings WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "删除绑定数据失败: " + uuid, e);
            return false;
        }
    }

    //解除绑定，并把现役绑定搬到历史表。返回解绑结果和原来的绑定数据（成功时才有）
    public synchronized UnbindOutcome unbind(UUID uuid, String unboundBy) {
        Binding binding = getBinding(uuid);
        if (binding == null) {
            return new UnbindOutcome(UnbindOutcome.Status.NOT_BOUND, null);
        }
        if (!archiveBinding(uuid, binding, unboundBy)) {
            return new UnbindOutcome(UnbindOutcome.Status.DATABASE_ERROR, null);
        }
        return new UnbindOutcome(UnbindOutcome.Status.SUCCESS, binding);
    }

    //归档绑定记录：把现役绑定搬到历史表，并删除现役绑定。事务化操作，失败时回滚。返回是否成功
    private boolean archiveBinding(UUID uuid, Binding binding, String unboundBy) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = prepare(
                    "INSERT INTO bind_history (uuid, kook_id, player_name, bound_at, unbound_at, unbound_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, uuid.toString());
                insert.setString(2, binding.kookId());
                insert.setString(3, binding.playerName());
                insert.setLong(4, binding.boundAt());
                insert.setLong(5, System.currentTimeMillis());
                insert.setString(6, unboundBy);
                insert.executeUpdate();
            }
            try (PreparedStatement delete = prepare("DELETE FROM bindings WHERE uuid = ?")) {
                delete.setString(1, uuid.toString());
                delete.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "归档绑定记录失败: " + uuid, e);
            rollbackQuietly();
            return false;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                logger.log(Level.WARNING, "恢复自动提交失败", e);
            }
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "回滚归档事务失败", e);
        }
    }

    //查某个玩家的历史绑定（最新在前）。
    public synchronized List<BindingHistory> getHistoryByUuid(UUID uuid, int limit) {
        return queryHistory("uuid = ?", uuid.toString(), limit);
    }

    //查某个 KOOK 账号的历史绑定
    public synchronized List<BindingHistory> getHistoryByKookId(String kookId, int limit) {
        return queryHistory("kook_id = ?", kookId, limit);
    }

    //按条件查历史绑定。where 是 SQL 条件，value 是参数值，limit 限制返回行数
    private List<BindingHistory> queryHistory(String where, String value, int limit) {
        List<BindingHistory> rows = new ArrayList<>();
        String sql = "SELECT uuid, kook_id, player_name, bound_at, unbound_at, unbound_by "
                + "FROM bind_history WHERE " + where + " ORDER BY unbound_at DESC, id DESC LIMIT ?";
        try (PreparedStatement statement = prepare(sql)) {
            statement.setString(1, value);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new BindingHistory(
                            result.getString("uuid"),
                            result.getString("kook_id"),
                            result.getString("player_name"),
                            result.getLong("bound_at"),
                            result.getLong("unbound_at"),
                            result.getString("unbound_by")));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "查询绑定历史失败", e);
        }
        return rows;
    }

    //按玩家名反查 uuid，供管理员解绑用。
    public synchronized List<UUID> findUuidsByPlayerName(String playerName) {
        List<UUID> found = new ArrayList<>();
        try (PreparedStatement statement = prepare(
                "SELECT uuid FROM bindings WHERE player_name = ? COLLATE NOCASE")) {
            statement.setString(1, playerName);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String raw = result.getString("uuid");
                    try {
                        found.add(UUID.fromString(raw));
                    } catch (IllegalArgumentException e) {
                        logger.warning("跳过 uuid 不合法的绑定记录: " + raw);
                    }
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "按玩家名查询绑定失败: " + playerName, e);
        }
        return found;
    }

    //根据UUID查询绑定数据
    public synchronized Binding getBinding(UUID uuid) {
        try (PreparedStatement statement = prepare(
                "SELECT kook_id, player_name, updated_at FROM bindings WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new Binding(uuid.toString(),
                            result.getString("kook_id"),
                            result.getString("player_name"),
                            result.getLong("updated_at"));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "查询绑定数据失败: " + uuid, e);
        }
        return null;
    }

    //按 KOOK id 反查绑定。查询功能用它：机器人只知道对方 KOOK id，要换成游戏名
    public synchronized Binding getBindingByKookId(String kookId) {
        try (PreparedStatement statement = prepare(
                "SELECT uuid, kook_id, player_name, updated_at FROM bindings WHERE kook_id = ?")) {
            statement.setString(1, kookId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new Binding(result.getString("uuid"),
                            result.getString("kook_id"),
                            result.getString("player_name"),
                            result.getLong("updated_at"));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "按 KOOK ID 查询绑定失败: " + kookId, e);
        }
        return null;
    }

    //根据UUID查询KOOK ID
    public synchronized String getKookId(UUID uuid) {
        Binding binding = getBinding(uuid);
        return binding == null ? null : binding.kookId();
    }

    //判定玩家是否绑定
    public synchronized boolean isBound(UUID uuid) {
        try (PreparedStatement statement = prepare(
                "SELECT 1 FROM bindings WHERE uuid = ? LIMIT 1")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "查询绑定状态失败: " + uuid, e);
            return false;
        }
    }

    //根据KOOK ID反查已绑定的玩家UUID，用于防止一个KOOK账号绑定多个游戏账号
    public synchronized UUID getUuidByKookId(String kookId) {
        try (PreparedStatement statement = prepare(
                "SELECT uuid FROM bindings WHERE kook_id = ? LIMIT 1")) {
            statement.setString(1, kookId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? UUID.fromString(result.getString("uuid")) : null;
            }
        } catch (SQLException | IllegalArgumentException e) {
            logger.log(Level.WARNING, "按 KOOK ID 查询绑定失败: " + kookId, e);
            return null;
        }
    }

    //保存验证码，覆盖旧验证码，同时记下玩家名。
    public synchronized void saveVerificationCode(UUID uuid, String code, String playerName) {
        String sql = """
                INSERT INTO verification_codes (uuid, code, player_name, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    code        = excluded.code,
                    player_name = excluded.player_name,
                    created_at  = excluded.created_at
                """;
        try (PreparedStatement statement = prepare(sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, code);
            statement.setString(3, playerName);
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "保存验证码失败: " + uuid, e);
        }
    }

    //获取玩家当前验证码。
    public synchronized String getVerificationCode(UUID uuid) {
        try (PreparedStatement statement = prepare(
                "SELECT code FROM verification_codes WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("code") : null;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "查询验证码失败: " + uuid, e);
            return null;
        }
    }

    //验证码是否已被别的玩家占用、且仍在有效期内。
    public synchronized boolean isCodeInUse(String code, long ttlMillis, UUID selfUuid) {
        try (PreparedStatement statement = prepare(
                "SELECT 1 FROM verification_codes WHERE code = ? "
                        + "AND created_at >= ? AND uuid <> ? LIMIT 1")) {
            statement.setString(1, code);
            statement.setLong(2, System.currentTimeMillis() - ttlMillis);
            statement.setString(3, selfUuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "检查验证码是否重复失败", e);
            return false;
        }
    }

    //删除玩家验证码
    public synchronized void removeVerificationCode(UUID uuid) {
        try (PreparedStatement statement = prepare(
                "DELETE FROM verification_codes WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "删除验证码失败: " + uuid, e);
        }
    }

    //删除创建时间早于指定时间的验证码
    public synchronized void purgeVerificationCodes(long ttlMillis) {
        try (PreparedStatement statement = prepare(
                "DELETE FROM verification_codes WHERE created_at < ?")) {
            statement.setLong(1, System.currentTimeMillis() - ttlMillis);
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "清理过期验证码失败", e);
        }
    }

    //并发的绑定请求、以及玩家同时重新 /kookbind 覆盖验证码，都会被串行化，
    public synchronized BindOutcome tryBind(String code, String kookId, long ttlMillis) {
        try {
            //按验证码反查。
            String normalized = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
            List<VerificationCode> matches = new ArrayList<>();
            try (PreparedStatement statement = prepare(
                    "SELECT uuid, player_name, created_at FROM verification_codes "
                            + "WHERE code = ?")) {
                statement.setString(1, normalized);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        matches.add(new VerificationCode(
                                result.getString("uuid"),
                                result.getString("player_name"),
                                result.getLong("created_at")));
                    }
                }
            }
            if (matches.isEmpty()) {
                return new BindOutcome(BindOutcome.Status.CODE_NOT_FOUND, null, null);
            }
            //命中多行时拒绝，防止错绑
            if (matches.size() > 1) {
                return new BindOutcome(BindOutcome.Status.AMBIGUOUS_CODE, null, null);
            }
            VerificationCode entry = matches.get(0);
            UUID uuid = UUID.fromString(entry.uuid());

            //过期校验。清理过期验证码
            if (System.currentTimeMillis() - entry.createdAt() > ttlMillis) {
                removeVerificationCode(uuid);
                return new BindOutcome(BindOutcome.Status.CODE_EXPIRED, null, null);
            }
            //该玩家自己是否已经绑定过
            if (isBound(uuid)) {
                removeVerificationCode(uuid);
                return new BindOutcome(BindOutcome.Status.PLAYER_ALREADY_BOUND, null, null);
            }
            //该 KOOK 账号是否已经绑定了别的玩家
            UUID owner = getUuidByKookId(kookId);
            if (owner != null && !owner.equals(uuid)) {
                removeVerificationCode(uuid);
                return new BindOutcome(BindOutcome.Status.KOOK_ALREADY_BOUND, null, null);
            }
            //写入绑定，确认写进去了之后再消费验证码（一次性）。
            if (!saveBinding(uuid, kookId, entry.playerName())) {
                return new BindOutcome(BindOutcome.Status.DATABASE_ERROR, null, null);
            }
            removeVerificationCode(uuid);
            return new BindOutcome(BindOutcome.Status.SUCCESS, uuid, entry.playerName());
        } catch (SQLException | IllegalArgumentException e) {
            logger.log(Level.WARNING, "处理绑定请求失败", e);
            return new BindOutcome(BindOutcome.Status.DATABASE_ERROR, null, null);
        }
    }

    private PreparedStatement prepare(String sql) throws SQLException {
        if (connection == null) {
            throw new SQLException("数据库连接尚未建立");
        }
        return connection.prepareStatement(sql);
    }

    //记录绑定数据的不可变类。boundAt 是这条绑定写进库的时刻（解绑归档时原样搬进历史表）
    public record Binding(String uuid, String kookId, String playerName, long boundAt) {
    }

    //一条已归档的历史绑定（解绑之后留在这里）
    public record BindingHistory(String uuid, String kookId, String playerName,
                                 long boundAt, long unboundAt, String unboundBy) {
    }

    //一条待使用的验证码
    public record VerificationCode(String uuid, String playerName, long createdAt) {
    }

    //一次绑定尝试的结果，供 WebSocket 层映射成回复给 KOOK 机器人的 reason
    public record BindOutcome(Status status, UUID uuid, String playerName) {

        public enum Status {
            SUCCESS,
            CODE_NOT_FOUND,
            CODE_EXPIRED,
            PLAYER_ALREADY_BOUND,
            KOOK_ALREADY_BOUND,
            AMBIGUOUS_CODE,
            DATABASE_ERROR
        }

        public boolean success() {
            return status == Status.SUCCESS;
        }
    }

    public record UnbindOutcome(Status status, Binding binding) {

        public enum Status {
            SUCCESS,
            NOT_BOUND,
            DATABASE_ERROR
        }
    }
}
