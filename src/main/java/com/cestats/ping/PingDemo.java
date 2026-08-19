package com.cestats.ping;

import com.cestats.compat.ParticleCompat;
import com.cestats.config.CeStatsConfig;
import com.cestats.parse.ChatEvent;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.RaycastContext;

/**
 * CS2-style client marker demo with an optional, short-lived team relay.
 *
 * <p>It uses the vanilla pick-item binding (middle mouse by default), so a click is consumed at
 * the start of the client tick before vanilla creative pick-block handling. The target is a
 * straight 64-block ray, with a three-marker cap and a short cooldown. Rendering remains local;
 * the optional relay only forwards quantized coordinates and expiry metadata.</p>
 */
public final class PingDemo {

    private static final long NORMAL_LIFETIME_MS = 4_000L;
    private static final long WARNING_LIFETIME_MS = 5_000L;
    private static final long PARTICLE_INTERVAL_MS = 220L;
    private static final double MAX_PING_DISTANCE = 64.0;

    private static final DustParticleEffect NORMAL_PARTICLE =
            new DustParticleEffect(0x29E6E8, 0.58F);
    private static final DustParticleEffect WARNING_PARTICLE =
            new DustParticleEffect(0xFF3045, 0.68F);
    private static final DustParticleEffect WARNING_ACCENT_PARTICLE =
            new DustParticleEffect(0xFFD23F, 0.56F);

    private static final PingClickDetector CLICK_DETECTOR = new PingClickDetector();
    private static final PingPolicy POLICY = new PingPolicy();
    private static final java.util.ArrayList<LocalPing> ACTIVE_PINGS = new java.util.ArrayList<>();
    private static final Map<String, LocalPing> REMOTE_PINGS = new HashMap<>();
    /** Fresh per game session so a marker id is never reused across a reset or a restart. */
    private static final String SESSION_NONCE = UUID.randomUUID().toString().substring(0, 8);
    private static PingRelayClient RELAY;
    private static long nextMarkerId;
    private static long lastParticleEmitAt = Long.MIN_VALUE;

    private PingDemo() {
    }

    public static void register(CeStatsConfig config) {
        RELAY = new PingRelayClient(config);
        RELAY.start();
        // START is intentional: consuming pickItemKey.wasPressed() here prevents the same middle
        // click from also being handled as creative pick-block by vanilla later in the tick.
        ClientTickEvents.START_CLIENT_TICK.register(PingDemo::onClientTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private static void onClientTick(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (RELAY != null) {
            RELAY.tick(client);
            applyRemoteSnapshot(client, RELAY.consumeSnapshot(), now);
        }
        commitExpiredNormal(now);
        if (client.world != null) {
            ACTIVE_PINGS.removeIf(ping -> now >= ping.expiresAt());
            REMOTE_PINGS.values().removeIf(ping -> now >= ping.expiresAt());
        }

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
            return;
        }

        ACTIVE_PINGS.removeIf(ping -> now >= ping.expiresAt());
        REMOTE_PINGS.values().removeIf(ping -> now >= ping.expiresAt());
        if (ACTIVE_PINGS.isEmpty() && REMOTE_PINGS.isEmpty()
                || (lastParticleEmitAt != Long.MIN_VALUE
                && now - lastParticleEmitAt < PARTICLE_INTERVAL_MS)) {
            return;
        }
        lastParticleEmitAt = now;
        for (LocalPing ping : ACTIVE_PINGS) {
            emitMarker(client.world, ping, now);
        }
        for (LocalPing ping : REMOTE_PINGS.values()) {
            emitMarker(client.world, ping, now);
        }
    }

    private static void placeFromCrosshair(MinecraftClient client, long now) {
        Vec3d position = resolvePingPosition(client);
        if (position == null) {
            CLICK_DETECTOR.reset();
            showOverlay(client, Text.literal("标点失败：无法取得准星位置").formatted(Formatting.GRAY));
            return;
        }

        PingClickDetector.ClickResult click = CLICK_DETECTOR.registerClick(now);
        if (click == PingClickDetector.ClickResult.WARNING) {
            if (!ACTIVE_PINGS.isEmpty()) {
                LocalPing previous = ACTIVE_PINGS.get(ACTIVE_PINGS.size() - 1);
                LocalPing warning = new LocalPing(previous.id(), position, PingKind.WARNING, now,
                        now + WARNING_LIFETIME_MS);
                ACTIVE_PINGS.set(ACTIVE_PINGS.size() - 1, warning);
                publish(warning);
                POLICY.acceptWarning(now);
                showOverlay(client, Text.literal("⚠ 警告标点").formatted(Formatting.RED));
            } else {
                CLICK_DETECTOR.reset();
                showOverlay(client, Text.literal("警告标点失败：没有可升级的标点")
                        .formatted(Formatting.GRAY));
            }
            return;
        }

        PingPolicy.Decision decision = POLICY.tryAcceptNormal(now, ACTIVE_PINGS.size());
        if (decision == PingPolicy.Decision.COOLDOWN) {
            CLICK_DETECTOR.reset();
            double seconds = POLICY.cooldownRemainingMs(now) / 1_000.0;
            showOverlay(client, Text.literal(String.format(Locale.ROOT, "标点冷却中 %.1fs", seconds))
                    .formatted(Formatting.YELLOW));
            return;
        }
        if (decision == PingPolicy.Decision.LIMIT_REACHED) {
            CLICK_DETECTOR.reset();
            showOverlay(client, Text.literal("标点已达到上限（" + POLICY.maxActivePings() + " 个）")
                    .formatted(Formatting.YELLOW));
            return;
        }

        LocalPing ping = new LocalPing(newMarkerId(), position, PingKind.NORMAL, now,
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

    private static void publish(LocalPing ping) {
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

    private static void emitMarker(ClientWorld world, LocalPing ping, long now) {
        double age = (now - ping.createdAt()) / 1_000.0;
        double pulse = 1.0 + Math.sin(age * (ping.kind() == PingKind.WARNING ? 7.0 : 5.0)) * 0.10;
        double radius = (ping.kind() == PingKind.WARNING ? 0.62 : 0.46) * pulse;
        double y = ping.position().y;
        DustParticleEffect particle = ping.kind() == PingKind.WARNING
                ? WARNING_PARTICLE
                : NORMAL_PARTICLE;

        int ringPoints = ping.kind() == PingKind.WARNING ? 8 : 6;
        for (int i = 0; i < ringPoints; i++) {
            double angle = (Math.PI * 2.0 * i / ringPoints) + age * 0.7;
            spawn(world,
                    ping.position().x + Math.cos(angle) * radius,
                    y,
                    ping.position().z + Math.sin(angle) * radius,
                    particle);
        }

        // A short vertical pointer makes the marker readable even when the ground ring is partly
        // occluded by grass or uneven terrain.
        int beamPoints = ping.kind() == PingKind.WARNING ? 3 : 2;
        for (int i = 0; i < beamPoints; i++) {
            double beamY = y + 0.12 + i * 0.22;
            spawn(world, ping.position().x, beamY, ping.position().z, particle);
        }

        if (ping.kind() == PingKind.WARNING && ((now / 180L) & 1L) == 0L) {
            spawn(world, ping.position().x, y + 1.65, ping.position().z,
                    WARNING_ACCENT_PARTICLE);
        }
    }

    private static void spawn(ClientWorld world, double x, double y, double z,
                              DustParticleEffect particle) {
        ParticleCompat.spawn(world, particle, x, y, z, 0.0, 0.0, 0.0);
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
            if (marker.owner().equals(owner) || !marker.dimension().equals(dimension)) continue;
            // Marker timestamps are already on the local clock. The cap is only a guard against a
            // relay reporting a far-future expiry; normal expiry is the value itself.
            long expiresAt = Math.min(marker.expiresAt(), now + WARNING_LIFETIME_MS);
            if (now >= expiresAt) continue;
            PingKind kind = "warning".equals(marker.kind()) ? PingKind.WARNING : PingKind.NORMAL;
            REMOTE_PINGS.put(marker.id(), new LocalPing(marker.id(),
                    new Vec3d(marker.x(), marker.y(), marker.z()), kind,
                    Math.min(marker.createdAt(), now), expiresAt));
            seen.add(marker.id());
        }
        REMOTE_PINGS.keySet().removeIf(id -> !seen.contains(id));
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
        // nextMarkerId deliberately keeps counting. The relay treats a repeated id as an idempotent
        // retry, so re-issuing one while the previous marker is still alive there would leave
        // teammates looking at the old position while this client shows the new one.
        lastParticleEmitAt = Long.MIN_VALUE;
        CLICK_DETECTOR.reset();
        POLICY.reset();
    }

    private record LocalPing(String id, Vec3d position, PingKind kind, long createdAt, long expiresAt) {
    }

    private enum PingKind {
        NORMAL,
        WARNING
    }
}
