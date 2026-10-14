package com.uxplima.uxmskyblock.bukkit.poseidon;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;

import com.uxplima.uxmlib.bedrock.BedrockButton;
import com.uxplima.uxmlib.bedrock.BedrockScreen;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * The lore of the sea, which guides a Poseidon island's players: a written book on Java, and a form a
 * page at a time on Bedrock. Every page is a line of {@code poseidon.lore.pages} in the reader's
 * language file, so the operator writes as many as they like, in every language they ship.
 *
 * <p>The book follows the story: after those pages come the chapters of the story missions, in the order the
 * operator lists them, each the lines of {@code poseidon.lore.chapters.<mission>} once the reader has finished
 * that mission. The first chapter not yet earned is a page that names its mission, and nothing after it shows.
 */
public final class PoseidonLore {

    private static final Logger LOGGER = Logger.getLogger(PoseidonLore.class.getName());

    private final Messages messages;
    private final SchedulerPort scheduler;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private final Function<ProfileId, GameModeType> modeOf;
    private final Predicate<Player> isBedrock;
    private final BedrockScreen screen;
    private final BiConsumer<Player, Book> openBook;
    private volatile Story story = Story.NONE;

    /**
     * The story the book follows.
     *
     * @param chapters the missions whose chapters the book holds, in their order
     * @param finished the missions a profile has finished on its island, which reads the database
     * @param named a mission's name as the missions file writes it, empty for a mission it does not hold
     */
    public record Story(
            List<String> chapters,
            Function<ProfileId, java.util.Set<String>> finished,
            Function<String, Optional<String>> named) {

        static final Story NONE = new Story(List.of(), profile -> java.util.Set.of(), mission -> Optional.empty());

        public Story {
            chapters = List.copyOf(chapters);
            Objects.requireNonNull(finished, "finished must not be null");
            Objects.requireNonNull(named, "named must not be null");
        }
    }

    /**
     * @param modeOf the mode a profile's island plays, which may read the database
     * @param openBook opens a written book for a Java player
     */
    public PoseidonLore(
            Messages messages,
            SchedulerPort scheduler,
            Function<UUID, Optional<ProfileId>> activeProfile,
            Function<ProfileId, GameModeType> modeOf,
            Predicate<Player> isBedrock,
            BedrockScreen screen,
            BiConsumer<Player, Book> openBook) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
        this.modeOf = Objects.requireNonNull(modeOf, "modeOf must not be null");
        this.isBedrock = Objects.requireNonNull(isBedrock, "isBedrock must not be null");
        this.screen = Objects.requireNonNull(screen, "screen must not be null");
        this.openBook = Objects.requireNonNull(openBook, "openBook must not be null");
    }

    /** Makes the book follow the story missions. */
    public void follow(Story followed) {
        this.story = Objects.requireNonNull(followed, "followed must not be null");
    }

    /** Opens the lore for a player whose island is a Poseidon island, or says why it does not. */
    public void open(Player player) {
        UUID id = player.getUniqueId();
        Story followed = story;
        scheduler.async(() -> {
            boolean poseidon;
            java.util.Set<String> finished = java.util.Set.of();
            try {
                Optional<ProfileId> profile = activeProfile.apply(id);
                poseidon = profile.map(one -> modeOf.apply(one) == GameModeType.POSEIDON)
                        .orElse(false);
                if (poseidon && !followed.chapters().isEmpty()) {
                    finished = followed.finished().apply(profile.get());
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "The island mode of " + id + " could not be read.");
                poseidon = false;
            }
            boolean reads = poseidon;
            java.util.Set<String> earned = finished;
            scheduler.onEntity(PlayerUuid.of(id), () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!reads) {
                    messages.send(player, "poseidon.not_poseidon");
                    return;
                }
                List<Component> pages = pages(player, followed, earned);
                if (isBedrock.test(player)) {
                    showPage(player, pages, 0);
                } else {
                    showBook(player, pages);
                }
            });
        });
    }

    /**
     * The pages {@code player} reads: those that open the book, then each chapter earned, then the page of the first
     * chapter still to earn. A chapter whose mission the missions file does not hold is skipped.
     */
    List<Component> pages(Player player, Story followed, java.util.Set<String> earned) {
        List<Component> pages = new java.util.ArrayList<>(messages.renderAll(player, "poseidon.lore.pages"));
        for (String chapter : followed.chapters()) {
            Optional<String> name = followed.named().apply(chapter);
            if (name.isEmpty()) {
                continue;
            }
            if (!earned.contains(chapter)) {
                pages.add(messages.render(
                        player,
                        "poseidon.lore.locked",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                                "mission", messages.words(player, name.get()))));
                break;
            }
            pages.addAll(messages.renderAll(player, "poseidon.lore.chapters." + chapter));
        }
        return pages;
    }

    private void showBook(Player player, List<Component> pages) {
        if (pages.isEmpty()) {
            messages.send(player, "poseidon.lore_empty");
            return;
        }
        openBook.accept(
                player,
                Book.book(
                        messages.renderPlain(player, "poseidon.lore.title"),
                        messages.renderPlain(player, "poseidon.lore.author"),
                        pages));
    }

    /** One page as a Bedrock form, with a button back, a button on while there is more, and one to close. */
    void showPage(Player player, List<Component> pages, int page) {
        if (pages.isEmpty()) {
            messages.send(player, "poseidon.lore_empty");
            return;
        }
        int shown = Math.clamp(page, 0, pages.size() - 1);
        List<BedrockButton> buttons = new java.util.ArrayList<>();
        List<Runnable> actions = new java.util.ArrayList<>();
        if (shown > 0) {
            buttons.add(new BedrockButton(text(player, "poseidon.lore.previous"), null));
            actions.add(() -> showPage(player, pages, shown - 1));
        }
        if (shown < pages.size() - 1) {
            buttons.add(new BedrockButton(text(player, "poseidon.lore.next"), null));
            actions.add(() -> showPage(player, pages, shown + 1));
        }
        buttons.add(new BedrockButton(text(player, "poseidon.lore.close"), null));
        actions.add(() -> {});
        screen.sendSimpleForm(player, text(player, "poseidon.lore.title"), legacy(pages.get(shown)), buttons, index -> {
            if (index >= 0 && index < actions.size()) {
                actions.get(index).run();
            }
        });
    }

    /** A catalogue line in the reader's language, flattened for a form. */
    private String text(Player player, String key) {
        return legacy(messages.renderPlain(player, key));
    }

    /** A Floodgate form takes a string, so a rendered line is flattened for it. */
    private static String legacy(Component line) {
        return com.uxplima.uxmskyblock.bukkit.bedrock.FormText.of(line);
    }
}
