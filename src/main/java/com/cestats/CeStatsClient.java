package com.cestats;

import com.cestats.config.CeStatsConfig;
import com.cestats.integration.FlashbackBridge;
import com.cestats.integration.MatchRecordingController;
import com.cestats.match.MatchTracker;
import com.cestats.model.MatchRecord;
import com.cestats.net.MatchJson;
import com.cestats.net.MatchStore;
import com.cestats.net.Uploader;
import com.cestats.ping.PingDemo;
import com.cestats.ui.ChatNotifier;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CeStatsClient implements ClientModInitializer {

    public static final String MOD_ID = "cestats";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    // serializeNulls is required: the API schema declares killer/weapon/bombSite as nullable,
    // and a *missing* key fails that check. Plain Gson would silently drop them.
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private static CeStatsConfig config;
    private static MatchStore store;
    private static Uploader uploader;
    private static MatchTracker tracker;
    private static MatchRecordingController recordingController;
    private static ChatNotifier notifier;
    private static String lastMatchId;

    @Override
    public void onInitializeClient() {
        config = CeStatsConfig.load();
        store = new MatchStore();
        notifier = new ChatNotifier(config);
        uploader = new Uploader(config, store, result ->
                notifier.uploadResult(result.matchId(), result.ok(), result.message()));
        recordingController = new MatchRecordingController(new FlashbackBridge(),
                config.enabled && config.flashbackAutoRecord);
        tracker = new MatchTracker(CeStatsClient::onMatchFinished, event -> {
            recordingController.accept(event);
            PingDemo.accept(event);
        });
        uploader.start();
        PingDemo.register(config);

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !config.enabled) {
                return;
            }
            try {
                PingDemo.acceptChatText(message.getString());
                tracker.accept(message.getString(), System.currentTimeMillis());
            } catch (Exception e) {
                LOG.error("[cestats] 解析聊天消息时出错", e);
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerInfo info = client.getCurrentServerEntry();
            String server = info != null ? info.address : "singleplayer";
            String user = client.getSession().getUsername();
            tracker.setContext(server, user);
            tracker.reset();
            PingDemo.setContext(server, user);
            LOG.info("[cestats] 已连接 {}，本地玩家 {}", server, user);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            recordingController.onDisconnect();
            tracker.reset();
            PingDemo.reset();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            recordingController.setEnabled(config.enabled && config.flashbackAutoRecord);
            recordingController.tick();
            tracker.tick(System.currentTimeMillis());
            notifier.flush(client);
        });

        CeStatsCommand.register();
        LOG.info("[cestats] 已启动，上报地址 {}", config.ingestUrl());
    }

    private static void onMatchFinished(MatchRecord match) {
        recordingController.onMatchFinished();
        lastMatchId = match.matchId();
        String json = GSON.toJson(MatchJson.toJson(match));
        store.store(match.matchId(), json);
        LOG.info("[cestats] 结算：{} 回合 {} 完整={} -> {}",
                match.roundsObserved(), match.winner(), match.complete(), match.matchId());

        notifier.matchFinished(match);
        if (config.uploadEnabled) {
            uploader.enqueue(match.matchId());
        }
    }

    public static CeStatsConfig config() {
        return config;
    }

    public static MatchStore store() {
        return store;
    }

    public static Uploader uploader() {
        return uploader;
    }

    public static ChatNotifier notifier() {
        return notifier;
    }

    public static MatchRecordingController recordingController() {
        return recordingController;
    }

    public static String lastMatchId() {
        return lastMatchId;
    }

    public static MinecraftClient client() {
        return MinecraftClient.getInstance();
    }
}
