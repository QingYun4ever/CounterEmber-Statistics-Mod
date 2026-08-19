package com.cestats.ping;

import com.cestats.compat.MarkerRenderCompat;
import com.cestats.compat.WorldMarkerDrawer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws every live ping as a glowing, untextured billboard that terrain cannot hide.
 *
 * <p>This replaces the original particle marker. Particles were the wrong vehicle twice over: a
 * server resource pack can drop the {@code dust} particle outright (which is exactly what happened),
 * and particles are depth-tested and fixed-size, so a marker behind a wall or fifty blocks out was
 * invisible or a speck. A ping has to be readable from anywhere on the map — that is the whole point
 * of it — so the marker is drawn as geometry the mod owns end to end.</p>
 *
 * <p>Three decisions do the work:</p>
 * <ul>
 *   <li><b>See-through, self-lit layer.</b> {@code MarkerRenderCompat.seeThroughQuads()} has no
 *       texture (nothing for a resource pack to remove), no depth test (walls never occlude) and its
 *       own lightmap value, which is pinned to full brightness so the marker glows in a dark cave
 *       exactly as it does at noon.</li>
 *   <li><b>Constant apparent size.</b> The marker is scaled by its distance to the camera, so it
 *       covers the same fraction of the screen at 5 blocks and at 300. Without this, "visible across
 *       the map" and "not obnoxious up close" cannot both hold.</li>
 *   <li><b>A tip that points at the exact spot.</b> The diamond head floats above the pinged
 *       position and a tapered stem runs down to it, so the marker never covers what it marks.</li>
 * </ul>
 *
 * <p>Rendering is entirely local: nothing here is sent anywhere, and a marker that arrived from the
 * relay is drawn by the same code as one this client placed.</p>
 */
public final class PingMarkerRenderer implements WorldMarkerDrawer {

    /**
     * Radius of the diamond head as a fraction of the distance to it — i.e. its angular size in
     * radians. 0.014 rad is roughly 0.8°, about a 25-pixel-wide head at 1080p and default FOV.
     */
    private static final double NORMAL_ANGULAR_SIZE = 0.014;
    /** Warnings are drawn deliberately larger so the two kinds are distinguishable at a glance. */
    private static final double WARNING_ANGULAR_SIZE = 0.017;
    /** Below this the marker stops shrinking, so standing on top of a ping still shows something. */
    private static final double MIN_WORLD_SIZE = 0.10;

    /** A short overshoot on spawn; the same cue CS2 uses to make a new ping catch the eye. */
    private static final long POP_IN_MS = 160L;
    private static final long FADE_OUT_MS = 450L;

    private static final int NORMAL_COLOR = 0x2FE8E4;
    private static final int NORMAL_ACCENT = 0xE8FFFF;
    private static final int WARNING_COLOR = 0xFF2D3F;
    private static final int WARNING_ACCENT = 0xFFC53D;
    private static final int PLATE_COLOR = 0x000000;

    // Local billboard units, one unit = one head radius. The origin is the pinged position and +Y is
    // up on screen, so every offset below scales with the head and the layout is distance-invariant.
    private static final float HEAD_Y = 2.00F;
    private static final float HEAD_OUTER = 1.00F;
    private static final float HEAD_INNER = 0.72F;
    private static final float PLATE_RADIUS = 1.34F;
    private static final float STEM_TOP_Y = 1.06F;
    private static final float STEM_TOP_HALF = 0.26F;
    private static final float STEM_TIP_HALF = 0.05F;
    private static final float PLATE_STEM_TOP_HALF = 0.40F;
    private static final float PLATE_STEM_TIP_HALF = 0.13F;

    /** Alphas are per pass; see {@link #quad} — every quad is emitted twice, so these compound. */
    private static final int PLATE_ALPHA = 80;
    private static final int GLOW_ALPHA = 55;
    private static final int SOLID_ALPHA = 240;

    /** Diamond corner directions, counted so that consecutive entries share an edge. */
    private static final float[] CORNER_X = {0.0F, 1.0F, 0.0F, -1.0F};
    private static final float[] CORNER_Y = {-1.0F, 0.0F, 1.0F, 0.0F};

    /** Reused every frame; {@code draw} only ever runs on the render thread. */
    private final List<PingMarker> scratch = new ArrayList<>();

    public static void register() {
        MarkerRenderCompat.register(new PingMarkerRenderer());
    }

    @Override
    public void draw(MatrixStack matrices, VertexConsumerProvider consumers, RenderLayer layer,
                     Vec3d cameraPos) {
        scratch.clear();
        PingDemo.collectMarkers(scratch);
        if (scratch.isEmpty()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.gameRenderer == null) {
            return;
        }
        // The same quaternion vanilla uses to face a nameplate at the camera. Multiplying it in makes
        // local +X screen-right and local +Y screen-up, which is what every offset above assumes.
        var rotation = client.gameRenderer.getCamera().getRotation();

        long now = System.currentTimeMillis();
        VertexConsumer buffer = consumers.getBuffer(layer);
        for (PingMarker marker : scratch) {
            drawMarker(matrices, buffer, rotation, cameraPos, marker, now);
        }
        scratch.clear();
    }

    private static void drawMarker(MatrixStack matrices, VertexConsumer buffer,
                                   org.joml.Quaternionf rotation, Vec3d cameraPos,
                                   PingMarker marker, long now) {
        long remaining = marker.expiresAt() - now;
        if (remaining <= 0L) {
            return;
        }
        boolean warning = marker.kind() == PingKind.WARNING;
        long age = Math.max(0L, now - marker.createdAt());

        double distance = cameraPos.distanceTo(marker.position());
        double angular = warning ? WARNING_ANGULAR_SIZE : NORMAL_ANGULAR_SIZE;
        double pop = age >= POP_IN_MS
                ? 1.0
                : 1.0 + square(1.0 - age / (double) POP_IN_MS) * 0.75;
        double pulse = 1.0 + Math.sin(age / 1_000.0 * (warning ? 11.0 : 6.0))
                * (warning ? 0.09 : 0.05);
        float size = (float) (Math.max(MIN_WORLD_SIZE, distance * angular) * pop * pulse);

        // Only the tail end fades. Fading in as well would fight the pop-in, which is the cue that
        // actually matters when a teammate's marker appears somewhere you were not looking.
        float fade = remaining >= FADE_OUT_MS ? 1.0F : (float) (remaining / (double) FADE_OUT_MS);
        int color = warning ? WARNING_COLOR : NORMAL_COLOR;
        int accent = warning ? WARNING_ACCENT : NORMAL_ACCENT;

        matrices.push();
        matrices.translate(marker.position().x - cameraPos.x,
                marker.position().y - cameraPos.y,
                marker.position().z - cameraPos.z);
        matrices.multiply(rotation);
        matrices.scale(size, size, size);
        MatrixStack.Entry entry = matrices.peek();

        // Back to front. Nothing here writes depth, so this order is the only thing deciding what
        // ends up on top.
        diamond(buffer, entry, HEAD_Y, PLATE_RADIUS, PLATE_COLOR, alpha(PLATE_ALPHA, fade));
        stem(buffer, entry, PLATE_STEM_TIP_HALF, PLATE_STEM_TOP_HALF, PLATE_COLOR,
                alpha(PLATE_ALPHA, fade));
        stem(buffer, entry, STEM_TIP_HALF, STEM_TOP_HALF, color, alpha(SOLID_ALPHA, fade));
        diamond(buffer, entry, HEAD_Y, HEAD_INNER, color, alpha(GLOW_ALPHA, fade));
        diamondRing(buffer, entry, HEAD_Y, HEAD_OUTER, HEAD_INNER, color, alpha(SOLID_ALPHA, fade));
        if (warning) {
            // An exclamation mark, built from two rectangles so it needs no font and no texture.
            rect(buffer, entry, -0.15F, HEAD_Y - 0.06F, 0.15F, HEAD_Y + 0.46F, accent,
                    alpha(SOLID_ALPHA, fade));
            rect(buffer, entry, -0.15F, HEAD_Y - 0.42F, 0.15F, HEAD_Y - 0.20F, accent,
                    alpha(SOLID_ALPHA, fade));
        } else {
            diamond(buffer, entry, HEAD_Y, 0.30F, accent, alpha(SOLID_ALPHA, fade));
        }

        matrices.pop();
    }

    private static int alpha(int base, float fade) {
        return Math.max(1, Math.round(base * fade));
    }

    private static double square(double value) {
        return value * value;
    }

    /** Filled diamond of the given radius, centred at {@code (0, cy)}. A rhombus is a convex quad. */
    private static void diamond(VertexConsumer buffer, MatrixStack.Entry entry, float cy, float r,
                                int rgb, int alpha) {
        quad(buffer, entry, 0.0F, cy - r, -r, cy, 0.0F, cy + r, r, cy, rgb, alpha);
    }

    /** Outlined diamond: one trapezoid per edge, between the {@code outer} and {@code inner} radius. */
    private static void diamondRing(VertexConsumer buffer, MatrixStack.Entry entry, float cy,
                                    float outer, float inner, int rgb, int alpha) {
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) & 3;
            quad(buffer, entry,
                    CORNER_X[i] * outer, cy + CORNER_Y[i] * outer,
                    CORNER_X[next] * outer, cy + CORNER_Y[next] * outer,
                    CORNER_X[next] * inner, cy + CORNER_Y[next] * inner,
                    CORNER_X[i] * inner, cy + CORNER_Y[i] * inner,
                    rgb, alpha);
        }
    }

    /** Tapered pointer from the pinged position up to the underside of the head. */
    private static void stem(VertexConsumer buffer, MatrixStack.Entry entry, float tipHalf,
                             float topHalf, int rgb, int alpha) {
        quad(buffer, entry, -tipHalf, 0.0F, -topHalf, STEM_TOP_Y, topHalf, STEM_TOP_Y,
                tipHalf, 0.0F, rgb, alpha);
    }

    private static void rect(VertexConsumer buffer, MatrixStack.Entry entry, float x0, float y0,
                             float x1, float y1, int rgb, int alpha) {
        quad(buffer, entry, x0, y0, x0, y1, x1, y1, x1, y0, rgb, alpha);
    }

    /**
     * Emits one flat quad twice, the second time wound backwards.
     *
     * <p>The layer keeps back-face culling on, and which winding counts as the front face depends on
     * the sign conventions of the projection matrix in the Minecraft version being built against.
     * Getting that wrong makes the marker silently invisible — the exact failure this rewrite is
     * meant to end — so both faces are emitted. It costs eight vertices per quad on a dozen quads
     * per marker, which is nothing next to a single nameplate.</p>
     *
     * <p>Consequence worth knowing: translucent geometry is blended twice, so an alpha of {@code a}
     * lands at {@code 1-(1-a)²}. The constants above are chosen with that in mind.</p>
     */
    private static void quad(VertexConsumer buffer, MatrixStack.Entry entry,
                             float x1, float y1, float x2, float y2,
                             float x3, float y3, float x4, float y4,
                             int rgb, int alpha) {
        vertex(buffer, entry, x1, y1, rgb, alpha);
        vertex(buffer, entry, x2, y2, rgb, alpha);
        vertex(buffer, entry, x3, y3, rgb, alpha);
        vertex(buffer, entry, x4, y4, rgb, alpha);

        vertex(buffer, entry, x4, y4, rgb, alpha);
        vertex(buffer, entry, x3, y3, rgb, alpha);
        vertex(buffer, entry, x2, y2, rgb, alpha);
        vertex(buffer, entry, x1, y1, rgb, alpha);
    }

    private static void vertex(VertexConsumer buffer, MatrixStack.Entry entry, float x, float y,
                               int rgb, int alpha) {
        buffer.vertex(entry, x, y, 0.0F)
                .color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha)
                // Pinning the lightmap to maximum is what makes the marker "glow": the layer samples
                // the light texture, so anything less would let a dark room dim it.
                .light(LightmapTextureManager.MAX_LIGHT_COORDINATE);
    }
}
