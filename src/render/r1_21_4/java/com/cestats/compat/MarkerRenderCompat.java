package com.cestats.compat;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.RenderLayer;

/**
 * World rendering hook for Minecraft 1.21.2 – 1.21.8.
 *
 * <p>{@code AFTER_ENTITIES} is the point where vanilla has drawn all opaque terrain, entities and
 * block entities but has not yet flushed the shared vertex buffers, so anything queued here is
 * drawn by vanilla itself a few lines later — no manual flush, no GL state juggling.</p>
 */
public final class MarkerRenderCompat {

    private MarkerRenderCompat() {
    }

    public static void register(WorldMarkerDrawer drawer) {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            if (context.matrixStack() == null || context.consumers() == null) {
                return;
            }
            drawer.draw(context.matrixStack(), context.consumers(), seeThroughQuads(),
                    context.camera().getPos());
        });
    }

    /**
     * Untextured quads, full-bright lightmap, {@code ALWAYS} depth test and no depth write.
     *
     * <p>Vanilla uses it for the plate behind a nameplate in "see through" mode. Reusing it buys
     * three properties a ping marker needs and a particle cannot have: no texture at all, so no
     * resource pack can remove or reskin it; no depth test, so terrain never hides it; and its own
     * lightmap value, so it glows the same in a cave and at noon.</p>
     */
    public static RenderLayer seeThroughQuads() {
        return RenderLayer.getTextBackgroundSeeThrough();
    }
}
