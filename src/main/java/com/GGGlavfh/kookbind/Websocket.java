package com.GGGlavfh.kookbind;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

//面向 KOOK 机器人的 WebSocket 服务端。
//协议：Python 端发一条文本 JSON {"code":"Ab3xY","kookId":"1234567890"}，
//插件处理后回一条 JSON 结果（见 sendResult）。
//访问控制完全依赖监听地址，默认只监听 127.0.0.1，不做密钥校验。
public final class Websocket extends WebSocketServer {

    //WebSocket 层只用到「日志」和「启动失败回调」这两样东西。
    //抽成接口是为了让它完全不依赖 Bukkit：所有回调都跑在网络线程上，
    //这里碰不到任何服务端 API，整个线程模型才是安全的；同时也方便脱离服务端单独测试。
    public interface Host {
        Logger getLogger();

        void onWebsocketFatal(Exception exception);
    }

    //WebSocket 层的可调参数
    public record Options(long ttlMillis, int maxFailures,
                          int connectionLostTimeoutSeconds, boolean allowRemoteConnections) {
    }

    //请求里允许出现的字段名
    private static final String FIELD_ACTION = "action";
    private static final String FIELD_CODE = "code";
    private static final String FIELD_KOOK_ID = "kookId";
    //字段长度上限，防止异常输入被写进数据库
    private static final int MAX_CODE_LENGTH = 64;
    private static final int MAX_KOOK_ID_LENGTH = 32;
    private static final int MAX_ACTION_LENGTH = 16;

    //支持的请求类型。action 缺省时按 ACTION_BIND 处理，
    //这样升级插件时旧版机器人（只发 code/kookId）不需要跟着改
    private static final String ACTION_BIND = "bind";
    private static final String ACTION_QUERY = "query";
    private static final String ACTION_HISTORY = "history";

    //一次最多回多少条历史记录：机器人那边只用来展示，不需要整表
    private static final int MAX_HISTORY_RECORDS = 20;

    private final Host host;
    private final Database database;
    private final Gson gson = new Gson();
    //验证码有效期（毫秒）
    private final long ttlMillis;
    //单条连接允许的最大失败次数
    private final int maxFailures;
    //是否允许非本机连接，默认关闭。
    private final boolean allowRemoteConnections;
    //只有走到 onStart() 才置位，用来区分「端口压根没绑上」和「启动成功后又挂掉」
    private volatile boolean started;
    //主动调用过 shutdown() 之后置位。关闭过程本身也会触发 onError(null, ...)，
    //置位之后这些错误就不再被当成服务端故障去禁用插件
    private volatile boolean stopping;

    public Websocket(Host host, InetSocketAddress address, Database database, Options options) {
        super(address);
        this.host = host;
        this.database = database;
        this.ttlMillis = options.ttlMillis();
        this.maxFailures = options.maxFailures();
        this.allowRemoteConnections = options.allowRemoteConnections();
        //以下设置必须在 start() 之前完成，start() 之后再调用会抛 IllegalStateException
        setReuseAddr(true);
        //服务端按此间隔主动发 Ping，收不到 Pong 就判定连接已断开；0 表示关闭心跳
        setConnectionLostTimeout(options.connectionLostTimeoutSeconds());
        //使用守护线程，避免关服时被残留的 selector / decoder 线程拖住
        setDaemon(true);
    }

    //端口绑定成功、decoder 线程启动之后回调
    @Override
    public void onStart() {
        started = true;
        host.getLogger().info("WebSocket 服务端已启动，监听: " + getAddress());
    }

    //注意 onOpen / onClose / onError 这三个回调都跑在 selector 线程上，
    //异常从它们中间漏出去的后果和 onMessage 完全不同：
    //onMessage 在 worker 线程上，异常会被 worker 自己捕获、只关掉当前连接；
    //而 selector 线程的异常会被 WebSocketServer.run() 接住并直接 stop() 整个服务端，
    //之后插件仍然以为自己活着（started 还是 true），表现就是机器人再也连不上。
    //所以这三个回调里的异常必须就地吞掉。
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        try {
            InetSocketAddress remote = conn.getRemoteSocketAddress();
            //纵深防御：默认只接受本机来的连接
            if (!allowRemoteConnections && !isLocal(remote)) {
                host.getLogger().warning("拒绝来自非本机的 WebSocket 连接: " + remote
                        + "。KOOK 机器人不在本机时，请把 config.yml 的 websocket.allow-remote 设为 true");
                conn.close(1008, "only loopback connections are allowed");
                return;
            }
            //每条连接的失败计数放在 attachment 里，连接销毁时一起回收
            conn.setAttachment(new AtomicInteger());
            host.getLogger().info("KOOK 机器人已连接: " + remote);
        } catch (Throwable t) {
            host.getLogger().log(Level.SEVERE, "处理 WebSocket 连接建立时发生异常", t);
        }
    }

    //判断对端是不是本机。这里不能用 isLoopbackAddress()：
    //机器人在本机、但连的是本机的非回环地址（比如 host 改了之后连 ws://192.168.1.5:25570）时，
    //源地址并不是 127.0.0.1，只看回环会把这种正常连接误拒。
    //NetworkInterface.getByInetAddress 判断的是「这个地址是否属于本机」，既能放行这种情况，
    //也依然挡得住真正的外部地址。
    private boolean isLocal(InetSocketAddress remote) {
        if (remote == null || remote.getAddress() == null) {
            return false;
        }
        InetAddress address = remote.getAddress();
        if (address.isLoopbackAddress()) {
            return true;
        }
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (SocketException e) {
            //查不出来就按外部地址处理，宁可拒绝
            host.getLogger().log(Level.WARNING, "判断连接来源时出错: " + address, e);
            return false;
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        //onMessage 里抛出的异常本库只会打日志、不会关闭连接，所以这里必须兜底
        try {
            handleMessage(conn, message);
        } catch (Throwable e) {
            host.getLogger().log(Level.WARNING, "处理 WebSocket 消息时发生未预期异常", e);
            sendResult(conn, false, "INTERNAL_ERROR", "服务器内部错误", null, null);
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        //协议只认文本帧。基类这个方法的默认实现是静默丢弃，会让 Python 端一直等不到回包
        fail(conn, "BAD_FRAME", "只支持文本 JSON 消息");
    }

    //同样在 selector 线程上，异常不能外泄
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        try {
            host.getLogger().info("KOOK 机器人连接已断开: code=" + code + " reason=" + reason);
        } catch (Throwable t) {
            host.getLogger().log(Level.SEVERE, "处理 WebSocket 连接关闭时发生异常", t);
        }
    }

    //conn 为 null 表示服务器级错误（端口被占用、selector 关闭失败等），这是常态而不是异常情况。
    //同样在 selector 线程上，异常不能外泄
    @Override
    public void onError(WebSocket conn, Exception exception) {
        try {
            if (conn != null) {
                //单条连接出错由库自己负责关掉那条连接，服务端本身还活着
                host.getLogger().log(Level.WARNING, "WebSocket 连接出错: " + describe(conn), exception);
                return;
            }
            //正常关闭时 selector.close() / server.close() 的 IO 错误也会以 conn == null 传进来，
            //这种是我们自己触发的，不是故障
            if (stopping) {
                host.getLogger().log(Level.FINE, "WebSocket 服务端关闭过程中的错误", exception);
                return;
            }
            if (started) {
                //启动成功之后又冒出的服务端级错误，说明 selector 线程已经退出来了，服务端不会再恢复
                host.getLogger().log(Level.SEVERE, "WebSocket 服务端已停止运行", exception);
            } else {
                //没走到 onStart() 说明端口压根没绑上
                host.getLogger().log(Level.SEVERE, "WebSocket 服务端启动失败", exception);
            }
            //两种情况插件都没法再完成绑定了，交给插件自己禁用
            host.onWebsocketFatal(exception);
        } catch (Throwable t) {
            host.getLogger().log(Level.SEVERE, "处理 WebSocket 错误回调时发生异常", t);
        }
    }

    //主动关闭。先置 stopping 再 stop()，这样关闭过程里冒出来的
    //conn == null 错误会被识别成自找的、而不是故障去禁用插件
    public void shutdown() {
        stopping = true;
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    //解析一次请求，按 action 分发。缺省 action 当作 bind，兼容只发 code/kookId 的旧客户端
    private void handleMessage(WebSocket conn, String message) {
        JsonObject json;
        try {
            json = gson.fromJson(message, JsonObject.class);
        } catch (JsonParseException e) {
            fail(conn, "BAD_JSON", "消息不是合法的 JSON");
            return;
        }
        if (json == null) {
            fail(conn, "BAD_JSON", "消息不是合法的 JSON 对象");
            return;
        }
        //action 缺省和 action 非法要分开：前者是旧客户端，后者是真的写错了
        String action = null;
        if (json.has(FIELD_ACTION)) {
            action = readText(json, FIELD_ACTION, MAX_ACTION_LENGTH);
            if (action == null) {
                fail(conn, "BAD_ACTION", "action 为空或超长");
                return;
            }
        }
        String normalized = action == null ? ACTION_BIND : action.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case ACTION_BIND -> handleBind(conn, json);
            case ACTION_QUERY -> handleQuery(conn, json);
            case ACTION_HISTORY -> handleHistory(conn, json);
            default -> fail(conn, "UNKNOWN_ACTION", "未知的操作: " + action);
        }
    }

    //用验证码换绑定
    private void handleBind(WebSocket conn, JsonObject json) {
        String code = readText(json, FIELD_CODE, MAX_CODE_LENGTH);
        String kookId = readText(json, FIELD_KOOK_ID, MAX_KOOK_ID_LENGTH);
        if (code == null || kookId == null) {
            fail(conn, "MISSING_FIELD", "code 或 kookId 缺失、为空或超长");
            return;
        }
        //「校验 + 写入」在数据库层是一次原子操作，并发提交同一个验证码时只会有一个成功
        Database.BindOutcome outcome = database.tryBind(code, kookId, ttlMillis);
        if (outcome.success()) {
            //注意：日志里不要输出验证码本身
            host.getLogger().info("绑定成功: " + outcome.playerName()
                    + " (" + outcome.uuid() + ") <-> KOOK " + kookId);
            sendResult(conn, true, "OK", "绑定成功", outcome.uuid(), outcome.playerName());
            return;
        }
        switch (outcome.status()) {
            case CODE_NOT_FOUND -> fail(conn, "CODE_NOT_FOUND", "验证码不存在或已被使用");
            case CODE_EXPIRED -> fail(conn, "CODE_EXPIRED", "验证码已过期，请在游戏内重新获取");
            case PLAYER_ALREADY_BOUND -> fail(conn, "PLAYER_ALREADY_BOUND", "该玩家已经绑定过 KOOK 账号");
            case KOOK_ALREADY_BOUND -> fail(conn, "KOOK_ALREADY_BOUND", "该 KOOK 账号已经绑定过其他玩家");
            case AMBIGUOUS_CODE -> fail(conn, "AMBIGUOUS_CODE", "验证码冲突，请在游戏内重新获取");
            case SUCCESS, DATABASE_ERROR -> fail(conn, "INTERNAL_ERROR", "服务器内部错误");
        }
    }

    //查询「这个 KOOK 账号当前绑的是谁」
    private void handleQuery(WebSocket conn, JsonObject json) {
        String kookId = readText(json, FIELD_KOOK_ID, MAX_KOOK_ID_LENGTH);
        if (kookId == null) {
            fail(conn, "MISSING_FIELD", "kookId 缺失、为空或超长");
            return;
        }
        Database.Binding binding = database.getBindingByKookId(kookId);
        JsonObject result;
        if (binding == null) {
            //「没绑定」是正常结果，不能走 fail()：那会计一次失败，
            //玩家多点两次「查绑定」连接就被踢了
            result = baseResult(true, "NOT_BOUND", "该 KOOK 账号还没有绑定游戏账号");
            result.addProperty("bound", false);
            sendJson(conn, result);
            return;
        }
        result = baseResult(true, "BOUND", "已绑定：" + binding.playerName());
        result.addProperty("bound", true);
        result.addProperty("uuid", binding.uuid());
        result.addProperty("playerName", binding.playerName());
        result.addProperty("boundAt", binding.boundAt());
        sendJson(conn, result);
    }

    //查询历史绑定（解绑之后归档的记录，最新在前）
    private void handleHistory(WebSocket conn, JsonObject json) {
        String kookId = readText(json, FIELD_KOOK_ID, MAX_KOOK_ID_LENGTH);
        if (kookId == null) {
            fail(conn, "MISSING_FIELD", "kookId 缺失、为空或超长");
            return;
        }
        Database.Binding current = database.getBindingByKookId(kookId);
        List<Database.BindingHistory> rows = database.getHistoryByKookId(kookId, MAX_HISTORY_RECORDS);

        JsonObject result = baseResult(true, "OK",
                rows.isEmpty() ? "没有历史绑定记录" : "历史绑定记录 " + rows.size() + " 条");
        result.addProperty("bound", current != null);
        if (current != null) {
            result.addProperty("uuid", current.uuid());
            result.addProperty("playerName", current.playerName());
            result.addProperty("boundAt", current.boundAt());
        }
        JsonArray records = new JsonArray();
        for (Database.BindingHistory row : rows) {
            JsonObject item = new JsonObject();
            item.addProperty("uuid", row.uuid());
            item.addProperty("playerName", row.playerName());
            item.addProperty("boundAt", row.boundAt());
            item.addProperty("unboundAt", row.unboundAt());
            if (row.unboundBy() != null) {
                item.addProperty("unboundBy", row.unboundBy());
            }
            records.add(item);
        }
        result.add("records", records);
        sendJson(conn, result);
    }

    //回一条失败结果，并累计失败次数；超过阈值就断开连接
    private void fail(WebSocket conn, String reason, String message) {
        sendResult(conn, false, reason, message, null, null);
        if (counterOf(conn).incrementAndGet() >= maxFailures) {
            host.getLogger().warning("连接失败次数过多，主动断开: " + describe(conn));
            conn.close(1008, "too many failed attempts");
        }
    }

    //取当前连接的失败计数；attachment 还没设置时懒初始化（onOpen 不保证先于本方法执行）
    private AtomicInteger counterOf(WebSocket conn) {
        AtomicInteger counter = conn.getAttachment();
        if (counter == null) {
            counter = new AtomicInteger();
            conn.setAttachment(counter);
        }
        return counter;
    }

    //绑定结果（bind 的成败回包），保持原来的字段不变
    private void sendResult(WebSocket conn, boolean success, String reason, String message,
                            UUID uuid, String playerName) {
        JsonObject json = baseResult(success, reason, message);
        if (uuid != null) {
            json.addProperty("uuid", uuid.toString());
        }
        if (playerName != null) {
            json.addProperty("playerName", playerName);
        }
        sendJson(conn, json);
    }

    //统一回包的三件套。Python 端按 success 判断成败，
    //reason 用于程序分支，message 可以直接转发给 KOOK 用户
    private JsonObject baseResult(boolean success, String reason, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("success", success);
        json.addProperty("reason", reason);
        json.addProperty("message", message);
        return json;
    }

    private void sendJson(WebSocket conn, JsonObject json) {
        if (conn == null || !conn.isOpen()) {
            return;
        }
        try {
            conn.send(gson.toJson(json));
        } catch (RuntimeException e) {
            //连接已经断开（WebsocketNotConnectedException 等），忽略即可
            host.getLogger().log(Level.FINE, "回包发送失败，连接可能已关闭", e);
        }
    }

    //读取一个字符串字段：缺失、不是基本类型、为空或超长都返回 null
    private String readText(JsonObject json, String key, int maxLength) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        String value = element.getAsString().trim();
        if (value.isEmpty() || value.length() > maxLength) {
            return null;
        }
        return value;
    }

    //连接可能已经关闭，取地址时兜底，避免日志本身再抛异常
    private String describe(WebSocket conn) {
        try {
            InetSocketAddress remote = conn.getRemoteSocketAddress();
            return remote == null ? "unknown" : remote.toString();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
