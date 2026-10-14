package com.uxplima.uxmskyblock.bukkit.top;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;

/**
 * Draws a display in the world, in the server's own language since everyone reads it: a sign's four lines, a head
 * turned to the island owner's face, or a line of text above the block it stands on, marked so the next draw finds
 * it. It runs on the thread that owns the block.
 */
public final class BukkitTopPainter implements TopPainter {

    /** The mark on a text display, naming the block it stands for. */
    static final NamespacedKey MARK = new NamespacedKey("uxmskyblock", "top-display");

    private static final double TEXT_RISE = 1.5;

    private final Messages messages;

    public BukkitTopPainter(Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    @Override
    public void paint(TopDisplay display, Optional<Shown> shown) {
        World world = Bukkit.getWorld(display.world());
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(display.x(), display.y(), display.z());
        TagResolver words = TagResolver.resolver(
                Placeholder.unparsed("position", Integer.toString(display.rank())),
                Placeholder.unparsed("board", shown.map(Shown::board).orElse(boardName(display))),
                Placeholder.unparsed("island", shown.map(Shown::island).orElse("")),
                Placeholder.unparsed("owner", shown.map(Shown::ownerName).orElse("")),
                Placeholder.unparsed("score", shown.map(Shown::score).orElse("")));
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
                Component text = messages.render(
                        Audience.empty(), shown.isPresent() ? "top_display.text" : "top_display.text_empty", words);
                List<TextDisplay> lines = linesAt(world, display);
                if (!lines.isEmpty()) {
                    lines.get(0).text(text);
                    return;
                }
                world.spawn(textSpot(world, display), TextDisplay.class, line -> {
                    line.text(text);
                    line.setBillboard(Display.Billboard.CENTER);
                    line.setAlignment(TextDisplay.TextAlignment.CENTER);
                    line.getPersistentDataContainer().set(MARK, PersistentDataType.STRING, display.place());
                });
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
            case TEXT -> linesAt(world, display).forEach(TextDisplay::remove);
        }
    }

    private String boardName(TopDisplay display) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(messages.renderPlain(
                        Audience.empty(),
                        "leaderboard.category_" + display.board().name().toLowerCase(java.util.Locale.ROOT)));
    }

    private static Location textSpot(World world, TopDisplay display) {
        return new Location(world, display.x() + 0.5, display.y() + TEXT_RISE, display.z() + 0.5);
    }

    private static List<TextDisplay> linesAt(World world, TopDisplay display) {
        return world.getNearbyEntitiesByType(TextDisplay.class, textSpot(world, display), 0.5).stream()
                .filter(line ->
                        display.place().equals(line.getPersistentDataContainer().get(MARK, PersistentDataType.STRING)))
                .toList();
    }
}
