package com.cestats.compat;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;

import java.net.URI;

/**
 * Minecraft 1.21.5 and later.
 *
 * <p>{@code ClickEvent} and {@code HoverEvent} became sealed interfaces with a record per action,
 * and the URL is now a {@link URI} rather than a string.
 */
public final class TextCompat {

    private TextCompat() {
    }

    /** A clickable, hover-previewed link. */
    public static MutableText link(String label, String url) {
        URI uri = URI.create(url);
        return Text.literal(label)
                .formatted(Formatting.AQUA)
                .styled(style -> style
                        .withClickEvent(new ClickEvent.OpenUrl(uri))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal(url))));
    }

    public static void openUrl(String url) {
        Util.getOperatingSystem().open(URI.create(url));
    }

    /**
     * Text that copies {@code value} to the system clipboard when clicked.
     *
     * <p>The bind code has to travel from the game to a QQ window, and retyping six characters is
     * exactly where a player loses two minutes to a typo.
     */
    public static MutableText copyable(String label, String value, String hover) {
        return Text.literal(label)
                .formatted(Formatting.AQUA)
                .styled(style -> style
                        .withClickEvent(new ClickEvent.CopyToClipboard(value))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover))));
    }
}
