package com.cestats.ping;

import com.cestats.compat.ParticleCompat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * A deliberately local-only ping prototype.
 *
 * <p>It uses the vanilla pick-item binding (middle mouse by default), so a click is consumed at
 * the start of the client tick before vanilla creative pick-block handling. No packet, payload, or
 * server-side state is involved yet.</p>
 */
public final class PingDemo {

    private static final long NORMAL_LIFETIME_MS = 4_000L;
    private static final long WARNING_LIFETIME_MS = 5_000L;
    private static final double MAX_PING_DISTANCE = 64.0;

    private static final DustParticleEffect NORMAL_PARTICLE =
            new DustParticleEffect(0x29E6E8, 0.72F);
    private static final DustParticleEffect WARNING_PARTICLE =
            new DustParticleEffect(0xFF3045, 0.86F);
    private static final DustParticleEffect WARNING_ACCENT_PARTICLE =
            new DustParticleEffect(0xFFD23F, 0.68F);

    private static final PingClickDetector CLICK_DETECTOR = new PingClickDetector();
    private static final PingPolicy POLICY = new PingPolicy();
    private static final java.util.ArrayList<LocalPing> ACTIVE_PINGS = new java.util.ArrayList<>();

    private PingDemo() {
    }

    public static void register() {
        // START is intentional: consuming pickItemKey.wasPressed() here prevents the same middle
        // click from also being handled as creative pick-block by vanilla later in the tick.
        ClientTickEvents.START_CLIENT_TICK.register(PingDemo::onClientTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private static void onClientTick(MinecraftClient client) {
        long now = System.currentTimeMillis();
        commitExpiredNormal(now);

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
            return;
        }

        ACTIVE_PINGS.removeIf(ping -> now >= ping.expiresAt());
        for (LocalPing ping : ACTIVE_PINGS) {
            emitMarker(client.world, ping, now);
        }
    }

    private static void placeFromCrosshair(MinecraftClient client, long now) {
        HitResult target = resolvePingTarget(client);
        if (target == null) {
            CLICK_DETECTOR.reset();
            showOverlay(client, Text.literal("标点失败：超出距离").formatted(Formatting.GRAY));
            return;
        }

        Vec3d position = markerPosition(target);
        PingClickDetector.ClickResult click = CLICK_DETECTOR.registerClick(now);
        if (click == PingClickDetector.ClickResult.WARNING) {
            if (!ACTIVE_PINGS.isEmpty()) {
                ACTIVE_PINGS.set(ACTIVE_PINGS.size() - 1,
                        new LocalPing(position, PingKind.WARNING, now, now + WARNING_LIFETIME_MS));
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
            showOverlay(client, Text.literal("标点冷却中").formatted(Formatting.YELLOW));
            return;
        }
        if (decision == PingPolicy.Decision.LIMIT_REACHED) {
            CLICK_DETECTOR.reset();
            showOverlay(client, Text.literal("标点已达到上限（3 个）")
                    .formatted(Formatting.YELLOW));
            return;
        }

        ACTIVE_PINGS.add(new LocalPing(position, PingKind.NORMAL, now,
                now + NORMAL_LIFETIME_MS));
        showOverlay(client, Text.literal("◎ 标点").formatted(Formatting.AQUA));
    }

    private static HitResult resolvePingTarget(MinecraftClient client) {
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
            return blockOrFluid;
        }

        // No block is required: on open space the marker is placed at the maximum ray distance.
        // Entity targeting is intentionally not added to this prototype; the straight ray point
        // keeps the behavior deterministic and local.
        return new HitResult(end) {
            @Override
            public Type getType() {
                return Type.MISS;
            }
        };
    }

    private static Vec3d markerPosition(HitResult target) {
        if (target instanceof BlockHitResult blockHit) {
            var normal = blockHit.getSide().getVector();
            return target.getPos().add(normal.getX() * 0.08, normal.getY() * 0.08,
                    normal.getZ() * 0.08);
        }
        return target.getPos().add(0.0, 0.06, 0.0);
    }

    private static void emitMarker(ClientWorld world, LocalPing ping, long now) {
        double age = (now - ping.createdAt()) / 1_000.0;
        double pulse = 1.0 + Math.sin(age * (ping.kind() == PingKind.WARNING ? 7.0 : 5.0)) * 0.10;
        double radius = (ping.kind() == PingKind.WARNING ? 0.62 : 0.46) * pulse;
        double y = ping.position().y;
        DustParticleEffect particle = ping.kind() == PingKind.WARNING
                ? WARNING_PARTICLE
                : NORMAL_PARTICLE;

        int ringPoints = ping.kind() == PingKind.WARNING ? 10 : 7;
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
        int beamPoints = ping.kind() == PingKind.WARNING ? 4 : 3;
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

    public static void reset() {
        ACTIVE_PINGS.clear();
        CLICK_DETECTOR.reset();
        POLICY.reset();
    }

    private record LocalPing(Vec3d position, PingKind kind, long createdAt, long expiresAt) {
    }

    private enum PingKind {
        NORMAL,
        WARNING
    }
}
