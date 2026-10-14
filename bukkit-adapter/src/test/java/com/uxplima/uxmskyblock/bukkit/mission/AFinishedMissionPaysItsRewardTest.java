package com.uxplima.uxmskyblock.bukkit.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionReward;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every mission showed crystals, money, island experience and commands as its reward, and the mission service had
 * nothing to pay them with: none was ever handed out.
 */
class AFinishedMissionPaysItsRewardTest {

    private final IslandBankService bank = mock(IslandBankService.class);
    private final IslandStoragePort islands = mock(IslandStoragePort.class);
    private final SchedulerPort scheduler = mock(SchedulerPort.class);
    private final List<String> ran = new ArrayList<>();
    private final List<String> threads = new ArrayList<>();
    private final PlayerUuid player = new PlayerUuid(UUID.randomUUID());
    private final ProfileId profile = new ProfileId(UUID.randomUUID());
    private Island island = Island.create(
            IslandId.of(UUID.randomUUID()),
            IslandBounds.fromCenterAndRadius(0, 0, 32),
            new PlayerUuid(UUID.randomUUID()),
            new ProfileId(UUID.randomUUID()),
            Instant.now());
    private double boost = 1.0;
    private String name = "Ann";

    @BeforeEach
    void setUp() {
        island = island.addMember(new IslandMember(player, profile, IslandRole.MEMBER, Instant.now()));
        when(islands.findIslandById(island.id())).thenReturn(Optional.of(island));
        when(bank.move(any(), any(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(mock(BankTransactionOutcome.Success.class));
        doAnswer(call -> {
                    threads.add("async");
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    threads.add("global");
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onGlobal(any(Runnable.class));
    }

    private MissionPayout payout() {
        return new MissionPayout(
                bank,
                islands,
                ServerNodeId.of("node-a"),
                id -> id.equals(island.id()) ? boost : 1.0,
                scheduler,
                uuid -> uuid.equals(player) ? Optional.of(name) : Optional.empty(),
                ran::add);
    }

    private static MissionDefinition paying(MissionReward reward) {
        return new MissionDefinition(
                MissionId.of("mine_stone"),
                MissionBranch.MINING,
                "Mine stone",
                "Mine some stone",
                MissionTriggerType.BLOCK_BREAK,
                "*",
                1L,
                reward);
    }

    @Test
    @DisplayName(
            "Money, crystals and experience go into the island bank off the thread, and the commands to the console")
    void theRewardIsPaid() {
        payout().dispatchReward(
                        island.id(),
                        profile,
                        paying(new MissionReward(10, 2_500, 150, List.of("/eco give %player% 5", "say %island%"))));

        String reason = "Mission reward: mine_stone";
        verify(bank).move(island.id(), player, "PRIMARY", 2_500L, reason, ServerNodeId.of("node-a"));
        verify(bank).move(island.id(), player, "crystals", 10L, reason, ServerNodeId.of("node-a"));
        verify(bank).move(island.id(), player, "exp", 150L, reason, ServerNodeId.of("node-a"));
        assertThat(ran).containsExactly("eco give Ann 5", "say " + island.id().value());
        assertThat(threads).containsExactly("async", "global");
    }

    @Test
    @DisplayName("A mission rewards booster makes the amounts more, and a part left out is not paid at all")
    void aBoosterMakesItMore() {
        boost = 1.5;
        payout().pay(island.id(), profile, paying(new MissionReward(0, 1_001, 10, List.of())));

        verify(bank).move(eq(island.id()), eq(player), eq("PRIMARY"), eq(1_502L), anyString(), any());
        verify(bank).move(eq(island.id()), eq(player), eq("exp"), eq(15L), anyString(), any());
        verify(bank, never()).move(any(), any(), eq("crystals"), anyLong(), anyString(), any());
        assertThat(threads).describedAs("no commands, nothing for the console").isEmpty();

        boost = 0.5;
        payout().pay(island.id(), profile, paying(new MissionReward(4, 0, 0, List.of())));
        verify(bank).move(eq(island.id()), eq(player), eq("crystals"), eq(4L), anyString(), any());
    }

    @Test
    @DisplayName("A finisher no longer on the island is paid as their profile, and a command naming them is not run")
    void aFinisherWhoLeft() {
        ProfileId gone = new ProfileId(UUID.randomUUID());
        payout().pay(island.id(), gone, paying(new MissionReward(0, 100, 0, List.of("give %player% dirt"))));

        verify(bank)
                .move(eq(island.id()), eq(PlayerUuid.of(gone.value())), eq("PRIMARY"), eq(100L), anyString(), any());
        assertThat(ran).describedAs("no name, nothing to put in safely").isEmpty();
    }

    @Test
    @DisplayName("A name a command cannot carry as one word runs none of the commands that name it, and the rest")
    void anUnsafeNameIsNotPutInACommand() {
        name = "Ann op Bob";
        payout().pay(
                        island.id(),
                        profile,
                        paying(new MissionReward(0, 0, 0, List.of("give %player% dirt", "say done"))));
        assertThat(ran).containsExactly("say done");

        ran.clear();
        name = ".Pocket_Ann";
        payout().pay(island.id(), profile, paying(new MissionReward(0, 0, 0, List.of("give %player% dirt"))));
        assertThat(ran).describedAs("a Bedrock name with its mark").containsExactly("give .Pocket_Ann dirt");
    }
}
