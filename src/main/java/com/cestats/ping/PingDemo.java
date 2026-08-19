package com.cestats.ping;

import com.cestats.config.CeStatsConfig;
import com.cestats.parse.ChatEvent;
import com.cestats.ui.ChatNotifier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.RaycastContext;

/**
 * CS2-style client marker demo with an optional, short-lived team relay.
 *
 * <p>It uses the vanilla pick-item binding (middle mouse by default), so a click is consumed at
 * the start of the client tick before vanilla creative pick-block handling. Because of exactly that,
 * the whole feature is off until it is switched on in Mod Menu ({@link CeStatsConfig#pingMarkerEnabled});
 * while off, nothing here touches the key. The target is a straight 64-block ray, with a
 * three-marker cap and a short cooldown. Rendering remains local (see {@link PingMarkerRenderer});
 * the optional relay only forwards quantized coordinates and expiry metadata.</p>
 */
public final class PingDemo {

    private static final long NORMAL_LIFETIME_MS = 4_000L;
    private static final long WARNING_LIFETIME_MS = 5_000L;
    private static final double MAX_PING_DISTANCE = 64.0;

    private static final PingClickDetector CLICK_DETECTOR = new PingClickDetector();
    private static final PingPolicy POLICY = new PingPolicy();
    private static final List<PingMarker> ACTIVE_PINGS = new ArrayList<>();
    private static final Map<String, PingMarker> REMOTE_PINGS = new HashMap<>();
    /** Ids already reported as skipped, so one unusable teammate marker logs once, not once per poll. */
    private static final Set<String> DEBUG_REPORTED_DROPS = new HashSet<>();
    /** Fresh per game session so a marker id is never reused across a reset or a restart. */
    private static final String SESSION_NONCE = UUID.randomUUID().toString().substring(0, 8);
    private static CeStatsConfig CONFIG;
    private static ChatNotifier NOTIFIER;
    private static PingRelayClient RELAY;
    private static long nextMarkerId;

    private PingDemo() {
    }

    public static void register(CeStatsConfig config, ChatNotifier notifier) {
        CONFIG = config;
        NOTIFIER = notifier;
        RELAY = new PingRelayClient(config, PingDemo::debug);
        RELAY.start();
        // START is intentional: consuming pickItemKey.wasPressed() here prevents the same middle
        // click from also being handled as creative pick-block by vanilla later in the tick.
        ClientTickEvents.START_CLIENT_TICK.register(PingDemo::onClientTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        PingMarkerRenderer.register();
    }

    /**
     * Debug sink for the relay and for local rejections. The relay calls this from HTTP callback and
     * poller threads, so it must not touch the HUD directly; ChatNotifier queues for the client tick.
     */
    private static void debug(String message, boolean ok) {
        if (NOTIFIER != null) {
            NOTIFIER.pingDebug(message, ok);
        }
    }

    /** Local-side debug line, for outcomes decided before the relay is ever asked. */
    private static void debugLocal(String message, boolean ok) {
        if (CONFIG != null && CONFIG.pingDebug) {
            debug(message, ok);
        }
    }

    private static void onClientTick(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (RELAY != null) {
            // Called even while the feature is off: the relay checks the same switches itself and
            // uses this tick to leave its channel and stop its poller.
            RELAY.tick(client);
            applyRemoteSnapshot(client, RELAY.consumeSnapshot(), now);
        }

        if (!markersEnabled()) {
            // Deliberately no pickItemKey drain on this path. While the feature is off the middle
            // click has to reach vanilla's own pick-block handling later in this same tick.
            if (!ACTIVE_PINGS.isEmpty() || !REMOTE_PINGS.isEmpty()) {
                resetLocal();
            }
            return;
        }

        commitExpiredNormal(now);
        // Expiry runs before the click is read, so an expired marker neither occupies a slot against
        // the cap nor gets resurrected into a warning by a double click.
        ACTIVE_PINGS.removeIf(ping -> now >= ping.expiresAt());
        REMOTE_PINGS.values().removeIf(ping -> now >= ping.expiresAt());

        // This is the default middle-mouse action. It follows the player's existing Minecraft
        // keybind for the demo; a future standalone version can expose a dedicated physical mouse
        // binding without changing the ping state machine below.
        while (client.options.pickItemKey.wasPressed()) {
            if (client.world == null || client.player == null || client.currentScreen != null) {
                continue;
            }
            placeFromCrosshair(client, now);
        }

        if (client.world == null) {
            ACTIVE_PINGS.clear();
            REMOTE_PINGS.clear();
        }
    }

    /**
     * The one gate for the whole feature. Off by default; see
     * {@link CeStatsConfig#pingMarkerEnabled} for why enabling it has to be a deliberate act.
     */
    private static boolean markersEnabled() {
        return CONFIG != null && CONFIG.enabled && CONFIG.pingMarkerEnabled;
    }

    /**
     * Hands the world renderer everything that should be on screen right now, local markers first.
     *
     * <p>The client tick and the world render run on the same thread, so this is not a safety copy —
     * it just flattens the two collections into the single list the renderer iterates, without
     * allocating one per frame.</p>
     */
    static void collectMarkers(List<PingMarker> out) {
        if (!markersEnabled()) {
            return;
        }
        out.addAll(ACTIVE_PINGS);
        out.addAll(REMOTE_PINGS.values());
    }

    private static void placeFromCrosshair(MinecraftClient client, long now) {
        Vec3d position = resolvePingPosition(client);
        if (position == null) {
            CLICK_DETECTOR.reset();
            showOverlay(client, Text.literal("标点失败：无法取得准星位置").formatted(Formatting.GRAY));
            debugLocal("本地拒绝：无法取得准星位置", false);
            return;
        }

        PingClickDetector.ClickResult click = CLICK_DETECTOR.registerClick(now);
        if (click == PingClickDetector.ClickResult.WARNING) {
            if (!ACTIVE_PINGS.isEmpty()) {
                PingMarker previous = ACTIVE_PINGS.get(ACTIVE_PINGS.size() - 1);
                PingMarker warning = new PingMarker(previous.id(), position, PingKind.WARNING, now,
                        now + WARNING_LIFETIME_MS);
                ACTIVE_PINGS.set(ACTIVE_PINGS.size() - 1, warning);
                publish(warning);
                POLICY.acceptWarning(now);
                showOverlay(client, Text.literal("⚠ 警告标点").formatted(Formatting.RED));
            } else {
                CLICK_DETECTOR.reset();
                showOverlay(client, Text.literal("警告标点失败：没有可升级的标点")
                        .formatted(Formatting.GRAY));
                debugLocal("本地拒绝：没有可升级为警告的标点", false);
            }
            return;
        }

        PingPolicy.Decision decision = POLICY.tryAcceptNormal(now, ACTIVE_PINGS.size());
        if (decision == PingPolicy.Decision.COOLDOWN) {
            CLICK_DETECTOR.reset();
            double seconds = POLICY.cooldownRemainingMs(now) / 1_000.0;
            showOverlay(client, Text.literal(String.format(Locale.ROOT, "标点冷却中 %.1fs", seconds))
                    .formatted(Formatting.YELLOW));
            debugLocal(String.format(Locale.ROOT, "本地拒绝：冷却中，还需 %.1fs", seconds), false);
            return;
        }
        if (decision == PingPolicy.Decision.LIMIT_REACHED) {
            CLICK_DETECTOR.reset();
            showOverlay(client, Text.literal("标点已达到上限（" + POLICY.maxActivePings() + " 个）")
                    .formatted(Formatting.YELLOW));
            debugLocal("本地拒绝：已达到标点上限（" + POLICY.maxActivePings() + " 个）", false);
            return;
        }

        PingMarker ping = new PingMarker(newMarkerId(), position, PingKind.NORMAL, now,
                now + NORMAL_LIFETIME_MS);
        ACTIVE_PINGS.add(ping);
        publish(ping);
        showOverlay(client, Text.literal("◎ 标点").formatted(Formatting.AQUA));
    }

    private static Vec3d resolvePingPosition(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return null;
        }

        Vec3d start = client.player.getCameraPosVec(1.0F);
        Vec3d direction = client.player.getRotationVec(1.0F).normalize();
        Vec3d end = start.add(direction.multiply(MAX_PING_DISTANCE));
        HitResult blockOrFluid = client.world.raycast(new RaycastContext(
                start, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, client.player));
        if (blockOrFluid.getType() != HitResult.Type.MISS) {
            return markerPosition(blockOrFluid);
        }

        // No block is required: on open space the marker is placed at the maximum ray distance.
        // Entity targeting is intentionally not added to this prototype; the straight ray point
        // keeps the behavior deterministic and local.
        return end;
    }

    private static Vec3d markerPosition(HitResult target) {
        if (target instanceof BlockHitResult blockHit) {
            var normal = blockHit.getSide().getVector();
            return target.getPos().add(normal.getX() * 0.08, normal.getY() * 0.08,
                    normal.getZ() * 0.08);
        }
        return target.getPos().add(0.0, 0.06, 0.0);
    }

    private static void publish(PingMarker ping) {
        if (RELAY != null) {
            RELAY.publish(ping.id(), ping.kind() == PingKind.WARNING ? "warning" : "normal",
                    ping.position().x, ping.position().y, ping.position().z,
                    "" + pingDimension());
        }
    }

    private static String pingDimension() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.world == null ? "unknown" : client.world.getRegistryKey().getValue().toString();
    }

    private static String newMarkerId() {
        String owner = RELAY == null ? "local" : RELAY.ownerId();
        return owner + "-" + SESSION_NONCE + "-" + (++nextMarkerId);
    }

    private static void showOverlay(MinecraftClient client, Text message) {
        InGameHud hud = client.inGameHud;
        if (hud != null) {
            hud.setOverlayMessage(message, false);
        }
    }

    private static void commitExpiredNormal(long now) {
        if (!CLICK_DETECTOR.isPendingExpired(now)) {
            return;
        }

        // The normal ping is already shown immediately; only the detector remains pending so a
        // second click can upgrade that marker to a warning.
        CLICK_DETECTOR.commitPending();
    }

    private static void applyRemoteSnapshot(MinecraftClient client,
                                             PingRelayClient.Snapshot snapshot, long now) {
        if (snapshot == null || client.world == null) return;
        Set<String> seen = new HashSet<>();
        String owner = RELAY == null ? "" : RELAY.ownerId();
        String dimension = client.world.getRegistryKey().getValue().toString();
        for (PingRelayClient.Marker marker : snapshot.markers()) {
            if (marker.owner().equals(owner)) continue;
            if (!marker.dimension().equals(dimension)) {
                // Worth reporting once: it means teammates are synced but standing in different
                // worlds, which otherwise looks identical to "the relay never delivered anything".
                if (DEBUG_REPORTED_DROPS.add(marker.id())) {
                    debugLocal("忽略队友标点：来自其他世界 " + marker.dimension(), false);
                }
                continue;
            }
            // Marker timestamps are already on the local clock. The cap is only a guard against a
            // relay reporting a far-future expiry; normal expiry is the value itself.
            long expiresAt = Math.min(marker.expiresAt(), now + WARNING_LIFETIME_MS);
            if (now >= expiresAt) {
                if (DEBUG_REPORTED_DROPS.add(marker.id())) {
                    debugLocal(String.format(Locale.ROOT, "忽略队友标点：已过期 %.1fs",
                            (now - marker.expiresAt()) / 1_000.0), false);
                }
                continue;
            }
            PingKind kind = "warning".equals(marker.kind()) ? PingKind.WARNING : PingKind.NORMAL;
            PingMarker previous = REMOTE_PINGS.put(marker.id(), new PingMarker(marker.id(),
                    new Vec3d(marker.x(), marker.y(), marker.z()), kind,
                    Math.min(marker.createdAt(), now), expiresAt));
            if (previous == null || previous.kind() != kind) {
                debugLocal("收到队友标点" + (kind == PingKind.WARNING ? "（警告）" : "")
                        + " " + String.format(Locale.ROOT, "%.0f %.0f %.0f",
                        marker.x(), marker.y(), marker.z())
                        + "，剩余 " + String.format(Locale.ROOT, "%.1fs",
                        (expiresAt - now) / 1_000.0), true);
            }
            seen.add(marker.id());
        }
        REMOTE_PINGS.keySet().removeIf(id -> !seen.contains(id));
        // Ids here are only needed while their marker can still show up in a snapshot; a plain bound
        // keeps this from growing over a long session at the cost of an occasional repeat line.
        if (DEBUG_REPORTED_DROPS.size() > 64) {
            DEBUG_REPORTED_DROPS.clear();
        }
    }

    public static void setContext(String server, String player) {
        if (RELAY != null) RELAY.setContext(server, player);
        resetLocal();
    }

    /** Null before {@link #register} runs; otherwise the live relay state for {@code /cestats ping}. */
    public static PingRelayClient.Status relayStatus() {
        return RELAY == null ? null : RELAY.status();
    }

    public static int localPingCount() {
        return ACTIVE_PINGS.size();
    }

    public static int remotePingCount() {
        return REMOTE_PINGS.size();
    }

    public static void accept(ChatEvent event) {
        if (RELAY != null) RELAY.accept(event);
    }

    public static void acceptChatText(String content) {
        // The identity object is intentionally private to the relay client; chat text is forwarded
        // through a small method so the automatic side probe can use [ALL] [CT/T] prefixes.
        if (RELAY != null) RELAY.acceptChatText(content);
    }

    public static void reset() {
        if (RELAY != null) RELAY.reset();
        resetLocal();
    }

    private static void resetLocal() {
        ACTIVE_PINGS.clear();
        REMOTE_PINGS.clear();
        DEBUG_REPORTED_DROPS.clear();
        // nextMarkerId deliberately keeps counting. The relay treats a repeated id as an idempotent
        // retry, so re-issuing one while the previous marker is still alive there would leave
        // teammates looking at the old position while this client shows the new one.
        CLICK_DETECTOR.reset();
        POLICY.reset();
    }
}
