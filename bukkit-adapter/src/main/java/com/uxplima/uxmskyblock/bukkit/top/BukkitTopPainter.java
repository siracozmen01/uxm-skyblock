package com.uxplima.uxmskyblock.bukkit.top;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmlib.packet.display.HologramAppearance;
import com.uxplima.uxmlib.packet.display.HologramPackets;
import com.uxplima.uxmlib.packet.display.PacketHologram;
import com.uxplima.uxmlib.scheduler.Scheduler;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import org.jspecify.annotations.Nullable;

/**
 * Draws a display: a sign's four lines and a head turned to the island owner's face, which are blocks everyone shares
 * and so read in the server's own language, or a line of text above the block it stands on, sent to each reader in
 * their own words and never spawned. It runs on the thread that owns the block.
 */
public final class BukkitTopPainter implements TopPainter {

    private static final double TEXT_RISE = 1.5;

    private static final HologramAppearance LOOK = HologramAppearance.defaults()
            .withBillboard(Display.Billboard.CENTER)
            .withAlignment(TextDisplay.TextAlignment.CENTER);

    private final Messages messages;
    private final Scheduler scheduler;
    private final HologramPackets packets;

    /** Each text display up, by its place. */
    private final Map<String, Line> lines = new ConcurrentHashMap<>();

    /** A text display up: what it shows, which the next draw swaps and every frame reads, and the hologram. */
    private static final class Line {

        private volatile Optional<Shown> shown;
        private @Nullable PacketHologram hologram;

        private Line(Optional<Shown> shown) {
            this.shown = shown;
        }

        private void takeDown() {
            PacketHologram up = hologram;
            if (up != null) {
                up.remove();
            }
        }
    }

    public BukkitTopPainter(Messages messages, Scheduler scheduler, HologramPackets packets) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.packets = Objects.requireNonNull(packets, "packets must not be null");
    }

    @Override
    public void paint(TopDisplay display, Optional<Shown> shown) {
        World world = Bukkit.getWorld(display.world());
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(display.x(), display.y(), display.z());
        TagResolver words = words(Audience.empty(), display, shown);
        switch (display.kind()) {
            case SIGN -> {
                BlockState state = block.getState();
                if (state instanceof Sign sign) {
                    String lines = shown.isPresent() ? "top_display.sign" : "top_display.sign_empty";
                    for (int line = 0; line < 4; line++) {
                        sign.getSide(Side.FRONT)
                                .line(line, messages.render(Audience.empty(), lines + ".line" + (line + 1), words));
                    }
                    sign.update(true, false);
                }
            }
            case HEAD -> {
                BlockState state = block.getState();
                // A place nobody holds yet leaves the head the face it last wore.
                if (state instanceof Skull head
                        && shown.isPresent()
                        && shown.get().owner() != null) {
                    var profile = io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile()
                            .uuid(Objects.requireNonNull(shown.get().owner()));
                    if (!shown.get().ownerName().isEmpty()) {
                        profile.name(shown.get().ownerName());
                    }
                    head.setProfile(profile.build());
                    head.update(true, false);
                }
            }
            case TEXT -> {
                Line held = lines.get(display.place());
                if (held != null) {
                    held.shown = shown;
                    return;
                }
                Line line = new Line(shown);
                line.hologram = PacketHologram.show(
                        packets,
                        scheduler,
                        textSpot(world, display),
                        LOOK,
                        viewer -> List.of(text(viewer, display, line)));
                lines.put(display.place(), line);
            }
        }
    }

    @Override
    public void clear(TopDisplay display) {
        World world = Bukkit.getWorld(display.world());
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(display.x(), display.y(), display.z());
        switch (display.kind()) {
            case SIGN -> {
                if (block.getState() instanceof Sign sign) {
                    for (int line = 0; line < 4; line++) {
                        sign.getSide(Side.FRONT).line(line, Component.empty());
                    }
                    sign.update(true, false);
                }
            }
            case HEAD -> {
                // A head keeps the face it last wore; it is the operator's block.
            }
            case TEXT -> {
                Line gone = lines.remove(display.place());
                if (gone != null) {
                    gone.takeDown();
                }
            }
        }
    }

    /** How many text displays are up, which a test reads. */
    int textsUp() {
        return lines.size();
    }

    private Component text(Player viewer, TopDisplay display, Line line) {
        Optional<Shown> shown = line.shown;
        return messages.render(
                viewer,
                shown.isPresent() ? "top_display.text" : "top_display.text_empty",
                words(viewer, display, shown));
    }

    /** The words of {@code display} as {@code reader} reads them. */
    private TagResolver words(Audience reader, TopDisplay display, Optional<Shown> shown) {
        Optional<Words> said = shown.map(it -> it.in(reader));
        return TagResolver.resolver(
                Placeholder.unparsed("position", Integer.toString(display.rank())),
                Placeholder.unparsed("board", said.map(Words::board).orElseGet(() -> boardName(reader, display))),
                Placeholder.unparsed("island", said.map(Words::island).orElse("")),
                Placeholder.unparsed("owner", shown.map(Shown::ownerName).orElse("")),
                Placeholder.unparsed("score", said.map(Words::score).orElse("")));
    }

    private String boardName(Audience reader, TopDisplay display) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(messages.renderPlain(
                        reader, "leaderboard.category_" + display.board().name().toLowerCase(java.util.Locale.ROOT)));
    }

    private static Location textSpot(World world, TopDisplay display) {
        return new Location(world, display.x() + 0.5, display.y() + TEXT_RISE, display.z() + 0.5);
    }
}
