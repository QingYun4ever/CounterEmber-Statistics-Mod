package com.cestats;

import com.cestats.compat.TextCompat;
import com.cestats.config.CeStatsConfig;
import com.cestats.net.PairingClient;
import com.cestats.ping.PingDemo;
import com.cestats.ping.PingRelayClient;
import com.mojang.brigadier.arguments.StringArgumentType;

import java.util.concurrent.ThreadLocalRandom;
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
                        .then(ClientCommandManager.literal("ping")
                                .executes(CeStatsCommand::pingStatus)
                                .then(ClientCommandManager.literal("code")
                                        .executes(CeStatsCommand::newPingCode))
                                .then(ClientCommandManager.literal("join")
                                        .then(ClientCommandManager.argument("code", StringArgumentType.word())
                                                .executes(CeStatsCommand::joinPingCode)))
                                .then(ClientCommandManager.literal("leave")
                                        .executes(CeStatsCommand::leavePingCode)))
                        .then(ClientCommandManager.literal("url")
                                .then(ClientCommandManager.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> setUrl(ctx, false))))
                        .then(ClientCommandManager.literal("web")
                                .then(ClientCommandManager.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> setUrl(ctx, true))))
                        .then(ClientCommandManager.literal("pair")
                                .then(ClientCommandManager.argument("code", StringArgumentType.word())
                                        .executes(CeStatsCommand::pair)))
                        .then(ClientCommandManager.literal("unpair")
                                .executes(CeStatsCommand::unpair))));
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
        source.sendFeedback(row("上传身份", config.isPaired()
                ? "已配对（" + config.pairedPlayer + "）"
                : "未配对（/cestats pair <配对码>）"));
        source.sendFeedback(row("网页", config.webBaseUrl));
        source.sendFeedback(row("本地存档", CeStatsClient.store().archivedCount() + " 场"));
        source.sendFeedback(row("待上传", CeStatsClient.uploader().queueSize() + " 场"));
        source.sendFeedback(row("标点中继", pingStatusText()));
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

    private static int pingStatus(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        source.sendFeedback(head("标点中继 " + pingStatusText()));
        String code = CeStatsClient.config().pingTeamCode;
        if (code != null && !code.isBlank()) {
            source.sendFeedback(row("队伍码", code));
        } else {
            source.sendFeedback(row("队伍码", "未设置（等待自动识别）"));
        }
        PingRelayClient.Status status = PingDemo.relayStatus();
        if (status == null) {
            return 1;
        }
        source.sendFeedback(row("识别方式", "code".equals(status.mode()) ? "手动队伍码"
                : "auto".equals(status.mode()) ? "自动（玩家列表 + 首次观测到的阵营）"
                : "尚未识别"));
        if (status.channel() != null) {
            // Teammates comparing these eight characters is the quickest way to tell "we are in
            // different channels" apart from "the relay is down".
            String shown = status.channel().substring(0, Math.min(8, status.channel().length()));
            source.sendFeedback(row("频道", shown + "（与队友核对这八位应一致）"));
        }
        source.sendFeedback(row("标点数", "本地 " + PingDemo.localPingCount()
                + " / 队友 " + PingDemo.remotePingCount()));
        if (status.lastError() != null) {
            source.sendFeedback(row("最近错误", status.lastError()));
        }
        return 1;
    }

    private static int newPingCode(CommandContext<FabricClientCommandSource> ctx) {
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(100000, 1_000_000));
        CeStatsClient.config().pingTeamCode = code;
        PingDemo.reset();
        ctx.getSource().sendFeedback(head("新的标点队伍码：" + code));
        ctx.getSource().sendFeedback(row("队友加入", "/cestats ping join " + code));
        return 1;
    }

    private static int joinPingCode(CommandContext<FabricClientCommandSource> ctx) {
        String code = StringArgumentType.getString(ctx, "code").trim();
        if (!code.matches("\\d{6}")) {
            ctx.getSource().sendError(Text.literal("队伍码必须是六位数字"));
            return 0;
        }
        CeStatsClient.config().pingTeamCode = code;
        PingDemo.reset();
        ctx.getSource().sendFeedback(head("已加入标点队伍频道 " + code));
        return 1;
    }

    private static int leavePingCode(CommandContext<FabricClientCommandSource> ctx) {
        CeStatsClient.config().pingTeamCode = null;
        PingDemo.reset();
        ctx.getSource().sendFeedback(head("已退出手动标点队伍频道，将尝试自动识别"));
        return 1;
    }

    private static String pingStatusText() {
        PingRelayClient.Status status = PingDemo.relayStatus();
        if (status == null) {
            return CeStatsClient.config().pingEnabled ? "已开启" : "已关闭";
        }
        return switch (status.phase()) {
            case DISABLED -> "已关闭";
            case UNPAIRED -> "未配对，无法同步（/cestats pair <配对码>）";
            case NO_IDENTITY -> "已开启，但还没识别到队伍频道（可用 /cestats ping code）";
            case JOINING -> "正在加入频道…";
            case JOINED -> "已同步";
            case RETRYING -> "已开启，正在重试加入";
        };
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

    private static int pair(CommandContext<FabricClientCommandSource> ctx) {
        String code = StringArgumentType.getString(ctx, "code").trim();
        if (!code.matches("[A-Fa-f0-9]{16}")) {
            ctx.getSource().sendError(Text.literal("配对码必须是 16 位十六进制字符"));
            return 0;
        }

        CeStatsConfig config = CeStatsClient.config();
        String player = CeStatsClient.client().getSession().getUsername();
        ctx.getSource().sendFeedback(head("正在请求配对，请稍候…"));
        PairingClient.pair(config, code, player, result -> CeStatsClient.client().execute(() -> {
            if (result.ok()) {
                CeStatsClient.uploader().requeuePending();
                ctx.getSource().sendFeedback(head(result.message()));
            } else {
                ctx.getSource().sendError(Text.literal(result.message()));
            }
        }));
        return 1;
    }

    private static int unpair(CommandContext<FabricClientCommandSource> ctx) {
        CeStatsClient.config().clearPairing();
        PingDemo.reset();
        ctx.getSource().sendFeedback(head("已解除设备配对；待上传数据会保留，重新配对后可继续上传"));
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
