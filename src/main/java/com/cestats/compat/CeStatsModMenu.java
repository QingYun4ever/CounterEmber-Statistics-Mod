package com.cestats.compat;

import com.cestats.config.CeStatsConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Registered under the "modmenu" entrypoint, which Fabric only resolves when Mod Menu is
 * installed — so this class is never loaded otherwise and Mod Menu stays a soft dependency.
 */
public final class CeStatsModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return CeStatsConfigScreen::create;
    }
}
