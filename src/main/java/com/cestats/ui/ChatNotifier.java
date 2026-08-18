package com.cestats.ui;

import com.cestats.compat.TextCompat;
import com.cestats.config.CeStatsConfig;
import com.cestats.model.MatchRecord;
import com.cestats.model.PlayerRecord;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Post-match chat feedback.
 *
 * <p>Messages are queued rather than sent directly: upload results arrive on the uploader thread,
 * and the chat HUD may only be touched from the render thread.
 */
public final class ChatNotifier {

    private final ConcurrentLinkedQueue<Text> outbox = new ConcurrentLinkedQueue<>();
    private final CeStatsConfig config;

    public ChatNotifier(CeStatsConfig config) {
        this.config = config;
    }

    /** Drains queued messages into the chat HUD. Call from a client tick. */
    public void flush(MinecraftClient client) {
        if (client.inGameHud == null) {
            return;
        }
        Text message;
        while ((message = outbox.poll()) != null) {
            client.inGameHud.getChatHud().addMessage(message);
        }
    }

    public void send(Text text) {
        outbox.offer(text);
    }

    public void sendPlain(String text) {
        send(prefix().append(Text.literal(text).formatted(Formatting.GRAY)));
    }

    /** "已记录 · 本场 Rating 1.52 · K-D-A 12-10-5 · ADR 151  [查看详情]" */
    public void matchFinished(MatchRecord match) {
        if (!config.notifyOnMatchEnd) {
            return;
        }
        MutableText line = prefix();

        PlayerRecord self = match.player(match.uploader());
        if (self != null) {
            double rating = self.stat().rating();
            line.append(Text.literal("本场 Rating ").formatted(Formatting.GRAY));
            line.append(Text.literal(String.format(Locale.ROOT, "%.2f", rating))
                    .formatted(ratingColor(rating)));
            line.append(Text.literal(String.format(Locale.ROOT, "  %d-%d-%d  ADR %d  KAST %d%%",
                            self.stat().kills(), self.stat().deaths(), self.stat().assists(),
                            self.stat().adr(), self.stat().kast()))
                    .formatted(Formatting.GRAY));
        } else {
            line.append(Text.literal("已记录一场比赛（你未上场）").formatted(Formatting.GRAY));
        }

        if (!match.complete()) {
            line.append(Text.literal("  [部分观测]").formatted(Formatting.YELLOW));
        }
        line.append(link("  [查看详情]", config.matchUrl(match.matchId())));
        send(line);
    }

    public void uploadResult(String matchId, boolean ok, String message) {
        MutableText line = prefix();
        if (ok) {
            line.append(Text.literal("已上传").formatted(Formatting.GREEN));
            line.append(link("  [查看详情]", config.matchUrl(matchId)));
        } else {
            line.append(Text.literal(message).formatted(Formatting.RED));
        }
        send(line);
    }

    public MutableText link(String label, String url) {
        return TextCompat.link(label, url);
    }

    private static MutableText prefix() {
        return Text.literal("[CE] ").formatted(Formatting.AQUA);
    }

    private static Formatting ratingColor(double rating) {
        if (rating > 1.5) {
            return Formatting.LIGHT_PURPLE;
        }
        if (rating >= 1.15) {
            return Formatting.BLUE;
        }
        if (rating >= 0.85) {
            return Formatting.WHITE;
        }
        return Formatting.RED;
    }
}
