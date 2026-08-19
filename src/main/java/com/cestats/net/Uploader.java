package com.cestats.net;

import com.cestats.config.CeStatsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Consumer;

/**
 * Ships archived matches to {@code /api/ingest} on a background thread.
 *
 * <p>The queue is disk-backed ({@link MatchStore}), so nothing is lost if the site is down, the
 * game crashes, or the player quits mid-retry. Retries back off and never give up on transient
 * failures; malformed payloads and revoked credentials are treated as permanent for the current
 * queue pass and remain in the local archive for a later manual retry.
 */
public final class Uploader {

    private static final Logger LOG = LoggerFactory.getLogger("cestats");
    private static final long[] BACKOFF_MS = {5_000L, 15_000L, 60_000L, 300_000L, 900_000L};

    public record Result(String matchId, boolean ok, String message, String url) {
    }

    private final CeStatsConfig config;
    private final MatchStore store;
    private final Consumer<Result> onResult;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final LinkedBlockingDeque<String> queue = new LinkedBlockingDeque<>();

    private volatile boolean running;
    private Thread worker;

    public Uploader(CeStatsConfig config, MatchStore store, Consumer<Result> onResult) {
        this.config = config;
        this.store = store;
        this.onResult = onResult;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::run, "cestats-uploader");
        worker.setDaemon(true);
        worker.start();
        requeuePending();
    }

    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    public void enqueue(String matchId) {
        queue.offerLast(matchId);
    }

    /** Re-arms everything that was still marked pending from a previous session. */
    public int requeuePending() {
        int n = 0;
        for (String id : store.pendingIds()) {
            if (!queue.contains(id)) {
                queue.offerLast(id);
                n++;
            }
        }
        return n;
    }

    public int queueSize() {
        return queue.size();
    }

    private void run() {
        while (running) {
            String matchId;
            try {
                matchId = queue.takeFirst();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (!config.enabled || !config.uploadEnabled || !config.isPaired()) {
                queue.offerFirst(matchId);
                sleep(30_000L);
                continue;
            }

            String json = store.read(matchId);
            if (json == null) {
                LOG.warn("[cestats] 待传比赛 {} 的存档丢失，跳过", matchId);
                store.markUploaded(matchId);
                continue;
            }

            int attempt = 0;
            while (running) {
                Outcome outcome = post(matchId, json);
                if (outcome.done()) {
                    break;
                }
                sleep(BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)]);
                attempt++;
            }
        }
    }

    private record Outcome(boolean done) {
    }

    private Outcome post(String matchId, String json) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.ingestUrl()))
                    .timeout(Duration.ofSeconds(20))
                    .header("content-type", "application/json")
                    .header("accept", "application/json")
                    .header("user-agent", "cestats/0.2 (Minecraft client)")
                    .header("authorization", "Bearer " + config.deviceToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();

            if (code >= 200 && code < 300) {
                store.markUploaded(matchId);
                onResult.accept(new Result(matchId, true, "已上传", config.matchUrl(matchId)));
                return new Outcome(true);
            }

            if (code == 400) {
                // The server could not parse what we sent; retrying will never help.
                LOG.error("[cestats] 服务端拒绝了比赛 {}（400）：{}", matchId, response.body());
                store.markUploaded(matchId);
                onResult.accept(new Result(matchId, false, "服务端拒绝（数据格式错误）", null));
                return new Outcome(true);
            }

            LOG.warn("[cestats] 上传 {} 失败：HTTP {}，稍后重试", matchId, code);
            if (code == 401 || code == 403) {
                // Leave the archive pending, but stop hammering a revoked/unpaired token. The pair
                // command requeues pending archives after a new token is stored.
                onResult.accept(new Result(matchId, false, "设备令牌无效，请执行 /cestats pair <配对码>", null));
                return new Outcome(true);
            }
            return new Outcome(false);
        } catch (Exception e) {
            LOG.warn("[cestats] 上传 {} 出错：{}，稍后重试", matchId, e.toString());
            return new Outcome(false);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
