package com.uxplima.uxmskyblock.bukkit.state;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

/**
 * A player's advancements as the server keeps them. The recipe advancements are left alone: they unlock the same
 * recipes in every group, and there are hundreds of them.
 */
public final class ServerAdvancements implements PlayerStates.Advancements {

    private static final char BETWEEN = '|';

    @Override
    public Set<String> earned(Player player) {
        Set<String> earned = new LinkedHashSet<>();
        for (Iterator<Advancement> all = Bukkit.advancementIterator(); all.hasNext(); ) {
            Advancement advancement = all.next();
            if (isRecipe(advancement)) {
                continue;
            }
            String key = advancement.getKey().asString();
            for (String criterion : player.getAdvancementProgress(advancement).getAwardedCriteria()) {
                earned.add(key + BETWEEN + criterion);
            }
        }
        return earned;
    }

    @Override
    public void earn(Player player, Set<String> criteria) {
        for (Iterator<Advancement> all = Bukkit.advancementIterator(); all.hasNext(); ) {
            Advancement advancement = all.next();
            if (isRecipe(advancement)) {
                continue;
            }
            String key = advancement.getKey().asString();
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            for (String criterion : Set.copyOf(progress.getAwardedCriteria())) {
                if (!criteria.contains(key + BETWEEN + criterion)) {
                    progress.revokeCriteria(criterion);
                }
            }
            for (String criterion : progress.getRemainingCriteria()) {
                if (criteria.contains(key + BETWEEN + criterion)) {
                    progress.awardCriteria(criterion);
                }
            }
        }
    }

    private static boolean isRecipe(Advancement advancement) {
        return advancement.getKey().getKey().startsWith("recipes/");
    }
}
