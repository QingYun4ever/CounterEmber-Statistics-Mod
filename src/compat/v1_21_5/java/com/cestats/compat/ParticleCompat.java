package com.cestats.compat;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleEffect;

/** Minecraft 1.21.5+ client particle API. */
public final class ParticleCompat {

    private ParticleCompat() {
    }

    public static void spawn(ClientWorld world, ParticleEffect particle,
                             double x, double y, double z,
                             double velocityX, double velocityY, double velocityZ) {
        world.addParticleClient(particle, x, y, z, velocityX, velocityY, velocityZ);
    }
}
