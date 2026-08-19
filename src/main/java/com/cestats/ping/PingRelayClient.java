package com.cestats.ping;

import com.cestats.config.CeStatsConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Background HTTP client for the ephemeral team ping relay. */
public final class PingRelayClient {

    private static final Logger LOG = LoggerFactory.getLogger("cestats");
    private static final long IDENTITY_CHECK_MS = 500L;
    private static final long RETRY_MS = 2_000L;
    private static final long POLL_WAIT_MS = 10_000L;

    private final CeStatsConfig config;
    private final DebugSink debugSink;
    private final PingIdentity identity = new PingIdentity();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicReference<Snapshot> pending = new AtomicReference<>();
    private final Object publicationLock = new Object();
    private final ArrayDeque<Publication> pendingPublications = new ArrayDeque<>();
    private CompletableFuture<Void> publicationTail = CompletableFuture.completedFuture(null);

    private volatile boolean running;
    private volatile boolean joined;
    private volatile boolean joining;
    private volatile boolean identityResolved;
    private volatile String mode;
    private volatile String lastError;
    private volatile String contextSignature;
    private volatile String channel;
    private volatile String token;
    private volatile long revision;
    private volatile long nextIdentityCheck;
    private volatile Thread poller;
    private volatile String lastRepeatedDebug;

    public PingRelayClient(CeStatsConfig config) {
        this(config, null);
    }

    public PingRelayClient(CeStatsConfig config, DebugSink debugSink) {
        this.config = config;
        this.debugSink = debugSink;
    }

    public void start() {
        running = true;
    }

    public void stop() {
        running = false;
        reset();
    }

    public void setContext(String server, String player) {
        identity.setContext(server, player);
        reset();
    }

    public void accept(com.cestats.parse.ChatEvent event) {
        identity.accept(event);
    }

    public void acceptChatText(String content) {
        identity.acceptChatText(content);
    }

    public void tick(MinecraftClient client) {
        if (!running || !config.enabled || !config.pingEnabled || !config.isPaired()) {
            if (joined || joining) reset();
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextIdentityCheck) {
            return;
        }
        nextIdentityCheck = now + IDENTITY_CHECK_MS;

        PingIdentity.Identity desired = config.pingAutoJoin
                ? identity.resolve(client, config.pingTeamCode)
                : identity.manual(client, config.pingTeamCode);
        identityResolved = desired != null;
        if (desired == null) {
            if (joined || joining) reset();
            return;
        }
        mode = desired.mode();

        String desiredSignature = desired.mode() + "|" + desired.channel() + "|" + desired.dimension();
        if (desiredSignature.equals(contextSignature) && (joined || joining)) {
            return;
        }
        contextSignature = desiredSignature;
        join(desired);
    }

    public Snapshot consumeSnapshot() {
        return pending.getAndSet(null);
    }

    public String ownerId() {
        return identity.ownerId();
    }

    public boolean isJoined() {
        return joined;
    }

    public String channel() {
        return channel;
    }

    /** Everything {@code /cestats ping} needs to tell "working" apart from "silently doing nothing". */
    public Status status() {
        Phase phase;
        if (!running || !config.enabled || !config.pingEnabled) {
            phase = Phase.DISABLED;
        } else if (!config.isPaired()) {
            phase = Phase.UNPAIRED;
        } else if (joined) {
            phase = Phase.JOINED;
        } else if (joining) {
            phase = Phase.JOINING;
        } else if (!identityResolved) {
            phase = Phase.NO_IDENTITY;
        } else {
            phase = Phase.RETRYING;
        }
        return new Status(phase, mode, channel, lastError);
    }

    public void publish(String id, String kind, double x, double y, double z, String dimension) {
        if (!running) return;
        Publication publication = new Publication(id, kind, quantize(x), quantize(y), quantize(z),
                dimension, contextSignature);
        String activeChannel = channel;
        String activeToken = token;
        if (!joined || activeChannel == null || activeToken == null || publication.signature() == null) {
            synchronized (publicationLock) {
                while (pendingPublications.size() >= 8) pendingPublications.removeFirst();
                pendingPublications.addLast(publication);
            }
            debug("标点 " + shortId(id) + " 只在本地：" + whyNotJoined(), false);
            return;
        }
        enqueuePublication(publication, activeChannel, activeToken);
    }

    /** Short reason a publish could not go out yet, phrased for the chat debug line. */
    private String whyNotJoined() {
        return switch (status().phase()) {
            case DISABLED -> "队伍标点同步已关闭";
            case UNPAIRED -> "设备未配对（/cestats bind）";
            case NO_IDENTITY -> "还没识别到队伍频道（可用 /cestats ping code）";
            case JOINING -> "正在加入频道，已排队等待重发";
            case RETRYING -> "尚未加入频道，已排队等待重发";
            case JOINED -> "频道状态刚刚变化，已排队等待重发";
        };
    }

    public void reset() {
        epoch.incrementAndGet();
        joined = false;
        joining = false;
        channel = null;
        token = null;
        revision = 0L;
        contextSignature = null;
        lastError = null;
        identityResolved = false;
        mode = null;
        pending.set(null);
        synchronized (publicationLock) {
            pendingPublications.clear();
        }
        Thread old = poller;
        poller = null;
        if (old != null) {
            old.interrupt();
        }
    }

    private void join(PingIdentity.Identity desired) {
        long currentEpoch = epoch.incrementAndGet();
        joined = false;
        joining = true;
        channel = null;
        token = null;
        revision = 0L;
        pending.set(null);

        JsonObject body = new JsonObject();
        body.addProperty("mode", desired.mode());
        body.addProperty("matchKey", desired.matchKey());
        body.addProperty("teamKey", desired.teamKey());
        body.addProperty("player", identity.ownerId());

        HttpRequest request = baseRequest(config.pingJoinUrl())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenAccept(response -> {
                    if (currentEpoch != epoch.get() || !running) return;
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        joining = false;
                        contextSignature = null;
                        lastError = describeFailure("加入频道", response.statusCode(), response.body());
                        debug(lastError, false);
                        LOG.debug("[cestats] 标点中继加入失败 HTTP {}: {}",
                                response.statusCode(), response.body());
                        return;
                    }
                    try {
                        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                        channel = root.get("channel").getAsString();
                        token = root.get("token").getAsString();
                        revision = root.get("revision").getAsLong();
                        joining = false;
                        joined = true;
                        lastError = null;
                        pending.set(parseSnapshot(root));
                        debug("已加入标点频道 " + shortChannel(channel) + "（" + desired.mode() + "）", true);
                        flushPendingPublications(contextSignature, channel, token);
                        startPoller(currentEpoch, channel);
                        LOG.debug("[cestats] 已加入标点频道 {}", channel);
                    } catch (RuntimeException e) {
                        joining = false;
                        contextSignature = null;
                        lastError = "加入响应无效：" + e;
                        debug(lastError, false);
                        LOG.debug("[cestats] 标点中继加入响应无效: {}", e.toString());
                    }
                })
                .exceptionally(error -> {
                    joining = false;
                    contextSignature = null;
                    lastError = "无法连接站点：" + rootCause(error);
                    debug(lastError, false);
                    LOG.debug("[cestats] 标点中继加入出错: {}", error.toString());
                    return null;
                });
    }

    private void startPoller(long currentEpoch, String joinedChannel) {
        Thread old = poller;
        if (old != null) old.interrupt();
        Thread thread = new Thread(() -> pollLoop(currentEpoch, joinedChannel), "cestats-ping-poll");
        thread.setDaemon(true);
        poller = thread;
        thread.start();
    }

    private void pollLoop(long currentEpoch, String joinedChannel) {
        while (running && currentEpoch == epoch.get() && joined
                && joinedChannel.equals(channel)) {
            try {
                HttpRequest request = baseRequest(config.pingStateUrl(joinedChannel, token, revision, POLL_WAIT_MS))
                        .GET().build();
                HttpResponse<String> response = http.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (currentEpoch != epoch.get()) return;
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    lastError = describeFailure("轮询", response.statusCode(), response.body());
                    debugRepeating(lastError);
                    LOG.debug("[cestats] 标点中继轮询失败 HTTP {}", response.statusCode());
                    break;
                }
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                revision = root.get("revision").getAsLong();
                pending.set(parseSnapshot(root));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                lastError = "轮询出错：" + rootCause(e);
                debugRepeating(lastError);
                LOG.debug("[cestats] 标点中继轮询出错: {}", e.toString());
                sleep(RETRY_MS);
            }
        }
        if (currentEpoch == epoch.get()) {
            joined = false;
            contextSignature = null;
        }
    }

    private void flushPendingPublications(String signature, String activeChannel, String activeToken) {
        List<Publication> queued = new ArrayList<>();
        synchronized (publicationLock) {
            while (!pendingPublications.isEmpty()) {
                Publication publication = pendingPublications.removeFirst();
                if (Objects.equals(publication.signature(), signature)) {
                    queued.add(publication);
                } else {
                    debug("标点 " + shortId(publication.id()) + " 已丢弃：频道已变化", false);
                }
            }
        }
        for (Publication publication : queued) {
            enqueuePublication(publication, activeChannel, activeToken);
        }
    }

    private void enqueuePublication(Publication publication, String activeChannel, String activeToken) {
        synchronized (publicationLock) {
            publicationTail = publicationTail.handle((ignored, error) -> null)
                    .thenCompose(ignored -> sendPublication(publication, activeChannel, activeToken));
        }
    }

    private CompletableFuture<Void> sendPublication(Publication publication, String activeChannel,
                                                    String activeToken) {
        String label = "标点 " + shortId(publication.id())
                + ("warning".equals(publication.kind()) ? "（警告）" : "");
        if (!running || !joined || !Objects.equals(publication.signature(), contextSignature)
                || !Objects.equals(activeChannel, channel) || !Objects.equals(activeToken, token)) {
            debug(label + " 已丢弃：频道已变化", false);
            return CompletableFuture.completedFuture(null);
        }
        JsonObject body = new JsonObject();
        body.addProperty("channel", activeChannel);
        body.addProperty("token", activeToken);
        body.addProperty("id", publication.id());
        body.addProperty("owner", identity.ownerId());
        body.addProperty("kind", publication.kind());
        body.addProperty("x", publication.x());
        body.addProperty("y", publication.y());
        body.addProperty("z", publication.z());
        body.addProperty("dimension", publication.dimension());

        HttpRequest request = baseRequest(config.pingPublishUrl())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenAccept(response -> {
                    if (response.statusCode() >= 300) {
                        lastError = describeFailure(label + " 未同步", response.statusCode(),
                                response.body());
                        debug(lastError, false);
                        LOG.debug("[cestats] 标点中继发布失败 HTTP {}: {}",
                                response.statusCode(), response.body());
                    } else {
                        lastError = null;
                        debug(label + " 已同步到频道 " + shortChannel(activeChannel), true);
                    }
                })
                .exceptionally(error -> {
                    lastError = label + " 发送出错：" + rootCause(error);
                    debug(lastError, false);
                    LOG.debug("[cestats] 标点中继发布出错: {}", error.toString());
                    return null;
                });
    }

    private Snapshot parseSnapshot(JsonObject root) {
        String snapshotChannel = root.get("channel").getAsString();
        long snapshotRevision = root.get("revision").getAsLong();
        long skew = clockSkew(root);
        List<Marker> markers = new ArrayList<>();
        JsonArray array = root.getAsJsonArray("markers");
        if (array != null) {
            for (JsonElement element : array) {
                JsonObject marker = element.getAsJsonObject();
                markers.add(new Marker(
                        marker.get("id").getAsString(),
                        marker.get("owner").getAsString(),
                        marker.get("kind").getAsString(),
                        marker.get("x").getAsDouble(),
                        marker.get("y").getAsDouble(),
                        marker.get("z").getAsDouble(),
                        marker.get("dimension").getAsString(),
                        marker.get("createdAt").getAsLong() + skew,
                        marker.get("expiresAt").getAsLong() + skew));
            }
        }
        return new Snapshot(snapshotChannel, snapshotRevision, markers);
    }

    /**
     * Difference between this machine's clock and the relay's, so {@code relayTime + skew} is a local
     * timestamp. Marker lifetimes are only a few seconds, so comparing a relay timestamp against the
     * local clock directly would drop every teammate marker on a client whose clock runs ahead of the
     * site. Returns 0 for a relay that does not report its clock.
     */
    private static long clockSkew(JsonObject root) {
        JsonElement now = root.get("now");
        if (now == null || now.isJsonNull()) {
            return 0L;
        }
        try {
            return System.currentTimeMillis() - now.getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .header("user-agent", "cestats/0.2 (Minecraft client)")
                .header("authorization", "Bearer " + config.deviceToken);
    }

    private static double quantize(double value) {
        return Math.round(value * 20.0) / 20.0;
    }

    /** Emits one debug line per event when {@code pingDebug} is on. */
    private void debug(String message, boolean ok) {
        lastRepeatedDebug = null;
        if (config.pingDebug && debugSink != null && message != null) {
            debugSink.log(message, ok);
        }
    }

    /**
     * Same, but collapses an identical consecutive message. The poll loop retries every two seconds,
     * and a site that stays down should not scroll the chat away.
     */
    private void debugRepeating(String message) {
        if (message == null || message.equals(lastRepeatedDebug)) {
            return;
        }
        lastRepeatedDebug = message;
        if (config.pingDebug && debugSink != null) {
            debugSink.log(message, false);
        }
    }

    /** Trailing counter of a marker id, so debug lines can be matched to individual clicks. */
    private static String shortId(String id) {
        int dash = id == null ? -1 : id.lastIndexOf('-');
        return dash < 0 || dash + 1 >= id.length() ? "#?" : "#" + id.substring(dash + 1);
    }

    /** First eight characters of a channel id — enough for teammates to compare, short enough for chat. */
    private static String shortChannel(String value) {
        return value == null ? "?" : value.substring(0, Math.min(8, value.length()));
    }

    /** Turns a relay rejection into something a player can act on, not just an HTTP number. */
    private static String describeFailure(String action, int status, String body) {
        String code = errorCode(body);
        String hint = switch (code == null ? "" : code) {
            case "unauthorized" -> "设备令牌无效或已被撤销，重新执行 /cestats bind";
            case "player_mismatch" -> "当前游戏名与配对时的账号不一致";
            case "invalid_request" -> "请求被站点拒绝（格式不符）";
            case "cooldown" -> "服务端仍在冷却";
            case "limit" -> "已达到本人标点上限";
            case "channel-limit" -> "该频道的标点已满";
            case "unknown-marker" -> "要升级的标点在服务端已不存在";
            case "internal_error" -> "站点内部错误";
            default -> code != null ? code : shorten(body);
        };
        return action + " HTTP " + status + (hint.isEmpty() ? "" : "：" + hint);
    }

    private static String errorCode(String body) {
        if (body == null || !body.trim().startsWith("{")) {
            return null;
        }
        try {
            JsonElement error = JsonParser.parseString(body).getAsJsonObject().get("error");
            return error == null || error.isJsonNull() ? null : error.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : shorten(message);
    }

    private static String shorten(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 96 ? trimmed : trimmed.substring(0, 96) + "…";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Publication(String id, String kind, double x, double y, double z,
                               String dimension, String signature) {
    }

    /** Where per-event debug lines go. {@code ok} distinguishes a confirmed sync from a failure. */
    public interface DebugSink {
        void log(String message, boolean ok);
    }

    /** Why the relay is or is not currently syncing. */
    public enum Phase {
        DISABLED,
        UNPAIRED,
        NO_IDENTITY,
        JOINING,
        JOINED,
        RETRYING
    }

    public record Status(Phase phase, String mode, String channel, String lastError) {
    }

    public record Marker(String id, String owner, String kind, double x, double y, double z,
                         String dimension, long createdAt, long expiresAt) {
        // createdAt/expiresAt are local-clock timestamps: PingRelayClient rebases the relay's values
        // on arrival, so callers compare them against System.currentTimeMillis() directly.
    }

    public record Snapshot(String channel, long revision, List<Marker> markers) {
    }
}
