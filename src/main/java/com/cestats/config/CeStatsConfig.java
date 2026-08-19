package com.cestats.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Plain JSON config at {@code .minecraft/config/cestats.json}. */
public final class CeStatsConfig {

    private static final Logger LOG = LoggerFactory.getLogger("cestats");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String DEFAULT_BASE_URL = "https://ce.qingyun.best";

    public boolean enabled = true;
    public boolean uploadEnabled = true;
    public boolean notifyOnMatchEnd = true;
    /** Enable the optional client-to-site team ping relay. */
    public boolean pingEnabled = true;
    /** Automatically use the visible roster and first observed side for relay channel discovery. */
    public boolean pingAutoJoin = true;
    /** Print one chat line per ping showing whether the relay accepted it. Off by default. */
    public boolean pingDebug = false;
    /** Volatile team code; kept in memory by commands and not written to disk. */
    public transient String pingTeamCode;
    /** Automatically start/finish a Flashback replay around parsed matches when Flashback exists. */
    public boolean flashbackAutoRecord = false;

    /** Where /api/ingest and /api/pair live. */
    public String apiBaseUrl = DEFAULT_BASE_URL;
    /** Random installation identifier used when redeeming a one-time pairing code. */
    public String installId = "";
    /** Per-installation token returned by /api/pair; never shipped as a default. */
    public String deviceToken = "";
    /** Player name bound to the current device token, for display and payload checks. */
    public String pairedPlayer = "";
    /** Where the human-facing site lives; usually the same host. */
    public String webBaseUrl = DEFAULT_BASE_URL;

    private transient Path path;

    public static CeStatsConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("cestats.json");
        CeStatsConfig config = new CeStatsConfig();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                CeStatsConfig loaded = GSON.fromJson(json, CeStatsConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (IOException | JsonSyntaxException e) {
                LOG.warn("[cestats] 配置读取失败，使用默认值: {}", e.toString());
            }
        }
        if (config.installId == null || config.installId.isBlank()) {
            config.installId = UUID.randomUUID().toString();
        }
        if (config.deviceToken == null) {
            config.deviceToken = "";
        }
        if (config.pairedPlayer == null) {
            config.pairedPlayer = "";
        }
        config.path = path;
        config.save();
        return config;
    }

    public void save() {
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("[cestats] 配置写入失败: {}", e.toString());
        }
    }

    public String ingestUrl() {
        return trimSlash(apiBaseUrl) + "/api/ingest";
    }

    public String pairUrl() {
        return trimSlash(apiBaseUrl) + "/api/pair";
    }

    /** Opens an in-game pairing request; the reply carries the short code the player reports. */
    public String pairRequestUrl() {
        return trimSlash(apiBaseUrl) + "/api/pair/request";
    }

    /** Polled with the local claim secret until somebody approves the code. */
    public String pairClaimUrl() {
        return trimSlash(apiBaseUrl) + "/api/pair/claim";
    }

    public boolean isPaired() {
        return deviceToken != null && !deviceToken.isBlank();
    }

    public void clearPairing() {
        deviceToken = "";
        pairedPlayer = "";
        save();
    }

    public String pingJoinUrl() {
        return trimSlash(apiBaseUrl) + "/api/ping/join";
    }

    public String pingPublishUrl() {
        return trimSlash(apiBaseUrl) + "/api/ping/publish";
    }

    public String pingStateUrl(String channel, String token, long revision, long waitMs) {
        return trimSlash(apiBaseUrl) + "/api/ping/state?channel=" + channel
                + "&token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8)
                + "&since=" + revision + "&wait=" + waitMs;
    }

    public String matchUrl(String matchId) {
        return trimSlash(webBaseUrl) + "/matches/" + matchId;
    }

    private static String trimSlash(String url) {
        String trimmed = url == null ? "" : url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
