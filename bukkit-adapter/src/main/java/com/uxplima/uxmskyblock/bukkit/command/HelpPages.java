package com.uxplima.uxmskyblock.bukkit.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;

/** The pages of {@code /is help}, split out of {@link IslandCommandTree} so the tree stays the size the standards allow. */
final class HelpPages {

    /** Says one catalogue line to an audience, the way the tree says every line. */
    @FunctionalInterface
    interface Say {
        void say(Audience audience, String key, TagResolver... resolvers);
    }

    private static final int HELP_PAGE = 10;

    private final Messages messages;
    private final Say say;

    HelpPages(Messages messages, Say say) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.say = Objects.requireNonNull(say, "say must not be null");
    }

    /**
     * Lists the commands the sender can use, under the words the operator gave them, a page at a time.
     *
     * <p>It was a list of twenty nine lines in the catalogue, and the command had seventy eight
     * branches: a player never learned of invite, members, warp or shop from it, and a renamed word
     * still printed as the shipped one. Each line is now drawn from the command the server registered,
     * with its words from the reader's catalogue.
     */
    int show(
            CommandSourceStack source,
            int page,
            String rootWord,
            List<ConfiguredCommandTree.Branch<CommandSourceStack>> helpBranches) {
        CommandSender sender = source.getSender();
        List<ConfiguredCommandTree.Branch<CommandSourceStack>> usable = new ArrayList<>();
        String helpWord = "help";
        for (ConfiguredCommandTree.Branch<CommandSourceStack> branch : helpBranches) {
            if (branch.key().equals("help")) {
                helpWord = branch.name();
            }
            // A branch with no help words is not listed. The catalogue gives none to a second word
            // for another branch, such as chest for the vault, so a command is one line.
            if (messages.has("help.commands." + branch.key() + ".description")
                    && branch.requirement().test(source)) {
                usable.add(branch);
            }
        }
        int pages = Math.max(1, (usable.size() + HELP_PAGE - 1) / HELP_PAGE);
        int shown = Math.min(Math.max(1, page), pages);
        say.say(
                sender,
                "help.header",
                Placeholder.unparsed("page", Integer.toString(shown)),
                Placeholder.unparsed("pages", Integer.toString(pages)));
        for (ConfiguredCommandTree.Branch<CommandSourceStack> branch :
                usable.subList((shown - 1) * HELP_PAGE, Math.min(usable.size(), shown * HELP_PAGE))) {
            String usage = "help.commands." + branch.key() + ".usage";
            Component usageLine = messages.has(usage)
                            && !messages.provider().getRaw(usage, "en").isBlank()
                    ? Component.space().append(messages.renderPlain(sender, usage))
                    : Component.empty();
            say.say(
                    sender,
                    "help.entry",
                    Placeholder.unparsed("root", rootWord),
                    Placeholder.unparsed("name", branch.name()),
                    Placeholder.component("usage", usageLine),
                    Placeholder.component(
                            "description",
                            messages.renderPlain(sender, "help.commands." + branch.key() + ".description")));
        }
        if (shown < pages) {
            say.say(
                    sender,
                    "help.next_page",
                    Placeholder.unparsed("root", rootWord),
                    Placeholder.unparsed("help", helpWord),
                    Placeholder.unparsed("page", Integer.toString(shown + 1)));
        }
        return Cmd.OK;
    }
}
