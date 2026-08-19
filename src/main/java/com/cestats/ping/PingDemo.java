package com.cestats.ping;

import com.cestats.compat.ParticleCompat;
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

    private static final DustParticleEffect NORMAL_PARTICLE =
            new DustParticleEffect(0x29E6E8, 1.25F);
    private static final DustParticleEffect WARNING_PARTICLE =
            new DustParticleEffect(0xFF3045, 1.45F);
    private static final DustParticleEffect WARNING_ACCENT_PARTICLE =
            new DustParticleEffect(0xFFD23F, 1.10F);

    private static final PingClickDetector CLICK_DETECTOR = new PingClickDetector();
    private static LocalPing activePing;

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

        if (activePing == null) {
            return;
        }
        if (client.world == null || now >= activePing.expiresAt()) {
            activePing = null;
            return;
        }

        emitMarker(client.world, activePing, now);
    }

    private static void placeFromCrosshair(MinecraftClient client, long now) {
        HitResult target = client.crosshairTarget;
        if (target == null || target.getType() == HitResult.Type.MISS) {
            showOverlay(client, Text.literal("标点失败：准星没有目标").formatted(Formatting.GRAY));
            return;
        }

        Vec3d position = markerPosition(target);
        PingClickDetector.ClickResult click = CLICK_DETECTOR.registerClick(now);
        if (click == PingClickDetector.ClickResult.WARNING) {
            activePing = new LocalPing(position, PingKind.WARNING, now,
                    now + WARNING_LIFETIME_MS);
            showOverlay(client, Text.literal("⚠ 警告标点").formatted(Formatting.RED));
        } else {
            activePing = new LocalPing(position, PingKind.NORMAL, now,
                    now + NORMAL_LIFETIME_MS);
            showOverlay(client, Text.literal("◎ 标点").formatted(Formatting.AQUA));
        }
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
        double radius = (ping.kind() == PingKind.WARNING ? 0.66 : 0.50) * pulse;
        double y = ping.position().y;
        DustParticleEffect particle = ping.kind() == PingKind.WARNING
                ? WARNING_PARTICLE
                : NORMAL_PARTICLE;

        int ringPoints = ping.kind() == PingKind.WARNING ? 16 : 12;
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
        int beamPoints = ping.kind() == PingKind.WARNING ? 7 : 5;
        for (int i = 0; i < beamPoints; i++) {
            double beamY = y + 0.12 + i * 0.22;
            spawn(world, ping.position().x, beamY, ping.position().z, particle);
        }

        if (ping.kind() == PingKind.WARNING && ((now / 100L) & 1L) == 0L) {
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
        activePing = null;
        CLICK_DETECTOR.reset();
    }

    private record LocalPing(Vec3d position, PingKind kind, long createdAt, long expiresAt) {
    }

    private enum PingKind {
        NORMAL,
        WARNING
    }
}
