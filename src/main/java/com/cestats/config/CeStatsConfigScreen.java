package com.cestats.config;

import com.cestats.CeStatsClient;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * The Cloth Config screen Mod Menu opens.
 *
 * <p>Cloth Config is bundled inside the mod jar, so this always works; Mod Menu itself is
 * optional and simply never invokes the entrypoint when it is absent.
 */
public final class CeStatsConfigScreen {

    private CeStatsConfigScreen() {
    }

    public static Screen create(Screen parent) {
        CeStatsConfig config = CeStatsClient.config();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("CE Stats"))
                .setSavingRunnable(() -> {
                    config.save();
                    // A corrected key or URL should immediately retry whatever is still queued.
                    if (CeStatsClient.uploader() != null) {
                        CeStatsClient.uploader().requeuePending();
                    }
                });

        ConfigEntryBuilder entries = builder.entryBuilder();

        ConfigCategory general = builder.getOrCreateCategory(Text.literal("常规"));
        general.addEntry(entries.startBooleanToggle(Text.literal("启用统计"), config.enabled)
                .setDefaultValue(true)
                .setTooltip(Text.literal("关闭后不再解析聊天，也不会记录任何比赛"))
                .setSaveConsumer(value -> config.enabled = value)
                .build());
        general.addEntry(entries.startBooleanToggle(Text.literal("自动上传"), config.uploadEnabled)
                .setDefaultValue(true)
                .setTooltip(Text.literal("关闭后比赛仍会存到本地，恢复后用 /cestats retry 补传"))
                .setSaveConsumer(value -> config.uploadEnabled = value)
                .build());
        general.addEntry(entries.startBooleanToggle(Text.literal("赛后聊天提示"), config.notifyOnMatchEnd)
                .setDefaultValue(true)
                .setTooltip(Text.literal("比赛结算后在聊天框显示本场数据和查看链接"))
                .setSaveConsumer(value -> config.notifyOnMatchEnd = value)
                .build());

        ConfigCategory server = builder.getOrCreateCategory(Text.literal("服务器"));
        server.addEntry(entries.startStrField(Text.literal("接口地址"), config.apiBaseUrl)
                .setDefaultValue("http://127.0.0.1:3100")
                .setTooltip(Text.literal("统计站点的根地址，比赛会 POST 到 <地址>/api/ingest"))
                .setSaveConsumer(value -> config.apiBaseUrl = value.trim())
                .build());
        server.addEntry(entries.startStrField(Text.literal("网页地址"), config.webBaseUrl)
                .setDefaultValue("http://127.0.0.1:3100")
                .setTooltip(Text.literal("聊天里「查看详情」打开的地址，通常和接口地址相同"))
                .setSaveConsumer(value -> config.webBaseUrl = value.trim())
                .build());
        server.addEntry(entries.startStrField(Text.literal("API Key"), config.apiKey)
                .setDefaultValue("dev-key")
                .setTooltip(Text.literal("需与站点的 CESTATS_API_KEY 一致，否则上传会被拒绝"))
                .setSaveConsumer(value -> config.apiKey = value.trim())
                .build());

        return builder.build();
    }
}
