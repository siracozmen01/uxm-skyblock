package com.uxplima.uxmskyblock.core.application.mission;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;

/** Keeps each island's and profile's progress apart, in memory, as a server's storage would. */
final class MissionStorageInMemory implements IslandMissionStoragePort {

    private final Map<String, MissionProgress> stored = new ConcurrentHashMap<>();

    private static String key(IslandId islandId, ProfileId profileId) {
        return islandId.value() + ":" + profileId.value() + ":";
    }

    @Override
    public Optional<MissionProgress> findProgress(IslandId islandId, ProfileId profileId, MissionId missionId) {
        return Optional.ofNullable(stored.get(key(islandId, profileId) + missionId.value()));
    }

    @Override
    public Map<MissionId, MissionProgress> findAllProgress(IslandId islandId, ProfileId profileId) {
        Map<MissionId, MissionProgress> found = new HashMap<>();
        stored.forEach((key, progress) -> {
            if (key.startsWith(key(islandId, profileId))) {
                found.put(progress.missionId(), progress);
            }
        });
        return found;
    }

    @Override
    public void saveProgress(IslandId islandId, ProfileId profileId, MissionProgress progress) {
        stored.put(key(islandId, profileId) + progress.missionId().value(), progress);
    }

    @Override
    public void saveAllProgress(IslandId islandId, ProfileId profileId, Collection<MissionProgress> progresses) {
        progresses.forEach(progress -> saveProgress(islandId, profileId, progress));
    }
}
