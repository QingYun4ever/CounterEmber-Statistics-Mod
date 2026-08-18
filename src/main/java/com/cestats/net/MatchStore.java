package com.cestats.net;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Disk archive plus upload queue, both under {@code .minecraft/config/cestats/}.
 *
 * <p>Every match is archived permanently under {@code matches/}; a marker under {@code pending/}
 * means "not yet accepted by the API". Markers survive a crash or a restart, so a match is never
 * lost just because the site was down.
 */
public final class MatchStore {

    private static final Logger LOG = LoggerFactory.getLogger("cestats");

    private final Path root;
    private final Path matches;
    private final Path pending;

    public MatchStore() {
        this(FabricLoader.getInstance().getConfigDir().resolve("cestats"));
    }

    public MatchStore(Path root) {
        this.root = root;
        this.matches = root.resolve("matches");
        this.pending = root.resolve("pending");
        try {
            Files.createDirectories(matches);
            Files.createDirectories(pending);
        } catch (IOException e) {
            LOG.warn("[cestats] 无法创建数据目录 {}: {}", root, e.toString());
        }
    }

    public Path root() {
        return root;
    }

    /** Archives the payload and marks it as awaiting upload. */
    public void store(String matchId, String json) {
        try {
            Files.writeString(matches.resolve(matchId + ".json"), json, StandardCharsets.UTF_8);
            Files.writeString(pending.resolve(matchId), "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("[cestats] 比赛写盘失败 {}: {}", matchId, e.toString());
        }
    }

    public String read(String matchId) {
        try {
            Path file = matches.resolve(matchId + ".json");
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            LOG.error("[cestats] 比赛读取失败 {}: {}", matchId, e.toString());
            return null;
        }
    }

    public void markUploaded(String matchId) {
        try {
            Files.deleteIfExists(pending.resolve(matchId));
        } catch (IOException e) {
            LOG.warn("[cestats] 无法清除待传标记 {}: {}", matchId, e.toString());
        }
    }

    public List<String> pendingIds() {
        try (Stream<Path> stream = Files.list(pending)) {
            List<String> ids = new ArrayList<>();
            stream.filter(Files::isRegularFile).forEach(p -> ids.add(p.getFileName().toString()));
            Collections.sort(ids);
            return ids;
        } catch (IOException e) {
            return List.of();
        }
    }

    public int archivedCount() {
        try (Stream<Path> stream = Files.list(matches)) {
            return (int) stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
