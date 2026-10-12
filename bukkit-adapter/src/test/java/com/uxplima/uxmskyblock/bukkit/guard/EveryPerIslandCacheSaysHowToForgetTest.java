package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every service that keeps something per island says how to forget one, and says it to the wiring.
 *
 * <p>An island id is a fresh uuid every time, so a cache that keeps an erased island never gives a
 * wrong answer. It simply never lets go. A server that has made a hundred thousand islands over a
 * year was carrying a hundred thousand material indexes, and a material index is a count per
 * material.
 *
 * <p>Several of these services were written with a method to forget an island and nothing ever
 * called it. This checks the other half: that the wiring says so out loud.
 */
class EveryPerIslandCacheSaysHowToForgetTest {

    private static final Path CORE_SERVICES = Path.of("../core/src/main/java/com/uxplima/uxmskyblock/core/application");

    private static final Path WIRING = Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/bootstrap");

    /** A field that keeps something for every island, for as long as the server runs. */
    private static final Pattern PER_ISLAND_CACHE = Pattern.compile("private final (?:\\w+\\.)*\\w*Map<IslandId,");

    /**
     * Services whose per island map is not a cache, with the reason.
     *
     * <p>The mutation lock gives its own lock back when nobody wants it, counted rather than
     * cleared. The recycle, membership and alliance maps hold work that is in flight, and each entry
     * is removed when that work ends.
     *
     * <p>The bankruptcy and quarantine services were on this list on the same reasoning, and it had
     * stopped being true: each of them answers a question on the movement path and remembers the
     * answer, so each holds an entry for every island anybody has walked on, healthy or not. Both
     * offer a way to forget one now, and both are checked here like the rest.
     */
    private static final List<String> NOT_A_CACHE = List.of(
            "island/IslandMutationLock.java",
            "recycle/IslandRecycleService.java",
            "membership/IslandMembershipService.java",
            "alliance/IslandAllianceService.java");

    @Test
    @DisplayName("Every service that keeps something per island offers a way to forget one")
    void everyCacheOffersAWayToForget() throws IOException {
        TreeSet<String> offences = new TreeSet<>();
        int cachesRead = 0;

        try (Stream<Path> files = Files.walk(CORE_SERVICES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String path = file.toString().replace('\\', '/');
                if (NOT_A_CACHE.stream().anyMatch(path::endsWith)) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = PER_ISLAND_CACHE.matcher(source);
                if (!matcher.find()) {
                    continue;
                }
                cachesRead++;
                if (!source.contains("(IslandId islandId) {") || !source.contains(".remove(islandId)")) {
                    offences.add(
                            file.getFileName() + " keeps something for every island and offers no way to forget one");
                }
            }
        }

        assertThat(cachesRead).describedAs("the guard really found the caches").isGreaterThan(2);
        assertThat(offences).isEmpty();
    }

    @Test
    @DisplayName("Every one of those ways is registered with the wiring, not just written")
    void everyWayIsRegistered() throws IOException {
        String wiring = readWiring();

        assertThat(wiring)
                .describedAs("the upgrade tiers must be let go when an island is erased")
                .contains("invalidateCache");
        assertThat(wiring)
                .describedAs("the block and spawner counts must be let go")
                .contains("clearIsland");
        assertThat(wiring)
                .describedAs("the material index the worth is computed from must be let go")
                .contains("forgetIsland");
        assertThat(wiring)
                .describedAs("the generated dimensions must be let go")
                .contains("resetIslandDimensions");
        assertThat(wiring)
                .describedAs("the three that answer a question on the movement path and remember it")
                .contains("temporaryAccessService::forgetIsland")
                .contains("antiAbuseService()::forgetIsland")
                .contains("bankruptcyService()::forgetIsland");
        assertThat(wiring)
                .describedAs("the mode each island plays, kept for the missions that name their modes")
                .contains("missionService()::forgetIsland");
        assertThat(wiring)
                .describedAs("the game modes that answer a step from memory: the chunks and the acid seas")
                .contains("chunkBlockWiring.service()::forget")
                .contains("acidIslandWiring.service()::forget")
                .contains("boxedWiring.service()::forget")
                .contains("poseidonWiring.service()::forget")
                .contains("strangerRealmsWiring.service()::forget")
                .contains("parkourWiring.service()::forget")
                .contains("brixWiring.service()::forget");
    }

    @Test
    @DisplayName("A cache keyed by something other than an island id is registered too")
    void aCacheKeyedByAStringIsRegisteredToo() throws IOException {
        // The temporary access grants are kept per root, and a root is a type and a key rather than
        // an IslandId, so the scan above cannot see the map at all. It is still one entry per island
        // anybody has touched a block on, so it is named here by hand.
        String service = Files.readString(
                Path.of("../core/src/main/java/com/uxplima/uxmskyblock/core/application/access/"
                        + "TemporaryAccessService.java"),
                StandardCharsets.UTF_8);

        assertThat(service)
                .describedAs("the grants kept per root must be let go when the island is erased")
                .contains("public void forgetIsland(IslandId islandId)");
    }

    @Test
    @DisplayName("Erasing an island is what says it happened")
    void erasingAnIslandSaysSo() throws IOException {
        String recycle = Files.readString(
                Path.of("../core/src/main/java/com/uxplima/uxmskyblock/core/application/recycle/"
                        + "IslandRecycleService.java"),
                StandardCharsets.UTF_8);

        assertThat(recycle)
                .describedAs("the reset that erases an island is the moment every cache should let go")
                .contains("cacheEviction.forget(islandId)");
    }

    private static String readWiring() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(WIRING)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return all.toString();
    }
}
