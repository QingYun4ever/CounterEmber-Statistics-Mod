package com.cestats;

import com.cestats.compat.TextCompat;
import com.cestats.config.CeStatsConfig;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** {@code /cestats ...} — client-side only, never reaches the server. */
public final class CeStatsCommand {

    private CeStatsCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommandManager.literal("cestats")
                        .executes(CeStatsCommand::status)
                        .then(ClientCommandManager.literal("status").executes(CeStatsCommand::status))
                        .then(ClientCommandManager.literal("toggle").executes(CeStatsCommand::toggle))
                        .then(ClientCommandManager.literal("record").executes(CeStatsCommand::toggleRecording))
                        .then(ClientCommandManager.literal("retry").executes(CeStatsCommand::retry))
                        .then(ClientCommandManager.literal("open").executes(ctx ->
                                openUrl(ctx, CeStatsClient.config().webBaseUrl)))
                        .then(ClientCommandManager.literal("last").executes(CeStatsCommand::last))
                        .then(ClientCommandManager.literal("url")
                                .then(ClientCommandManager.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> setUrl(ctx, false))))
                        .then(ClientCommandManager.literal("web")
                                .then(ClientCommandManager.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> setUrl(ctx, true))))
                        .then(ClientCommandManager.literal("key")
                                .then(ClientCommandManager.argument("value", StringArgumentType.greedyString())
                                        .executes(CeStatsCommand::setKey)))));
    }

    private static int status(CommandContext<FabricClientCommandSource> ctx) {
        CeStatsConfig config = CeStatsClient.config();
        FabricClientCommandSource source = ctx.getSource();
        source.sendFeedback(head("CE Stats"));
        source.sendFeedback(row("状态", config.enabled ? "开启" : "关闭"));
        source.sendFeedback(row("上传", config.uploadEnabled ? "开启" : "关闭"));
        source.sendFeedback(row("Flashback录制", config.flashbackAutoRecord ? "开启" : "关闭"));
        if (CeStatsClient.recordingController() != null && config.flashbackAutoRecord) {
            source.sendFeedback(row("Flashback", CeStatsClient.recordingController().isAvailable()
                    ? "已检测" : "未安装或接口不兼容"));
        }
        source.sendFeedback(row("接口", config.ingestUrl()));
        source.sendFeedback(row("网页", config.webBaseUrl));
        source.sendFeedback(row("本地存档", CeStatsClient.store().archivedCount() + " 场"));
        source.sendFeedback(row("待上传", CeStatsClient.uploader().queueSize() + " 场"));
        source.sendFeedback(row("数据目录", CeStatsClient.store().root().toString()));
        return 1;
    }

    private static int toggle(CommandContext<FabricClientCommandSource> ctx) {
        CeStatsConfig config = CeStatsClient.config();
        config.enabled = !config.enabled;
        config.save();
        if (CeStatsClient.recordingController() != null) {
            CeStatsClient.recordingController().setEnabled(
                    config.enabled && config.flashbackAutoRecord);
        }
        ctx.getSource().sendFeedback(head(config.enabled ? "已开启统计" : "已关闭统计"));
        return 1;
    }

    private static int toggleRecording(CommandContext<FabricClientCommandSource> ctx) {
        CeStatsConfig config = CeStatsClient.config();
        config.flashbackAutoRecord = !config.flashbackAutoRecord;
        config.save();
        if (CeStatsClient.recordingController() != null) {
            CeStatsClient.recordingController().setEnabled(
                    config.enabled && config.flashbackAutoRecord);
        }
        ctx.getSource().sendFeedback(head(config.flashbackAutoRecord
                ? "已开启 Flashback 自动录制" : "已关闭 Flashback 自动录制"));
        return 1;
    }

    private static int retry(CommandContext<FabricClientCommandSource> ctx) {
        int n = CeStatsClient.uploader().requeuePending();
        ctx.getSource().sendFeedback(head("已重新排队 " + n + " 场待上传比赛"));
        return 1;
    }

    private static int last(CommandContext<FabricClientCommandSource> ctx) {
        String id = CeStatsClient.lastMatchId();
        if (id == null) {
            ctx.getSource().sendFeedback(head("本次启动还没有结算过比赛"));
            return 0;
        }
        String url = CeStatsClient.config().matchUrl(id);
        ctx.getSource().sendFeedback(head("上一场比赛")
                .append(CeStatsClient.notifier().link("  [查看详情]", url)));
        return 1;
    }

    private static int openUrl(CommandContext<FabricClientCommandSource> ctx, String url) {
        try {
            TextCompat.openUrl(url);
            ctx.getSource().sendFeedback(head("已在浏览器中打开 " + url));
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendError(Text.literal("打开失败：" + e.getMessage()));
            return 0;
        }
    }

    private static int setUrl(CommandContext<FabricClientCommandSource> ctx, boolean web) {
        CeStatsConfig config = CeStatsClient.config();
        String value = StringArgumentType.getString(ctx, "value").trim();
        if (web) {
            config.webBaseUrl = value;
        } else {
            config.apiBaseUrl = value;
        }
        config.save();
        ctx.getSource().sendFeedback(head((web ? "网页地址" : "接口地址") + " 已设为 " + value));
        return 1;
    }

    private static int setKey(CommandContext<FabricClientCommandSource> ctx) {
        CeStatsConfig config = CeStatsClient.config();
        config.apiKey = StringArgumentType.getString(ctx, "value").trim();
        config.save();
        CeStatsClient.uploader().requeuePending();
        ctx.getSource().sendFeedback(head("API Key 已保存，待传比赛已重新排队"));
        return 1;
    }

    private static net.minecraft.text.MutableText head(String text) {
        return Text.literal("[CE] ").formatted(Formatting.AQUA)
                .append(Text.literal(text).formatted(Formatting.WHITE));
    }

    private static Text row(String label, String value) {
        return Text.literal("  " + label + "  ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(value).formatted(Formatting.GRAY));
    }
}
