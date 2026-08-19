package com.cestats.net;

import com.cestats.config.CeStatsConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Pairing driven from inside the game: ask the site for a short code, have a human approve it in
 * the QQ group, then collect the device token.
 *
 * <p>The two secrets involved are deliberately asymmetric. The <em>code</em> is meant to be read
 * out loud into a group chat and grants nothing; the <em>claim secret</em> never leaves this
 * process and is the only thing that can collect a token. That is what makes it safe for the code
 * to be public: whoever approves it completes the pairing on <em>this</em> machine, not theirs.
 *
 * <p>The claim secret is intentionally not written to {@code cestats.json} — a request expires in
 * 20 minutes, so surviving a game restart is not worth persisting a credential for. Restarting
 * mid-flow just means running {@code /cestats bind} again.
 */
public final class BindClient {

    private static final Logger LOG = LoggerFactory.getLogger("cestats");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final long POLL_INTERVAL_MS = 5_000L;

    /** One in-flight bind attempt at a time; a second /cestats bind supersedes the first. */
    private static volatile Thread poller;

    /** Progress reported back to the chat: either a code to relay, or a terminal outcome. */
    public record Opened(String code, String player, long expiresAt) {
    }

    public record Result(boolean ok, String message) {
    }

    private BindClient() {
    }

    /**
     * Opens a request and then polls until it is approved, expires, or {@link #cancel()} is called.
     *
     * @param onOpened called once with the code to report in the QQ group
     * @param onDone   called once with the terminal outcome
     */
    public static void bind(CeStatsConfig config, String player, String server,
                            Consumer<Opened> onOpened, Consumer<Result> onDone) {
        cancel();

        Thread thread = new Thread(() -> {
            Opening opening;
            try {
                opening = openRequest(config, player, server);
            } catch (Failure e) {
                onDone.accept(new Result(false, e.getMessage()));
                return;
            } catch (Exception e) {
                LOG.debug("[cestats] 绑定请求失败: {}", e.toString());
                onDone.accept(new Result(false, "连不上统计站点，请检查 /cestats url 和网络"));
                return;
            }

            if (poller != Thread.currentThread()) {
                return;
            }
            onOpened.accept(opening.opened());

            Result result = pollUntilApproved(config, opening);
            if (result != null) {
                onDone.accept(result);
            }
        }, "cestats-bind");
        thread.setDaemon(true);
        poller = thread;
        thread.start();
    }

    /** Stops the current attempt, if any. The request stays valid on the site until it expires. */
    public static void cancel() {
        Thread current = poller;
        poller = null;
        if (current != null) {
            current.interrupt();
        }
    }

    public static boolean inProgress() {
        Thread current = poller;
        return current != null && current.isAlive();
    }

    private static Opening openRequest(CeStatsConfig config, String player, String server)
            throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("player", player);
        body.addProperty("installId", config.installId);
        if (server != null && !server.isBlank()) {
            body.addProperty("server", server);
        }

        HttpResponse<String> response = send(config.pairRequestUrl(), body);
        int status = response.statusCode();
        if (status == 429) {
            throw new Failure("请求太频繁了，等几分钟再试");
        }
        if (status == 503) {
            throw new Failure("站点侧待批准的请求太多了，稍后再试");
        }
        if (status < 200 || status >= 300) {
            throw new Failure("站点拒绝了绑定请求 HTTP " + status);
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String code = root.get("code").getAsString();
        String claimSecret = root.get("claimSecret").getAsString();
        long expiresAt = root.has("expiresAt") ? root.get("expiresAt").getAsLong() : 0L;
        if (code.isBlank() || claimSecret.isBlank()) {
            throw new Failure("站点返回的绑定码无效");
        }

        // Held only for the lifetime of this attempt, never logged and never saved to disk.
        return new Opening(new Opened(code, player, expiresAt), claimSecret);
    }

    /** The claim secret rides along with the opened request instead of living in a static field. */
    private record Opening(Opened opened, String claimSecret) {
    }

    /**
     * Polls the claim endpoint until the request is approved, rejected, or expires.
     *
     * @return the terminal outcome, or {@code null} if this attempt was cancelled or superseded —
     *         whoever did that already said so, so there is nothing left to report
     */
    private static Result pollUntilApproved(CeStatsConfig config, Opening opening) {
        Opened opened = opening.opened();
        Thread self = Thread.currentThread();

        JsonObject body = new JsonObject();
        body.addProperty("claimSecret", opening.claimSecret());
        body.addProperty("installId", config.installId);

        while (poller == self && !self.isInterrupted()) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                return null;
            }
            if (poller != self) {
                return null;
            }

            HttpResponse<String> response;
            try {
                response = send(config.pairClaimUrl(), body);
            } catch (Exception e) {
                // A dropped poll is normal on a flaky connection; keep waiting until expiry.
                LOG.debug("[cestats] 轮询绑定状态失败: {}", e.toString());
                continue;
            }

            if (response.statusCode() == 404) {
                return new Result(false, "绑定请求已过期或被拒绝，请重新执行 /cestats bind");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                continue;
            }

            JsonObject root;
            try {
                root = JsonParser.parseString(response.body()).getAsJsonObject();
            } catch (Exception e) {
                continue;
            }

            String status = root.has("status") ? root.get("status").getAsString() : "";
            if (!"paired".equals(status)) {
                continue;
            }

            String token = root.get("deviceToken").getAsString();
            String pairedPlayer = root.get("player").getAsString();
            if (token.isBlank() || !opened.player().equals(pairedPlayer)) {
                return new Result(false, "配对响应无效");
            }

            poller = null;
            config.deviceToken = token;
            config.pairedPlayer = pairedPlayer;
            config.save();
            return new Result(true, "已配对玩家 " + pairedPlayer + "，之前失败的上传会自动重试");
        }
        return null;
    }

    private static HttpResponse<String> send(String url, JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .header("user-agent", "cestats/0.2 (Minecraft client)")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** A failure with wording already fit for the chat line. */
    private static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }
}
