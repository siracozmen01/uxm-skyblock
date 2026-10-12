package com.uxplima.uxmskyblock.bukkit.tradewinds;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;

/** The names the operator gives ports and ranks in the language files, by the ids the file gives them. */
final class Names {

    private Names() {}

    /**
     * {@code <tag>}, filled with what the reader's language file writes under {@code group.id}, or with the
     * id itself when no file names it.
     */
    static TagResolver of(
            Messages messages, net.kyori.adventure.audience.Audience player, String tag, String group, String id) {
        String key = "tradewinds." + group + "." + id;
        return messages.raw(player, key) == null
                ? Placeholder.unparsed(tag, id)
                : Placeholder.component(tag, messages.renderPlain(player, key));
    }

    /** What the reader's language file writes under {@code group.id}, as plain words, or the id itself. */
    static String plain(Messages messages, Player player, String group, String id) {
        String key = "tradewinds." + group + "." + id;
        return messages.raw(player, key) == null
                ? id
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(messages.renderPlain(player, key));
    }
}
