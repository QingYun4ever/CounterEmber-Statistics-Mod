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
    private volatile String contextSignature;
    private volatile String channel;
    private volatile String token;
    private volatile long revision;
    private volatile long nextIdentityCheck;
    private volatile Thread poller;

    public PingRelayClient(CeStatsConfig config) {
        this.config = config;
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
        if (desired == null) {
            if (joined || joining) reset();
            return;
        }

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
            return;
        }
        enqueuePublication(publication, activeChannel, activeToken);
    }

    public void reset() {
        epoch.incrementAndGet();
        joined = false;
        joining = false;
        channel = null;
        token = null;
        revision = 0L;
        contextSignature = null;
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
                        pending.set(parseSnapshot(root));
                        flushPendingPublications(contextSignature, channel, token);
                        startPoller(currentEpoch, channel);
                        LOG.debug("[cestats] 已加入标点频道 {}", channel);
                    } catch (RuntimeException e) {
                        joining = false;
                        contextSignature = null;
                        LOG.debug("[cestats] 标点中继加入响应无效: {}", e.toString());
                    }
                })
                .exceptionally(error -> {
                    joining = false;
                    contextSignature = null;
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
                if (Objects.equals(publication.signature(), signature)) queued.add(publication);
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
        if (!running || !joined || !Objects.equals(publication.signature(), contextSignature)
                || !Objects.equals(activeChannel, channel) || !Objects.equals(activeToken, token)) {
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
                        LOG.debug("[cestats] 标点中继发布失败 HTTP {}: {}",
                                response.statusCode(), response.body());
                    }
                })
                .exceptionally(error -> {
                    LOG.debug("[cestats] 标点中继发布出错: {}", error.toString());
                    return null;
                });
    }

    private Snapshot parseSnapshot(JsonObject root) {
        String snapshotChannel = root.get("channel").getAsString();
        long snapshotRevision = root.get("revision").getAsLong();
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
                        marker.get("createdAt").getAsLong(),
                        marker.get("expiresAt").getAsLong()));
            }
        }
        return new Snapshot(snapshotChannel, snapshotRevision, markers);
    }

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + config.deviceToken);
    }

    private static double quantize(double value) {
        return Math.round(value * 20.0) / 20.0;
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

    public record Marker(String id, String owner, String kind, double x, double y, double z,
                         String dimension, long createdAt, long expiresAt) {
    }

    public record Snapshot(String channel, long revision, List<Marker> markers) {
    }
}
