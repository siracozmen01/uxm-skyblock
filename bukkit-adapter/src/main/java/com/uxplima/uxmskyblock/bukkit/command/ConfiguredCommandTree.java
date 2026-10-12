package com.uxplima.uxmskyblock.bukkit.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.logging.Logger;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.uxplima.uxmlib.command.annotation.ConfiguredCommands;
import org.jspecify.annotations.Nullable;

/**
 * The island command as the operator named it in {@code commands.conf}.
 *
 * <p>Every word of the command was written in Java, so a server that already had an {@code /island}
 * from another plugin, or one that plays in a language where {@code invite} means nothing, could not
 * change a single one. The root and each of its branches now read a name, aliases and whether they
 * are on at all from the file, under the key the code knows them by, and a branch the file does not
 * mention keeps its own word.
 *
 * <p>A name another branch already answers to is refused and the branch keeps its own: two branches
 * under one word would merge their arguments, and a player would reach whichever the server tried
 * first.
 *
 * <p>A root or a branch may also carry {@code localized-aliases}, words for the readers of one language. A word
 * on the root is one more alias, which every player can type and only its readers are shown. A word on a branch
 * is answered for its readers and the console, and nobody else is offered it.
 *
 * @param <S> the command source
 */
final class ConfiguredCommandTree<S> {

    /** The key the root is read under, and the word it answers to when the file says nothing. */
    static final String ROOT = "island";

    private static final Logger LOGGER = Logger.getLogger(ConfiguredCommandTree.class.getName());

    private final @Nullable ConfiguredCommands names;

    /** A branch as a player reaches it: the key the code knows it by, the word typed, who may. */
    record Branch<S>(String key, String name, java.util.function.Predicate<S> requirement) {}

    private final List<Branch<S>> branches = new ArrayList<>();

    /** Whether a source reads the language a tag names. The console reads every one. */
    private final BiPredicate<S, String> reads;

    /** The key the root is read under, the word it answers to and its aliases when the file says nothing. */
    private final String rootKey;

    private final String rootWord;
    private final List<String> rootAliasesShipped;

    ConfiguredCommandTree(@Nullable ConfiguredCommands names) {
        this(names, (source, tag) -> true);
    }

    /** @param reads whether a source reads the language a tag such as {@code tr} or {@code pt-BR} names */
    ConfiguredCommandTree(@Nullable ConfiguredCommands names, BiPredicate<S, String> reads) {
        this(names, reads, ROOT, ROOT, List.of("is"));
    }

    /**
     * A tree read under another key: another command, or the branches of one branch, keyed as
     * {@code island.subcommands.chunks}.
     *
     * @param rootKey the key the root is read under
     * @param rootWord the word the root answers to when the file says nothing
     * @param rootAliases the aliases it has when the file says nothing
     */
    ConfiguredCommandTree(
            @Nullable ConfiguredCommands names,
            BiPredicate<S, String> reads,
            String rootKey,
            String rootWord,
            List<String> rootAliases) {
        this.names = names;
        this.reads = Objects.requireNonNull(reads, "reads");
        this.rootKey = Objects.requireNonNull(rootKey, "rootKey");
        this.rootWord = Objects.requireNonNull(rootWord, "rootWord");
        this.rootAliasesShipped = List.copyOf(rootAliases);
    }

    /** Whether a player who reads {@code locale} is one of the readers {@code tag} names. */
    static boolean readerOf(String tag, Locale locale) {
        Locale wanted = Locale.forLanguageTag(tag);
        return wanted.getLanguage().equalsIgnoreCase(locale.getLanguage())
                && (wanted.getCountry().isEmpty() || wanted.getCountry().equalsIgnoreCase(locale.getCountry()));
    }

    /** Every word the root answers to besides its name: its aliases, then the words for one language. */
    List<String> rootAliases() {
        ConfiguredCommands.Entry root = root();
        List<String> aliases = new ArrayList<>(root.aliases());
        rootLocalized().keySet().stream()
                .filter(word -> !word.equals(root.name()) && !aliases.contains(word))
                .forEach(aliases::add);
        return List.copyOf(aliases);
    }

    /** Each word the root carries for some languages only, and the tags of those languages. */
    Map<String, List<String>> rootLocalized() {
        return byWord(root());
    }

    private static Map<String, List<String>> byWord(ConfiguredCommands.Entry entry) {
        Map<String, List<String>> tagsByWord = new LinkedHashMap<>();
        entry.localizedAliases()
                .forEach((tag, words) -> words.forEach(word -> tagsByWord
                        .computeIfAbsent(word, ignored -> new ArrayList<>())
                        .add(tag)));
        return tagsByWord;
    }

    /** The root's own entry: its name, its aliases and whether it is registered at all. */
    ConfiguredCommands.Entry root() {
        return entry(rootKey, rootWord, rootAliasesShipped);
    }

    /** The key's entry for a branch of the root, which answers to its own word when the file is silent. */
    ConfiguredCommands.Entry branch(String key) {
        return entry(ConfiguredCommands.branchKey(rootKey, key), key, List.of());
    }

    /**
     * The tree {@code built} describes, under the names the file gives, with every branch the file
     * turns off left out.
     */
    LiteralArgumentBuilder<S> apply(LiteralArgumentBuilder<S> built) {
        Objects.requireNonNull(built, "built");
        return rename(built, root().name());
    }

    /**
     * The branches of {@code built} under the names the file gives, with {@code built} keeping its own word: for a
     * branch whose word the tree above it names.
     */
    LiteralArgumentBuilder<S> applyToBranches(LiteralArgumentBuilder<S> built) {
        Objects.requireNonNull(built, "built");
        return rename(built, built.getLiteral());
    }

    private LiteralArgumentBuilder<S> rename(LiteralArgumentBuilder<S> built, String rootName) {
        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.<S>literal(rootName)
                .requires(built.getRequirement())
                .executes(built.getCommand());
        Set<String> taken = new HashSet<>();
        List<CommandNode<S>> kept = new ArrayList<>();
        for (CommandNode<S> child : built.getArguments()) {
            if (child instanceof LiteralCommandNode<S> literal) {
                taken.add(literal.getLiteral());
            }
            kept.add(child);
        }
        Set<String> answered = new HashSet<>();
        branches.clear();
        for (CommandNode<S> child : kept) {
            if (!(child instanceof LiteralCommandNode<S> literal)) {
                root.then(child);
                continue;
            }
            String key = literal.getLiteral();
            ConfiguredCommands.Entry entry = branch(key);
            if (!entry.enabled()) {
                continue;
            }
            List<String> words = new ArrayList<>();
            words.add(entry.name());
            words.addAll(entry.aliases());
            Map<String, List<String>> localized = byWord(entry);
            localized.keySet().stream().filter(word -> !words.contains(word)).forEach(words::add);
            for (String word : words) {
                boolean someoneElses = !word.equals(key) && taken.contains(word);
                if (someoneElses || !answered.add(word)) {
                    LOGGER.warning(() -> "commands.conf names the " + key + " branch '" + word
                            + "', which another branch already answers to, so it keeps its own word.");
                    if (answered.add(key)) {
                        root.then(copy(literal, key));
                        branches.add(new Branch<>(key, key, literal.getRequirement()));
                    }
                    continue;
                }
                List<String> tags = localized.get(word);
                if (tags != null
                        && !word.equals(entry.name())
                        && !entry.aliases().contains(word)) {
                    // A word for some languages is offered to their readers, and the console, only.
                    root.then(copy(literal, word)
                            .requires(literal.getRequirement()
                                    .and(source -> tags.stream().anyMatch(tag -> reads.test(source, tag)))));
                    continue;
                }
                root.then(copy(literal, word));
                if (word.equals(entry.name())) {
                    branches.add(new Branch<>(key, word, literal.getRequirement()));
                }
            }
        }
        return root;
    }

    /** Every branch {@link #apply} kept, in the order the tree holds them, under its own name. */
    List<Branch<S>> branches() {
        return List.copyOf(branches);
    }

    /** {@code node} under another word, with everything it runs, requires and holds. */
    private static <S> LiteralArgumentBuilder<S> copy(LiteralCommandNode<S> node, String word) {
        LiteralArgumentBuilder<S> copy = LiteralArgumentBuilder.<S>literal(word)
                .requires(node.getRequirement())
                .executes(node.getCommand());
        if (node.getRedirect() != null) {
            // A redirected node holds no children of its own; it hands the rest of the line on.
            return copy.forward(node.getRedirect(), node.getRedirectModifier(), node.isFork());
        }
        for (CommandNode<S> child : node.getChildren()) {
            copy.then(child);
        }
        return copy;
    }

    private ConfiguredCommands.Entry entry(String key, String word, List<String> aliases) {
        ConfiguredCommands file = this.names;
        if (file == null) {
            return new ConfiguredCommands.Entry(word, aliases, true);
        }
        return file.entryOf(key, word);
    }
}
