package com.cestats.compat;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;

import java.net.URI;

/**
 * Minecraft 1.21.2 – 1.21.4 flavour.
 *
 * <p>Here {@code ClickEvent} and {@code HoverEvent} are plain classes taking an action enum.
 * 1.21.5 turned both into sealed interfaces with one record per action, which is the only API
 * break this mod hits — see the sibling file under {@code src/compat/v1_21_5}.
 */
public final class TextCompat {

    private TextCompat() {
    }

    /** A clickable, hover-previewed link. */
    public static MutableText link(String label, String url) {
        return Text.literal(label)
                .formatted(Formatting.AQUA)
                .styled(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(url))));
    }

    public static void openUrl(String url) {
        Util.getOperatingSystem().open(URI.create(url));
    }
}
