package com.cestats.compat;

import net.minecraft.client.network.PlayerListEntry;

/** Small cross-target adapter for authlib GameProfile accessors. */
public final class PlayerCompat {
    private PlayerCompat() {
    }

    public static String name(PlayerListEntry entry) {
        Object profile = entry.getProfile();
        for (String methodName : new String[]{"getName", "name"}) {
            try {
                Object value = profile.getClass().getMethod(methodName).invoke(profile);
                if (value instanceof String name) return name;
            } catch (ReflectiveOperationException ignored) {
                // 1.21.11 exposes name(), older targets expose getName().
            }
        }
        return null;
    }
}
