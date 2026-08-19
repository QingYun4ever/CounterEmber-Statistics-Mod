package com.cestats.config;

import com.cestats.CeStatsClient;
import com.cestats.ping.PingDemo;
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
                    // A corrected URL or newly completed pairing should immediately retry queued data.
                    if (CeStatsClient.uploader() != null) {
                        CeStatsClient.uploader().requeuePending();
                    }
                    if (CeStatsClient.recordingController() != null) {
                        CeStatsClient.recordingController().setEnabled(
                                config.enabled && config.flashbackAutoRecord);
                    }
                    PingDemo.reset();
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
        general.addEntry(entries.startBooleanToggle(Text.literal("队伍标点同步"), config.pingEnabled)
                .setDefaultValue(true)
                .setTooltip(Text.literal("通过统计站点的短时中继，让同局同队的客户端看到标点；不写入比赛数据库"))
                .setSaveConsumer(value -> config.pingEnabled = value)
                .build());
        general.addEntry(entries.startBooleanToggle(Text.literal("自动识别标点频道"), config.pingAutoJoin)
                .setDefaultValue(true)
                .setTooltip(Text.literal("使用可见玩家列表和首次观测到的队伍；识别不到时可用 /cestats ping join 六位码"))
                .setSaveConsumer(value -> config.pingAutoJoin = value)
                .build());
        general.addEntry(entries.startBooleanToggle(Text.literal("Flashback 自动录制"), config.flashbackAutoRecord)
                .setDefaultValue(false)
                .setTooltip(Text.literal("安装 Flashback 后，按 CE Stats 识别的比赛边界自动开始和结束录制；需在 Flashback 中开启 Quick Save"))
                .setSaveConsumer(value -> config.flashbackAutoRecord = value)
                .build());

        ConfigCategory server = builder.getOrCreateCategory(Text.literal("服务器"));
        server.addEntry(entries.startStrField(Text.literal("接口地址"), config.apiBaseUrl)
                .setDefaultValue(CeStatsConfig.DEFAULT_BASE_URL)
                .setTooltip(Text.literal("统计站点的根地址，比赛会 POST 到 <地址>/api/ingest 和 <地址>/api/ping/*"))
                .setSaveConsumer(value -> config.apiBaseUrl = value.trim())
                .build());
        server.addEntry(entries.startStrField(Text.literal("网页地址"), config.webBaseUrl)
                .setDefaultValue(CeStatsConfig.DEFAULT_BASE_URL)
                .setTooltip(Text.literal("聊天里「查看详情」打开的地址，通常和接口地址相同"))
                .setSaveConsumer(value -> config.webBaseUrl = value.trim())
                .build());
        return builder.build();
    }
}
