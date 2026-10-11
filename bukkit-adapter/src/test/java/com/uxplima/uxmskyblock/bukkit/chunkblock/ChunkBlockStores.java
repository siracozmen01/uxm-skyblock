package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkPaymentPort;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkTerritoryPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPayments;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** The ChunkBlock tables in memory, for the tests of what a player sees and pays. */
final class ChunkBlockStores {

    private ChunkBlockStores() {}

    /** Every island's first chunk, open chunks and bought chunks, as the territory table keeps them. */
    static final class Territories implements ChunkTerritoryPort {
        final Map<IslandId, ChunkPos> origins = new HashMap<>();
        final Map<IslandId, List<ChunkPos>> opened = new HashMap<>();
        final Map<IslandId, List<ChunkPos>> bought = new HashMap<>();

        @Override
        public Map<IslandId, ChunkTerritory> findAll() {
            return Map.of();
        }

        @Override
        public Optional<ChunkTerritory> find(IslandId islandId) {
            ChunkPos origin = origins.get(islandId);
            return origin == null
                    ? Optional.empty()
                    : Optional.of(new ChunkTerritory(origin, opened.getOrDefault(islandId, List.of())));
        }

        @Override
        public void start(IslandId islandId, ChunkPos origin) {
            origins.putIfAbsent(islandId, origin);
        }

        @Override
        public boolean open(IslandId islandId, ChunkPos chunk, int order) {
            return opened.computeIfAbsent(islandId, id -> new ArrayList<>()).add(chunk);
        }

        @Override
        public boolean open(IslandId islandId, ChunkPos chunk, int order, boolean paidFor) {
            if (paidFor) {
                bought.computeIfAbsent(islandId, id -> new ArrayList<>()).add(chunk);
            }
            return open(islandId, chunk, order);
        }

        @Override
        public void close(IslandId islandId, List<ChunkPos> chunks) {
            opened.getOrDefault(islandId, new ArrayList<>()).removeAll(chunks);
        }
    }

    /** What was paid toward each chunk, and each island's chunk points. */
    static final class Payments implements ChunkPaymentPort {
        private final Map<String, Long> paid = new HashMap<>();
        private final Map<IslandId, Long> points = new HashMap<>();
        boolean failing;

        private String at(IslandId islandId, ChunkPos chunk) {
            return islandId + "/" + chunk.x() + "," + chunk.z() + "/";
        }

        @Override
        public ChunkPayments find(IslandId islandId, ChunkPos chunk) {
            Map<String, Long> found = new HashMap<>();
            paid.forEach((key, amount) -> {
                if (key.startsWith(at(islandId, chunk))) {
                    found.put(key.substring(at(islandId, chunk).length()), amount);
                }
            });
            return new ChunkPayments(found);
        }

        @Override
        public void add(IslandId islandId, ChunkPos chunk, String key, long amount) {
            if (failing) {
                throw new IllegalStateException("the table is gone");
            }
            paid.merge(at(islandId, chunk) + key, amount, Long::sum);
        }

        @Override
        public void clear(IslandId islandId, ChunkPos chunk) {
            paid.keySet().removeIf(key -> key.startsWith(at(islandId, chunk)));
        }

        @Override
        public long points(IslandId islandId) {
            return points.getOrDefault(islandId, 0L);
        }

        @Override
        public void addPoints(IslandId islandId, long amount) {
            points.merge(islandId, amount, Long::sum);
        }

        @Override
        public boolean spendPoints(IslandId islandId, long amount) {
            if (points(islandId) < amount) {
                return false;
            }
            points.merge(islandId, -amount, Long::sum);
            return true;
        }
    }
}
