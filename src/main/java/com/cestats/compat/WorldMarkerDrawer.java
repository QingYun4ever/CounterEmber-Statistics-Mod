package com.cestats.compat;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

/**
 * One world-space draw pass, handed everything that differs between Minecraft versions so the
 * geometry itself can live in shared code. Registered through {@code MarkerRenderCompat}.
 *
 * @see com.cestats.ping.PingMarkerRenderer
 */
public interface WorldMarkerDrawer {

    /**
     * @param matrices   the world matrix stack for this frame; already holds the camera rotation,
     *                   so vertex positions must be given relative to {@code cameraPos}
     * @param consumers  buffers that vanilla flushes later in the same frame
     * @param layer      an untextured, self-lit, depth-test-free quad layer (see
     *                   {@code MarkerRenderCompat.seeThroughQuads()})
     * @param cameraPos  the camera position in world coordinates
     */
    void draw(MatrixStack matrices, VertexConsumerProvider consumers, RenderLayer layer,
              Vec3d cameraPos);
}
