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

/** Plain JSON config at {@code .minecraft/config/cestats.json}. */
public final class CeStatsConfig {

    private static final Logger LOG = LoggerFactory.getLogger("cestats");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String DEFAULT_BASE_URL = "https://ce.qingyun.best";
    public static final String DEFAULT_API_KEY = "903f0d31c36e2f561e33376c7c37af98f841df1e1184b9d2";

    public boolean enabled = true;
    public boolean uploadEnabled = true;
    public boolean notifyOnMatchEnd = true;
    /** Automatically start/finish a Flashback replay around parsed matches when Flashback exists. */
    public boolean flashbackAutoRecord = false;

    /** Where /api/ingest lives. */
    public String apiBaseUrl = DEFAULT_BASE_URL;
    public String apiKey = DEFAULT_API_KEY;
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
