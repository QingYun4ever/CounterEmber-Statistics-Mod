package com.cestats.compat;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;

/**
 * World rendering hook for Minecraft 1.21.9 and later.
 *
 * <p>Same phase as on older versions, but 1.21.9 moved the events into {@code ...rendering.v1.world},
 * split the render state out of the world renderer (so the camera comes from the game renderer
 * rather than the context) and moved the layer factories to {@link RenderLayers}.</p>
 */
public final class MarkerRenderCompat {

    private MarkerRenderCompat() {
    }

    public static void register(WorldMarkerDrawer drawer) {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            if (context.matrices() == null || context.consumers() == null) {
                return;
            }
            drawer.draw(context.matrices(), context.consumers(), seeThroughQuads(),
                    context.gameRenderer().getCamera().getCameraPos());
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
        return RenderLayers.textBackgroundSeeThrough();
    }
}
