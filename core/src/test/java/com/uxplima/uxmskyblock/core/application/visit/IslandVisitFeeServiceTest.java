package com.uxplima.uxmskyblock.core.application.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandVisitFeeServiceTest {

    private final Map<IslandId, Long> kept = new HashMap<>();
    private final IslandVisitFeeService fees = new IslandVisitFeeService(
            new VisitFeeStoragePort() {
                @Override
                public long feeOf(IslandId island) {
                    return kept.getOrDefault(island, 0L);
                }

                @Override
                public void setFee(IslandId island, long minorUnits) {
                    kept.put(island, minorUnits);
                }
            },
            new IslandAccessService());
    private final ProfileId owner = new ProfileId(UUID.randomUUID());
    private final ProfileId member = new ProfileId(UUID.randomUUID());
    private final Island island = Island.create(
                    IslandId.of(UUID.randomUUID()),
                    IslandBounds.fromCenterAndRadius(0, 0, 32),
                    new PlayerUuid(UUID.randomUUID()),
                    owner,
                    Instant.now())
            .addMember(new IslandMember(new PlayerUuid(UUID.randomUUID()), member, IslandRole.MEMBER, Instant.now()));

    @Test
    @DisplayName("The owner sets a fee from nothing up to the most, and it is read back")
    void theOwnerSetsIt() {
        assertThat(fees.feeOf(island.id())).isZero();
        fees.setFee(island, owner, 2_500, 2_500);
        assertThat(fees.feeOf(island.id())).isEqualTo(2_500);
        fees.setFee(island, owner, 0, 2_500);
        assertThat(fees.feeOf(island.id())).isZero();
    }

    @Test
    @DisplayName("A fee above the most or below nothing is refused and says the most; nothing is kept")
    void outOfRange() {
        assertThatThrownBy(() -> fees.setFee(island, owner, 2_501, 2_500))
                .isInstanceOfSatisfying(
                        VisitFeeOutOfRangeException.class,
                        refused -> assertThat(refused.most()).isEqualTo(2_500));
        assertThatThrownBy(() -> fees.setFee(island, owner, -1, 2_500)).isInstanceOf(VisitFeeOutOfRangeException.class);
        assertThatThrownBy(() -> fees.setFee(island, owner, 1, -5))
                .isInstanceOfSatisfying(
                        VisitFeeOutOfRangeException.class,
                        refused -> assertThat(refused.most()).isZero());
        assertThat(kept).isEmpty();
    }

    @Test
    @DisplayName("A member whose role may not change the settings, and a stranger, are refused")
    void othersAreRefused() {
        assertThatThrownBy(() -> fees.setFee(island, member, 100, 2_500)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> fees.setFee(island, new ProfileId(UUID.randomUUID()), 100, 2_500))
                .isInstanceOf(SecurityException.class);
        assertThat(kept).isEmpty();
    }
}
