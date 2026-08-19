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

/** Exchanges a one-time server-issued pairing code for a per-installation bearer token. */
public final class PairingClient {

    private static final Logger LOG = LoggerFactory.getLogger("cestats");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record Result(boolean ok, String message) {
    }

    private PairingClient() {
    }

    public static void pair(CeStatsConfig config, String code, String player, Consumer<Result> callback) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code.trim());
        body.addProperty("player", player);
        body.addProperty("installId", config.installId);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(config.pairUrl()))
                    .timeout(Duration.ofSeconds(20))
                    .header("content-type", "application/json")
                    .header("accept", "application/json")
                    .header("user-agent", "cestats/0.2 (Minecraft client)")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            callback.accept(new Result(false, "接口地址无效"));
            return;
        }

        Thread thread = new Thread(() -> {
            Result result;
            try {
                HttpResponse<String> response = HTTP.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    result = new Result(false, response.statusCode() == 403
                            ? "配对码无效、已过期或不属于当前玩家"
                            : "配对失败 HTTP " + response.statusCode());
                } else {
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    String token = root.get("deviceToken").getAsString();
                    String pairedPlayer = root.get("player").getAsString();
                    if (token.isBlank() || !player.equals(pairedPlayer)) {
                        result = new Result(false, "配对响应无效");
                    } else {
                        config.deviceToken = token;
                        config.pairedPlayer = pairedPlayer;
                        config.save();
                        result = new Result(true, "已配对玩家 " + pairedPlayer);
                    }
                }
            } catch (Exception e) {
                LOG.debug("[cestats] 配对请求失败: {}", e.toString());
                result = new Result(false, "配对请求失败，请检查站点地址和网络");
            }
            callback.accept(result);
        }, "cestats-pairing");
        thread.setDaemon(true);
        thread.start();
    }
}
