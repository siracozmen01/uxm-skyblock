package com.uxplima.uxmskyblock.bukkit.boxed;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.BoxedBuy;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.requirement.LinePayment;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxSide;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.requirement.Requirement;

/**
 * {@code /is boxbuy} and {@code /is boxside}: a Boxed island buying growth with the lines {@code buy} of
 * {@code modules/boxed.conf} asks, and choosing the side its box grows toward.
 *
 * <p>Any member buys for their island, paying from their own purse or, with a role that may spend from it, the island
 * bank. Only the owner, or a role that may move the box, chooses its side.
 */
public final class BoxedShop {

    private static final Logger LOGGER = Logger.getLogger(BoxedShop.class.getName());

    /** Where a purchase's lines are said from, line by line. */
    static final String LINES = "boxed.buy.requires";

    private final BoxedService service;
    private final BoxedBuy buy;
    private final LinePayment payment;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private final Function<ProfileId, Optional<IslandId>> islandOf;
    private final Function<IslandId, Optional<Island>> islands;
    private final Consumer<IslandId> boxChanged;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final InteractionEffectPlayer effectPlayer;
    private final InteractionEffects effects;
    private final Set<IslandId> buying = ConcurrentHashMap.newKeySet();

    /**
     * @param islandOf the island a profile plays on, read from storage
     * @param islands an island, read from storage
     */
    @SuppressWarnings("TooManyParameters")
    public BoxedShop(
            BoxedService service,
            BoxedBuy buy,
            LinePayment payment,
            Function<UUID, Optional<ProfileId>> activeProfile,
            Function<ProfileId, Optional<IslandId>> islandOf,
            Function<IslandId, Optional<Island>> islands,
            Consumer<IslandId> boxChanged,
            SchedulerPort scheduler,
            Messages messages,
            InteractionEffectPlayer effectPlayer,
            InteractionEffects effects) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.buy = Objects.requireNonNull(buy, "buy must not be null");
        this.payment = Objects.requireNonNull(payment, "payment must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
        this.islandOf = Objects.requireNonNull(islandOf, "islandOf must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.boxChanged = Objects.requireNonNull(boxChanged, "boxChanged must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.effectPlayer = Objects.requireNonNull(effectPlayer, "effectPlayer must not be null");
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
    }

    /** {@code player} buys growth for their Boxed island. */
    public void buy(Player player) {
        if (!buy.on()) {
            messages.send(player, "boxed.buy.off");
            return;
        }
        Optional<ProfileId> profile = activeProfile.apply(player.getUniqueId());
        if (profile.isEmpty()) {
            messages.send(player, "boxed.buy.no_box");
            return;
        }
        scheduler.async(() -> {
            try {
                Optional<Island> island = islandOf.apply(profile.get()).flatMap(islands);
                String refused = refusal(island);
                if (refused != null) {
                    tell(player, refused);
                    return;
                }
                Island buyer = island.orElseThrow();
                if (!buying.add(buyer.id())) {
                    return;
                }
                payment.pay(buyer, player, buy.lines(), paidFor(player, buyer.id()), () -> buying.remove(buyer.id()));
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Growth bought by " + player.getName() + " could not be sold.");
            }
        });
    }

    /** Why the island cannot buy now, or nothing. Off the main thread: it counts the purchases. */
    private @org.jspecify.annotations.Nullable String refusal(Optional<Island> island) {
        if (island.isEmpty() || !service.isBoxed(island.get().id())) {
            return "boxed.buy.no_box";
        }
        IslandId islandId = island.get().id();
        if (service.reach(islandId).orElse(0) >= service.rules().maxRadius()) {
            return "boxed.buy.full";
        }
        if (buy.most() > 0 && service.bought(islandId) >= buy.most()) {
            return "boxed.buy.most";
        }
        return null;
    }

    private LinePayment.Outcome paidFor(Player player, IslandId islandId) {
        return new LinePayment.Outcome() {
            @Override
            public void missing(List<Requirement> lines) {
                messages.send(player, "boxed.buy.missing");
                for (Requirement line : lines) {
                    Component said = LinePayment.lineOf(messages, player, line, LINES);
                    if (said != null) {
                        player.sendMessage(said);
                    }
                }
            }

            @Override
            public void paid() {
                service.buy(islandId, buy.blocks()).ifPresent(grown -> {
                    boxChanged.accept(islandId);
                    String across = Integer.toString(grown.toRadius() * 2 + 1);
                    String blocks = Integer.toString(grown.blocks());
                    scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> {
                        messages.send(
                                player,
                                "boxed.bought",
                                Placeholder.unparsed("blocks", blocks),
                                Placeholder.unparsed("size", across));
                        effectPlayer.fire(effects, "boxed-grew", player, Map.of("blocks", blocks, "size", across));
                    });
                });
            }
        };
    }

    /** {@code player} says which side their island's box grows toward; with no word, what it grows toward now. */
    public void side(Player player, @org.jspecify.annotations.Nullable String word) {
        Optional<BoxSide> side = word == null ? Optional.empty() : BoxSide.named(word);
        if (word != null && side.isEmpty()) {
            messages.send(player, "boxed.side.unknown", Placeholder.unparsed("sides", words()));
            return;
        }
        Optional<ProfileId> profile = activeProfile.apply(player.getUniqueId());
        if (profile.isEmpty()) {
            messages.send(player, "boxed.buy.no_box");
            return;
        }
        scheduler.async(() -> {
            try {
                Optional<Island> island = islandOf.apply(profile.get()).flatMap(islands);
                if (island.isEmpty() || !service.isBoxed(island.get().id())) {
                    tell(player, "boxed.buy.no_box");
                    return;
                }
                IslandId islandId = island.get().id();
                if (side.isEmpty()) {
                    tell(player, "boxed.side.now", service.side(islandId));
                    return;
                }
                boolean may = island.get().isOwner(profile.get())
                        || island.get().hasPermission(profile.get(), IslandPermission.BOX_MOVE);
                if (!may) {
                    tell(player, "boxed.side.not_allowed");
                    return;
                }
                service.choose(islandId, side.get());
                tell(player, "boxed.side.chosen", side.get());
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "The side " + player.getName() + " chose could not be kept.");
            }
        });
    }

    /** Every side's word, as a player types it. */
    static String words() {
        return Arrays.stream(BoxSide.values()).map(BoxSide::word).collect(Collectors.joining(", "));
    }

    private void tell(Player player, String key) {
        scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> messages.send(player, key));
    }

    private void tell(Player player, String key, BoxSide side) {
        scheduler.onEntity(
                PlayerUuid.of(player.getUniqueId()),
                () -> messages.send(
                        player,
                        key,
                        Placeholder.unparsed("side", messages.named(player, "boxed.sides", side.word(), side.word())),
                        Placeholder.unparsed("sides", words())));
    }
}
