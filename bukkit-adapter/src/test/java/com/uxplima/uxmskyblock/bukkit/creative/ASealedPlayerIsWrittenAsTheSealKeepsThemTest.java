package com.uxplima.uxmskyblock.bukkit.creative;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A sealed player is written as the seal keeps them, and sealed only once their session holds their state.
 *
 * <p>The seal lived on the player while the session wrote what they held in the creative place. A crash gave
 * them back the place's items in creative, and the seal from the player's own save gave back what it kept as
 * well. At the join a creative place sealed what the save held, before the session put the player's own state
 * on them, and gave that back on the way out.
 */
class ASealedPlayerIsWrittenAsTheSealKeepsThemTest extends MockBukkitHarness {

    private final List<ItemStack[]> written = new ArrayList<>();
    private final InventoryCodec codec = new InventoryCodec() {
        @Override
        public byte[] write(ItemStack[] items) {
            written.add(items.clone());
            return new byte[] {(byte) (written.size() - 1)};
        }

        @Override
        public ItemStack[] read(byte[] bytes) {
            return written.get(bytes[0]);
        }
    };

    @SuppressWarnings("NullAway.Init")
    private PlayerMock builder;

    @BeforeEach
    void setUpBuilder() {
        builder = createPlayer("Builder");
        builder.setGameMode(GameMode.SURVIVAL);
    }

    @Test
    @DisplayName("A snapshot of a sealed player holds what they brought, never what the place gave them")
    void theSnapshotHoldsWhatWasBrought() {
        builder.getInventory().setItem(0, new ItemStack(Material.IRON_PICKAXE));
        builder.setLevel(12);
        builder.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 600, 1));
        new SealedInventory(codec, "brix").seal(builder);
        builder.setGameMode(GameMode.CREATIVE);
        builder.setAllowFlight(true);
        builder.setLevel(0);
        builder.getInventory().setItem(0, new ItemStack(Material.BEDROCK, 64));
        builder.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 600, 9));

        ProfileInventoryRecord record =
                BukkitInventorySerializer.snapshotPlayer(builder, new ProfileId(UUID.randomUUID()), 3L);

        assertThat(BukkitInventorySerializer.deserializeItemStacks(record.inventoryNbt()))
                .contains(new ItemStack(Material.IRON_PICKAXE))
                .doesNotContain(new ItemStack(Material.BEDROCK, 64));
        assertThat(record.gamemode()).isEqualTo("SURVIVAL");
        assertThat(record.flightAllowed()).isFalse();
        assertThat(record.experiencePoints())
                .describedAs("twelve levels, not the place's none")
                .isEqualTo(BukkitInventorySerializer.snapshotPlayer(levelled(12), record.profileId(), 3L)
                        .experiencePoints());
        assertThat(BukkitInventorySerializer.deserializePotionEffects(record.activePotionEffectsNbt()))
                .extracting(PotionEffect::getType)
                .containsExactly(PotionEffectType.SPEED);

        new SealedInventory(codec, "brix").unseal(builder);
        ProfileInventoryRecord after = BukkitInventorySerializer.snapshotPlayer(builder, record.profileId(), 3L);
        assertThat(BukkitInventorySerializer.deserializeItemStacks(after.inventoryNbt()))
                .describedAs("unsealed, the player is written as they stand")
                .contains(new ItemStack(Material.IRON_PICKAXE));
    }

    @Test
    @DisplayName(
            "A player is sealed only while their session holds their state, and a session's start lets go of a seal")
    void sealedOnlyInPlay() {
        AtomicBoolean inPlay = new AtomicBoolean(false);
        SealedInventory plot = new SealedInventory(codec, "brix", player -> inPlay.get());
        builder.getInventory().setItem(0, new ItemStack(Material.IRON_PICKAXE));

        assertThat(plot.seal(builder)).isFalse();
        assertThat(SealedInventory.holds(builder)).isFalse();
        assertThat(builder.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.IRON_PICKAXE));

        inPlay.set(true);
        assertThat(plot.seal(builder)).isTrue();
        assertThat(SealedInventory.holds(builder)).isTrue();

        SealedInventory.forget(builder);
        assertThat(SealedInventory.holds(builder)).isFalse();
        assertThat(SealedInventory.keptFor(builder)).isNull();
    }

    private PlayerMock levelled(int level) {
        PlayerMock other = createPlayer("Levelled");
        other.setLevel(level);
        return other;
    }
}
