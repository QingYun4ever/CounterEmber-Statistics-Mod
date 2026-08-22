package com.cestats.ping;

import com.cestats.CeStatsClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;

/** Plays the short, screen-relative confirmation cue for local and teammate pings. */
public final class PingSoundPlayer {

    private static final Identifier NORMAL_SOUND = Identifier.of(CeStatsClient.MOD_ID, "ping.normal");
    private static final Identifier WARNING_SOUND = Identifier.of(CeStatsClient.MOD_ID, "ping.warning");

    private PingSoundPlayer() {
    }

    public static void play(MinecraftClient client, PingKind kind) {
        if (client == null || client.getSoundManager() == null) {
            return;
        }
        Identifier sound = kind == PingKind.WARNING ? WARNING_SOUND : NORMAL_SOUND;
        float volume = kind == PingKind.WARNING ? 0.82F : 0.70F;
        client.getSoundManager().play(new PositionedSoundInstance(
                sound,
                SoundCategory.PLAYERS,
                volume,
                1.0F,
                SoundInstance.createRandom(),
                false,
                0,
                SoundInstance.AttenuationType.NONE,
                0.0,
                0.0,
                0.0,
                true));
    }
}
