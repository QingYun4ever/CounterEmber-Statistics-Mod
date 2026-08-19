package com.cestats.ping;

import com.cestats.compat.PlayerCompat;
import com.cestats.model.Side;
import com.cestats.parse.ChatEvent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Best-effort identity for the client-only relay.
 *
 * <p>The game server does not expose a match id to the mod. During a match we therefore use the
 * visible player roster as the automatic match fingerprint and the local player's first observed
 * CT/T side as the stable team fingerprint. The side fingerprint is deliberately not the current
 * side: CT/T swaps at halftime. If either signal is unavailable, the manual six-digit team code
 * is the reliable fallback.</p>
 */
public final class PingIdentity {

    private String server = "unknown";
    private String player = "unknown";
    private List<String> matchRoster;
    private final Map<String, Side> firstObservedSides = new LinkedHashMap<>();

    public void setContext(String server, String player) {
        this.server = server == null ? "unknown" : server;
        this.player = player == null ? "unknown" : player;
        resetMatch();
    }

    public void accept(ChatEvent event) {
        switch (event) {
            case ChatEvent.Kill kill -> {
                firstObservedSides.putIfAbsent(kill.killer(), kill.killerSide());
                firstObservedSides.putIfAbsent(kill.victim(), kill.victimSide());
            }
            case ChatEvent.ContextReset ignored -> resetMatch();
            case ChatEvent.Result ignored -> resetMatch();
            default -> {
                // Round-end and stats messages do not provide a more reliable live identity.
            }
        }
    }

    /** Reads the server's [ALL] [CT/T] prefix when the local player speaks. */
    public void acceptChatText(String content) {
        if (content == null || player == null || player.isBlank() || !content.startsWith("[ALL] [")) {
            return;
        }
        int prefixEnd = content.indexOf("] ", 7);
        if (prefixEnd < 0) return;
        Side side;
        try {
            side = Side.valueOf(content.substring(7, prefixEnd));
        } catch (IllegalArgumentException ignored) {
            return;
        }
        int colon = content.indexOf(':');
        if (colon > 0 && content.substring(0, colon).trim().endsWith(player)) {
            firstObservedSides.putIfAbsent(player, side);
        }
    }

    public void resetMatch() {
        firstObservedSides.clear();
        matchRoster = null;
    }

    /** Returns an automatic identity only when both the live roster and local team are known. */
    public Identity resolve(MinecraftClient client, String manualCode) {
        if (manualCode != null && !manualCode.isBlank()) {
            Identity manual = manual(client, manualCode);
            if (manual != null) {
                return manual;
            }
        }
        return automatic(client);
    }

    public Identity automatic(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null
                || client.getNetworkHandler() == null) {
            return null;
        }
        Side ownInitialSide = firstObservedSides.get(player);
        if (ownInitialSide == null) {
            return null;
        }

        if (!ensureRoster(client)) return null;

        String dimension = client.world.getRegistryKey().getValue().toString();
        String matchKey = digest(normalize(server) + "|" + dimension + "|"
                + String.join(",", matchRoster));
        // This is the initial team, not the currently displayed CT/T side. It remains stable after
        // the halftime side swap and is equal for teammates that observed the same first half.
        String teamKey = ownInitialSide.name();
        return new Identity("auto", matchKey, teamKey, dimension);
    }

    public Identity manual(MinecraftClient client, String code) {
        if (client == null || client.player == null || client.world == null || code == null) {
            return null;
        }
        String normalized = code.trim();
        if (!normalized.matches("\\d{6}")) {
            return null;
        }
        String dimension = client.world.getRegistryKey().getValue().toString();
        String matchKey = normalize(server) + "|" + dimension;
        // A manual code is intentionally a team secret. It is not persisted and expires with the
        // relay's idle channel, so a reused code cannot become a permanent room identifier.
        return new Identity("code", matchKey, normalized, dimension);
    }

    private boolean ensureRoster(MinecraftClient client) {
        if (matchRoster != null) return true;
        List<String> roster = new ArrayList<>();
        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            String name = PlayerCompat.name(entry);
            if (name != null && !name.isBlank()) roster.add(name);
        }
        if (roster.isEmpty()) return false;
        roster.add(player);
        matchRoster = roster.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        return true;
    }

    public String player() {
        return player;
    }

    public String ownerId() {
        return digest("owner|" + normalize(player));
    }

    private static String normalize(String value) {
        return value == null ? "unknown" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format("%02x", b & 0xff));
            }
            return out.substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record Identity(String mode, String matchKey, String teamKey, String dimension) {
        public String channel() {
            return digest("cestats-ping-v1|" + mode + "|" + matchKey + "|" + teamKey);
        }
    }
}
