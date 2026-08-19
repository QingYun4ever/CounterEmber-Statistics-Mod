package com.cestats.ping;

import net.minecraft.util.math.Vec3d;

/**
 * One live marker, as both the placing client and the world renderer see it.
 *
 * <p>{@code createdAt} and {@code expiresAt} are always on the <em>local</em> clock, including for
 * markers that arrived from the relay — {@link PingRelayClient} rebases teammate timestamps on the
 * way in, so nothing downstream has to know about server clock skew.</p>
 */
public record PingMarker(String id, Vec3d position, PingKind kind, long createdAt, long expiresAt) {
}
