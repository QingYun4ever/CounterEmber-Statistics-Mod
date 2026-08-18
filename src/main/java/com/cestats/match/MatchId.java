package com.cestats.match;

import com.cestats.model.StatLine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Deliberately timestamp-free: two clients that watched the same match must derive the same id so
 * the server can dedupe them. The roster plus every exact stat value is unique in practice.
 *
 * <p>Must produce byte-identical output to {@code computeMatchId} in
 * {@code web/src/lib/protocol.ts}.
 */
public final class MatchId {

    private MatchId() {
    }

    public static String compute(String server, List<StatLine> players) {
        List<String> lines = new ArrayList<>(players.size());
        for (StatLine p : players) {
            lines.add(String.format(Locale.ROOT, "%s:%d-%d-%d:%d:%d:%.2f",
                    p.name(), p.kills(), p.deaths(), p.assists(), p.adr(), p.kast(), p.rating()));
        }
        Collections.sort(lines);

        String payload = server + "|" + String.join(",", lines);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
