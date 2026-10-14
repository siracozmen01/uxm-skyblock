package com.uxplima.uxmskyblock.bukkit.stack;

import java.util.List;
import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;

/**
 * A stack's line is a text display above it, in the server's own language since everyone reads it, marked with the
 * place of its block so the next change finds it; the block given back drops where the stack stands.
 */
public final class TextDisplayStackWorld implements StackWorld {

    /** The mark on the line above a stack, naming the place of the block it belongs to. */
    static final NamespacedKey LINE = new NamespacedKey("uxmskyblock", "stack-line");

    private static final double LINE_RISE = 1.25;

    private final Messages messages;

    public TextDisplayStackWorld(Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    @Override
    public void giveBack(Block block, Player breaker) {
        if (!block.getDrops(breaker.getInventory().getItemInMainHand(), breaker).isEmpty()) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), new ItemStack(block.getType()));
        }
    }

    /** Shows {@code size} on the line above {@code block}, or takes the line away for a block of one. */
    @Override
    public void show(Block block, int size) {
        String place = block.getX() + "," + block.getY() + "," + block.getZ();
        Location above = block.getLocation().add(0.5, LINE_RISE, 0.5);
        List<TextDisplay> lines = block.getWorld().getNearbyEntitiesByType(TextDisplay.class, above, 0.5).stream()
                .filter(line -> place.equals(line.getPersistentDataContainer().get(LINE, PersistentDataType.STRING)))
                .toList();
        if (size <= 1) {
            lines.forEach(TextDisplay::remove);
            return;
        }
        var text = messages.render(
                Audience.empty(), "stacking.line", Placeholder.unparsed("size", Integer.toString(size)));
        if (!lines.isEmpty()) {
            lines.get(0).text(text);
            return;
        }
        block.getWorld().spawn(above, TextDisplay.class, line -> {
            line.text(text);
            line.setBillboard(Display.Billboard.CENTER);
            line.setAlignment(TextDisplay.TextAlignment.CENTER);
            line.getPersistentDataContainer().set(LINE, PersistentDataType.STRING, place);
        });
    }
}
