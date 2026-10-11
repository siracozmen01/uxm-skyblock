package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.bank.BankCurrencies;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * One currency the operator lets islands keep in their bank, as {@code modules/bank.conf} describes it.
 *
 * <p>The island's own money, which upgrades, the shop and upkeep spend, is the server's economy and is not one of
 * these. These are everything else a server wants players to put away together: their experience, their points, a
 * stack of diamonds, the balance of a second economy plugin. Each is kept in whole units.
 *
 * @param id what the bank keeps it under: lower case letters, digits, {@code _} and {@code -}
 * @param type where the currency lives
 * @param order where it stands among the others, the lowest first
 * @param displayName its name, a catalogue key written {@code @key} or the words themselves
 * @param icon the item its tile in the bank window wears
 * @param options what its type needs, every value as the file wrote it
 * @param quick the amounts its window moves in one click, the smallest first
 */
public record BankCurrencySpec(
        String id,
        Type type,
        int order,
        boolean enabled,
        String displayName,
        String icon,
        Map<String, String> options,
        List<Long> quick) {

    /** The amounts a currency whose file names none moves in one click. */
    public static final List<Long> DEFAULT_QUICK = List.of(10L, 100L);

    private static final Logger LOGGER = Logger.getLogger(BankCurrencySpec.class.getName());

    /** Where a currency lives. */
    public enum Type {
        VAULT,
        VAULTUNLOCKED,
        PLAYERPOINTS,
        ECOBITS,
        TREASURY,
        ITEM,
        EXPERIENCE,
        BRIDGE,
        PLACEHOLDER;

        /** The type a file names, any case, or nothing for a word that is none of them. */
        public static Optional<Type> named(String written) {
            for (Type type : values()) {
                if (type.name().equalsIgnoreCase(written.trim())) {
                    return Optional.of(type);
                }
            }
            return Optional.empty();
        }

        /** Whether the currency lives in the player, so it is read and moved on the thread that owns them. */
        public boolean inThePlayer() {
            return this == ITEM || this == EXPERIENCE;
        }
    }

    public BankCurrencySpec {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(icon, "icon must not be null");
        options = Map.copyOf(Objects.requireNonNull(options, "options must not be null"));
        quick = List.copyOf(Objects.requireNonNull(quick, "quick must not be null"));
        if (!BankCurrencies.isHeld(id)) {
            throw new IllegalArgumentException("'" + id + "' is not a currency id the bank can keep");
        }
    }

    /** A currency that moves the shipped amounts in one click. */
    public BankCurrencySpec(
            String id,
            Type type,
            int order,
            boolean enabled,
            String displayName,
            String icon,
            Map<String, String> options) {
        this(id, type, order, enabled, displayName, icon, options, DEFAULT_QUICK);
    }

    /**
     * The amounts under {@code node}: whole numbers above nothing, the smallest first, or {@code fallback} when the
     * file names none it can use.
     */
    static List<Long> quickOf(ConfigurationNode node, List<Long> fallback) {
        List<Long> amounts = new ArrayList<>();
        for (ConfigurationNode written : node.childrenList()) {
            long amount = written.getLong(0L);
            if (amount > 0 && !amounts.contains(amount)) {
                amounts.add(amount);
            }
        }
        amounts.sort(Comparator.naturalOrder());
        return amounts.isEmpty() ? fallback : List.copyOf(amounts);
    }

    /** An option of its type, or nothing when the file left it out. */
    public Optional<String> option(String name) {
        return Optional.ofNullable(options.get(name)).filter(value -> !value.isBlank());
    }

    /**
     * Every currency under {@code bank.currencies}, in order, the ones turned off included. A currency the file
     * describes wrongly is left out and the console says why: one wrong line does not close the bank.
     */
    public static List<BankCurrencySpec> load(ConfigurationNode root) {
        Objects.requireNonNull(root, "root must not be null");
        List<BankCurrencySpec> specs = new ArrayList<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                root.node("bank", "currencies").childrenMap().entrySet()) {
            String id = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            ConfigurationNode node = entry.getValue();
            Optional<Type> type = Type.named(node.node("type").getString(""));
            if (type.isEmpty()) {
                LOGGER.warning(() -> "The bank currency " + id + " names the type '"
                        + node.node("type").getString("")
                        + "', which is none of vault, vaultunlocked, playerpoints, ecobits, treasury, item, experience,"
                        + " bridge and placeholder. It stays out of the bank.");
                continue;
            }
            if (!BankCurrencies.isHeld(id)) {
                LOGGER.warning(() -> "The bank currency '" + id + "' needs another id: lower case letters, digits,"
                        + " _ and -, at most 32, and none of primary, crystals, exp and vault. It stays out of the"
                        + " bank.");
                continue;
            }
            Map<String, String> options = new LinkedHashMap<>();
            node.childrenMap().forEach((key, child) -> {
                if (child.isMap() || child.isList()) {
                    return;
                }
                String value = child.getString();
                if (value != null) {
                    options.put(String.valueOf(key), value);
                }
            });
            specs.add(new BankCurrencySpec(
                    id,
                    type.get(),
                    node.node("order").getInt(100),
                    node.node("enabled").getBoolean(true),
                    node.node("display-name").getString(id),
                    node.node("icon").getString(defaultIcon(type.get())),
                    options,
                    quickOf(node.node("quick"), DEFAULT_QUICK)));
        }
        specs.sort(Comparator.comparingInt(BankCurrencySpec::order).thenComparing(BankCurrencySpec::id));
        return List.copyOf(specs);
    }

    private static String defaultIcon(Type type) {
        return switch (type) {
            case EXPERIENCE -> "EXPERIENCE_BOTTLE";
            case ITEM -> "CHEST";
            case PLAYERPOINTS -> "AMETHYST_SHARD";
            default -> "GOLD_NUGGET";
        };
    }
}
