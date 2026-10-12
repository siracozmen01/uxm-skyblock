package com.uxplima.uxmskyblock.bukkit.parkour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourPort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** {@code /isadmin parkour resettimes}: an operator forgets the runs on a course, of every runner or of one. */
class AnOperatorForgetsTheRunsOnACourseTest extends MockBukkitHarness {

    private static final IslandId COURSE = IslandId.of(UUID.randomUUID());
    private static final IslandId ORDINARY = IslandId.of(UUID.randomUUID());
    private static final UUID ADA = UUID.randomUUID();

    private final List<String> cleared = new ArrayList<>();
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private PlayerMock operator;

    @BeforeEach
    void setUp() {
        ParkourService service = new ParkourService(new ParkourPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.of(COURSE);
            }

            @Override
            public boolean exists(IslandId course) {
                return course.equals(COURSE);
            }

            @Override
            public void add(IslandId course) {}

            @Override
            public OptionalLong best(IslandId course, PlayerUuid runner) {
                return OptionalLong.empty();
            }

            @Override
            public void finish(IslandId course, PlayerUuid runner, long millis) {}

            @Override
            public int clear(IslandId course, Optional<PlayerUuid> runner) {
                cleared.add(
                        course + " " + runner.map(one -> one.value().toString()).orElse("everyone"));
                return runner.isPresent() ? 1 : 4;
            }

            @Override
            public List<ParkourPort.Best> top(IslandId course, int limit) {
                return List.of();
            }

            @Override
            public List<ParkourPort.Runs> mostRun(int limit) {
                return List.of();
            }
        });
        service.prime();
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        Map<String, UUID> seen = Map.of("Ada", ADA);
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(new ModeAdminCommands(
                        List.of(new ParkourAdmin(service, name -> Optional.ofNullable(seen.get(name)))),
                        mock(IslandLocationService.class),
                        null,
                        scheduler,
                        Messages.bundled())
                .build(null, (source, tag) -> true)
                .orElseThrow());
        operator = server.addPlayer("Op");
        operator.setOp(true);
    }

    private String run(String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(operator);
        dispatcher.execute(line, source);
        StringBuilder said = new StringBuilder();
        Component next;
        while ((next = operator.nextComponentMessage()) != null) {
            said.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(next))
                    .append('\n');
        }
        return said.toString();
    }

    @Test
    @DisplayName("Every runner's runs are forgotten, or one runner's, named as the server knows them")
    void forgotten() throws Exception {
        assertThat(run("isadmin parkour resettimes " + COURSE.value())).contains("4 runners start again");
        assertThat(run("isadmin parkour resettimes " + COURSE.value() + " Ada")).contains("runs of Ada");
        assertThat(cleared).containsExactly(COURSE + " everyone", COURSE + " " + ADA);
    }

    @Test
    @DisplayName("A runner nobody has seen and an island that is no course are refused, and nothing is forgotten")
    void refused() throws Exception {
        assertThat(run("isadmin parkour resettimes " + COURSE.value() + " Nobody"))
                .contains("No player called Nobody");
        assertThat(run("isadmin parkour resettimes " + ORDINARY.value())).contains("not a Parkour course");
        assertThat(cleared).isEmpty();
    }
}
