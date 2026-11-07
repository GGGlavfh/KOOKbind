package com.GGGlavfh.kookbind;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

//实现 Websocket.Host：JavaPlugin 自带的 getLogger() 已经满足接口，只需要补上启动失败回调
public final class kookbind extends JavaPlugin implements Websocket.Host {

    //SQLite 数据存储
    private Database database;
    //面向 KOOK 机器人的 WebSocket 服务端
    private Websocket websocket;
    //PlaceholderAPI 变量（没装 PAPI 时为 null）
    private Placeholders placeholders;
    //验证码有效期（毫秒）
    private long verificationCodeTtlMillis;
    //WebSocket 启动失败只处理一次
    private final AtomicBoolean websocketFatal = new AtomicBoolean();
    //验证码清理任务，onDisable 里要显式取消
    private BukkitTask purgeTask;
    //验证码生成用的随机数源
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    //SecureRandom 而不是 Math.random()：后者是可预测的线性同余，不该用来生成一次性凭据
    private static final SecureRandom RANDOM = new SecureRandom();
    //生成时最多重试几次去避开「已被别人占用且未过期」的码
    private static final int CODE_MAX_ATTEMPTS = 8;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        database = new Database(getDataFolder(), getLogger());
        if (!database.connect()) {
            getLogger().severe("数据库初始化失败，插件将被禁用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("SQLite 数据库已就绪: " + database.getFile().getName());

        //验证码有效期，秒转毫秒，并给一个下限，防止被配成 0 导致验证码一生成就过期
        verificationCodeTtlMillis =
                Math.max(30L, getConfig().getLong("verification-code.ttl-seconds", 300L)) * 1000L;

        startWebsocket();

        //PlaceholderAPI 变量（软依赖，没装就跳过）
        registerPlaceholders();

        //定期清理过期验证码。
        long intervalTicks =
                Math.max(1L, getConfig().getLong("verification-code.purge-interval-seconds", 60L)) * 20L;
        purgeTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                () -> {
                    if (!isEnabled()) {
                        return;
                    }
                    database.purgeVerificationCodes(verificationCodeTtlMillis);
                },
                intervalTicks, intervalTicks);
    }

    //启动 WebSocket 服务端
    private void startWebsocket() {
        String host = getConfig().getString("websocket.host", "127.0.0.1");
        int port = getConfig().getInt("websocket.port", 25570);
        int maxFailures = Math.max(1, getConfig().getInt("websocket.max-failures", 10));
        int lostTimeout = Math.max(0, getConfig().getInt("websocket.connection-lost-timeout-seconds", 60));
        boolean allowRemote = getConfig().getBoolean("websocket.allow-remote", false);

        Websocket.Options options = new Websocket.Options(
                verificationCodeTtlMillis, maxFailures, lostTimeout, allowRemote);
        websocket = new Websocket(this, new InetSocketAddress(host, port), database, options);
        websocket.start();
    }

    //由 WebSocket 线程回调：服务端起不来，或者起来之后又整个挂掉了。两种情况下插件都没法再完成绑定，只能把自己禁用掉，免得留个连不上的端口。
    public void onWebsocketFatal(Exception exception) {
        if (!websocketFatal.compareAndSet(false, true)) {
            return;
        }
        getLogger().log(Level.SEVERE, "WebSocket 服务端不可用", exception);
        try {
            getServer().getScheduler().runTask(this, () -> {
                if (!isEnabled()) {
                    return;
                }
                getLogger().severe("WebSocket 服务端不可用，插件将被禁用。"
                        + "请检查 websocket.port 是否已被占用，以及 websocket.host 是否是本机存在的地址。");
                getServer().getPluginManager().disablePlugin(this);
            });
        } catch (IllegalStateException e) {
            getLogger().fine("插件正在关闭，跳过 WebSocket 失败处理");
        }
    }

    //注册 PlaceholderAPI 变量。PAPI 是**软依赖**：没装（或被禁用）时插件照常工作，只是没有这些变量
    private void registerPlaceholders() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().info("未安装/未启用 PlaceholderAPI，跳过变量注册（%kook_bound% / %kook_uid%）");
            return;
        }
        try {
            placeholders = new Placeholders(database,
                    String.join(", ", getPluginMeta().getAuthors()),
                    getPluginMeta().getVersion());
            if (!placeholders.register()) {
                getLogger().warning("PlaceholderAPI 变量注册失败（标识符 kook 可能已被别的插件占用）");
                placeholders = null;
                return;
            }
            getLogger().info("已注册 PlaceholderAPI 变量：%kook_bound% / %kook_uid%");
        } catch (Exception | LinkageError e) {
            //PAPI 版本不兼容时这里可能抛 NoClassDefFoundError 之类的链接错误，
            //不能让一个可选功能把整个插件的 onEnable 拖垮
            placeholders = null;
            getLogger().log(Level.WARNING, "注册 PlaceholderAPI 变量时出错，已跳过", e);
        }
    }

    @Override
    public void onDisable() {
        if (placeholders != null) {
            placeholders.unregister();
            placeholders = null;
        }
        if (purgeTask != null) {
            purgeTask.cancel();
        }
        if (websocket != null) {
            websocket.shutdown();
        }
        if (database != null) {
            database.disconnect();
        }
    }

    //检测命令
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("kookbind")) {
            return false;
        }
        if (database == null) {
            sender.sendMessage("§c数据库尚未就绪，请稍后再试！");
            return true;
        }
        if (args.length == 0) {
            return giveVerificationCode(sender);
        }
        if (args[0].equalsIgnoreCase("unbind")) {
            return unbindCommand(sender, args);
        }
        sender.sendMessage("§c用法: /kookbind [unbind [玩家]]");
        return true;
    }

    // /kookbind：生成一条新验证码给玩家
    private boolean giveVerificationCode(CommandSender sender) {
        //检查命令是否由玩家执行
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该命令只能由玩家执行！");
            return true;
        }
        //检查是否已绑定。顺带把绑到了哪个 KOOK 账号告诉玩家：
        //绑错之后玩家至少能从这儿看到自己绑的是什么，再来找管理员解绑
        Database.Binding binding = database.getBinding(player.getUniqueId());
        if (binding != null) {
            sender.sendMessage("§c你已经绑定过了！（KOOK ID: " + binding.kookId() + "）");
            sender.sendMessage("§c需要换绑请输入/kookbind unbind");
            return true;
        }
        //生成验证码（会重试到不与其他玩家未过期的码冲突为止）
        String verificationCode = generateVerificationCode(player);
        //保存新验证码，覆盖旧验证码（顺便记下玩家名，供 KOOK 机器人绑定时取用）
        database.saveVerificationCode(player.getUniqueId(), verificationCode, player.getName());
        //发送验证码给玩家
        player.sendMessage("§a你的绑定验证码是: §e" + verificationCode);
        player.sendMessage("§a请把验证码发送给 KOOK 机器人完成绑定。");
        return true;
    }

    // /kookbind unbind [玩家]
    private boolean unbindCommand(CommandSender sender, String[] args) {
        if (args.length > 2) {
            sender.sendMessage("§c用法: /kookbind unbind [玩家]");
            return true;
        }
        //不带参数 = 解绑自己
        if (args.length < 2) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c控制台请指定玩家: /kookbind unbind <玩家>");
                return true;
            }
            if (!sender.hasPermission("kookbind.unbind.self")) {
                sender.sendMessage("§c你没有权限解除自己的绑定。");
                return true;
            }
            return unbindOne(sender, player.getUniqueId(), player.getName(), true);
        }
        if (!sender.hasPermission("kookbind.unbind.others")) {
            sender.sendMessage("§c你没有权限解除其他玩家的绑定。");
            return true;
        }

        String target = args[1];
        //依次按「在线玩家名」「UUID」「绑定当时记下的名字」来定位。
        //刻意不用 Bukkit.getOfflinePlayer(name)：玩家没在本机缓存过时它会阻塞主线程去联网查名字
        Player online = Bukkit.getPlayerExact(target);
        if (online != null) {
            return unbindOne(sender, online.getUniqueId(), online.getName(), false);
        }
        try {
            return unbindOne(sender, UUID.fromString(target), target, false);
        } catch (IllegalArgumentException ignored) {
            //不是 UUID，落到按名字查库
        }
        List<UUID> matches = database.findUuidsByPlayerName(target);
        if (matches.isEmpty()) {
            sender.sendMessage("§c没有找到 " + target + " 的绑定记录。"
                    + "若该玩家改过名，请改用 /kookbind unbind <UUID>");
            return true;
        }
        if (matches.size() > 1) {
            //player_name 没有唯一约束，同名是可能的，这种情况必须让管理员明确指定
            sender.sendMessage("§e有 " + matches.size() + " 条名字为 " + target + " 的绑定记录，请用 UUID 指定：");
            for (UUID uuid : matches) {
                sender.sendMessage("§7- " + uuid);
            }
            return true;
        }
        return unbindOne(sender, matches.get(0), target, false);
    }

    //解除指定 uuid 的绑定，并把结果反馈给命令发起者。self 表示发起者就是被解绑的人
    private boolean unbindOne(CommandSender sender, UUID uuid, String name, boolean self) {
        //传操作者名字：解绑不再删数据，而是归档进 bind_history，得记下是谁解的
        Database.UnbindOutcome outcome = database.unbind(uuid, sender.getName());
        switch (outcome.status()) {
            case SUCCESS -> {
                Database.Binding binding = outcome.binding();
                getLogger().info(sender.getName() + " 解除了 " + binding.playerName()
                        + " (" + uuid + ") 与 KOOK " + binding.kookId() + " 的绑定（已归档）");
                sender.sendMessage("§a已解除 " + binding.playerName() + " 与 KOOK "
                        + binding.kookId() + " 的绑定。");
                sender.sendMessage("§7这条记录已归档到历史，不会被删除。");
                sender.sendMessage(self ? "§a你现在可以重新执行 /kookbind 了。"
                        : "§a该玩家现在可以重新执行 /kookbind 了。");
            }
            case NOT_BOUND -> sender.sendMessage(self ? "§c你还没有绑定。"
                    : "§c" + name + " 没有绑定记录。");
            case DATABASE_ERROR -> sender.sendMessage("§c解除绑定失败，请查看服务端日志。");
        }
        return true;
    }

    //生成一条验证码。先查库避开仍被别人占用的码：
    //真撞上时 tryBind 只能回 AMBIGUOUS_CODE，那个码对两个玩家都失效，体验很差
    private String generateVerificationCode(Player player) {
        for (int attempt = 0; attempt < CODE_MAX_ATTEMPTS; attempt++) {
            String code = randomCode();
            if (!database.isCodeInUse(code, verificationCodeTtlMillis, player.getUniqueId())) {
                return code;
            }
        }
        //重试耗尽（概率极低）也不阻断取码：真撞了 tryBind 会回「验证码冲突」，玩家重取一次即可
        getLogger().warning("生成验证码时连续 " + CODE_MAX_ATTEMPTS + " 次撞码，直接使用最后一次结果");
        return randomCode();
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

}
