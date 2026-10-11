package com.uxplima.uxmskyblock.bukkit.integration.economy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import com.uxplima.uxmlib.common.Durations;
import com.uxplima.uxmlib.condition.Wallet;
import com.uxplima.uxmlib.condition.wallet.BridgedWallet;
import com.uxplima.uxmlib.condition.wallet.Economies;
import com.uxplima.uxmlib.condition.wallet.EconomyBinding;
import com.uxplima.uxmlib.condition.wallet.ExperienceWallet;
import com.uxplima.uxmlib.condition.wallet.PlaceholderWallet;
import com.uxplima.uxmlib.condition.wallet.ServerPlaceholders;
import com.uxplima.uxmlib.condition.wallet.TreasuryWallet;
import com.uxplima.uxmskyblock.bukkit.config.BankCurrencySpec;
import com.uxplima.uxmskyblock.core.application.bank.BankCurrencies;
import com.uxplima.uxmskyblock.core.application.economy.ExternalWalletPort;
import com.uxplima.uxmskyblock.core.application.economy.WalletDirectory;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * The wallet of every currency an island bank keeps: the server's economy for the island's own money, and for
 * each currency the operator lists, the wallet its type names.
 *
 * <p>A wallet in another plugin is asked from whatever thread the bank works on, which is never the main thread. A
 * wallet in the player, their experience or an item, is asked on the thread that owns them: the bank's thread waits
 * a few seconds for that thread to answer, and a player who left in between is answered as having nothing.
 */
public final class BankWallets implements WalletDirectory {

    private static final Logger LOGGER = Logger.getLogger(BankWallets.class.getName());

    /** How long the bank waits for the thread that owns a player to read or move what they carry. */
    private static final Duration WAIT = Duration.ofSeconds(5);

    /** One currency the bank keeps, the wallet it lives in, and the pool of that wallet it is. */
    public record Kept(BankCurrencySpec spec, Wallet wallet, String pool) {
        public Kept {
            Objects.requireNonNull(spec, "spec must not be null");
            Objects.requireNonNull(wallet, "wallet must not be null");
            Objects.requireNonNull(pool, "pool must not be null");
        }
    }

    private final ExternalWalletPort money;
    private final SchedulerPort scheduler;
    private final Map<String, Kept> kept;

    public BankWallets(ExternalWalletPort money, SchedulerPort scheduler, Map<String, Kept> kept) {
        this.money = Objects.requireNonNull(money, "money must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.kept = Map.copyOf(Objects.requireNonNull(kept, "kept must not be null"));
    }

    /** The wallets of the currencies turned on in {@code specs}, built against this server. */
    public static BankWallets ofServer(
            ExternalWalletPort money, SchedulerPort scheduler, List<BankCurrencySpec> specs, String caller) {
        Map<String, Kept> kept = new LinkedHashMap<>();
        System.Logger log = System.getLogger(BankWallets.class.getName());
        for (BankCurrencySpec spec : specs) {
            if (!spec.enabled()) {
                continue;
            }
            try {
                kept.put(spec.id(), built(spec, log, caller, scheduler));
            } catch (RuntimeException | LinkageError wrong) {
                LOGGER.log(
                        Level.WARNING,
                        "The bank currency " + spec.id() + " could not be built and stays out of the bank: "
                                + wrong.getMessage(),
                        wrong);
            }
        }
        kept.values().stream()
                .filter(currency -> !answers(currency))
                .forEach(currency -> LOGGER.info(() ->
                        "The bank currency " + currency.spec().id() + " lives in a plugin this server does not run now."
                                + " It stays out of the bank until that plugin is here."));
        return new BankWallets(money, scheduler, kept);
    }

    /**
     * Whether the economy a currency lives in answers now. Experience and items are in the player and always
     * answer. A currency in another plugin answers while that plugin is here, so one the server does not run is
     * left out of the bank's windows and commands rather than drawn as a balance of nothing nobody can move.
     */
    static boolean answers(Kept kept) {
        return switch (kept.spec().type()) {
            case ITEM, EXPERIENCE -> true;
            case PLACEHOLDER -> Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
            case TREASURY -> Bukkit.getPluginManager().isPluginEnabled("Treasury");
            default -> !(kept.wallet() instanceof BridgedWallet bridged) || bridged.reaches(kept.pool());
        };
    }

    /** The currencies the bank keeps beside the island's own money and can reach now, in the operator's order. */
    public List<Kept> currencies() {
        return kept.values().stream()
                .filter(BankWallets::answers)
                .sorted(java.util.Comparator.comparingInt((Kept k) -> k.spec().order())
                        .thenComparing(k -> k.spec().id()))
                .toList();
    }

    /** The currency kept under {@code id} when it can be reached now, or nothing. */
    public Optional<Kept> currency(String id) {
        return Optional.ofNullable(kept.get(id.toLowerCase(Locale.ROOT))).filter(BankWallets::answers);
    }

    @Override
    public Optional<ExternalWalletPort> walletFor(String currency) {
        if (BankCurrencies.isPrimary(currency)) {
            return Optional.of(money);
        }
        // A move a crash left half done is finished or undone through the wallet it began with, reachable or not.
        return Optional.ofNullable(kept.get(currency.toLowerCase(Locale.ROOT))).map(PlayerWallet::new);
    }

    /** What {@code player} carries of the currency {@code id}, read where it lives, or nothing when unknown. */
    public Optional<Long> carried(UUID player, String id) {
        return currency(id)
                .flatMap(found -> new PlayerWallet(found).ask(player, online ->
                        (long) Math.floor(found.wallet().balance(online, found.pool()))));
    }

    /** One currency's wallet as the bank's saga asks it: whole units, by player id. */
    private final class PlayerWallet implements ExternalWalletPort {

        private final Kept kept;

        PlayerWallet(Kept kept) {
            this.kept = kept;
        }

        @Override
        public boolean hasFunds(PlayerUuid playerUuid, long amount) {
            return ask(playerUuid.value(), online -> kept.wallet().balance(online, kept.pool()) >= amount)
                    .orElse(false);
        }

        @Override
        public boolean withdraw(PlayerUuid playerUuid, long amount) {
            return ask(playerUuid.value(), online -> kept.wallet().withdraw(online, kept.pool(), (double) amount))
                    .orElse(false);
        }

        @Override
        public boolean deposit(PlayerUuid playerUuid, long amount) {
            return ask(playerUuid.value(), online -> kept.wallet().deposit(online, kept.pool(), (double) amount))
                    .orElse(false);
        }

        /** Runs {@code work} with the player online, on the thread that owns them where the currency is in them. */
        <T> Optional<T> ask(UUID player, Function<Player, T> work) {
            if (!kept.spec().type().inThePlayer() || scheduler.ownsEntity(new PlayerUuid(player))) {
                Player online = Bukkit.getPlayer(player);
                return online == null ? Optional.empty() : Optional.ofNullable(work.apply(online));
            }
            CompletableFuture<Optional<T>> answer = new CompletableFuture<>();
            scheduler.onEntity(
                    new PlayerUuid(player),
                    () -> {
                        Player online = Bukkit.getPlayer(player);
                        answer.complete(online == null ? Optional.empty() : Optional.ofNullable(work.apply(online)));
                    },
                    () -> answer.complete(Optional.empty()));
            try {
                return answer.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (ExecutionException | TimeoutException unanswered) {
                LOGGER.log(
                        Level.WARNING,
                        "The wallet of " + kept.spec().id() + " did not answer for " + player,
                        unanswered);
                return Optional.empty();
            }
        }
    }

    /** The wallet a currency's type names, from what the file wrote for it. */
    static Kept built(BankCurrencySpec spec, System.Logger log, String caller, SchedulerPort scheduler) {
        String pool = spec.option("currency-name").orElse("");
        return switch (spec.type()) {
            case VAULT -> new Kept(spec, BridgedWallet.ofServer(Economies.vault(), log), "");
            case VAULTUNLOCKED -> new Kept(spec, BridgedWallet.ofServer(Economies.vaultUnlocked(caller), log), pool);
            case PLAYERPOINTS -> new Kept(spec, BridgedWallet.ofServer(Economies.playerPoints(), log), "");
            case ECOBITS ->
                new Kept(
                        spec,
                        BridgedWallet.ofServer(Economies.ecoBits(), log),
                        spec.option("currency-name")
                                .orElseThrow(
                                        () -> new IllegalArgumentException("an ecobits currency names currency-name")));
            case TREASURY ->
                new Kept(
                        spec,
                        TreasuryWallet.ofServer(
                                spec.option("wait-for").map(Durations::parse).orElse(Duration.ofSeconds(10)), log),
                        pool);
            case ITEM -> new Kept(spec, new ItemWallet(material(spec)), "");
            case EXPERIENCE ->
                new Kept(
                        spec,
                        "levels".equalsIgnoreCase(spec.option("unit").orElse("points"))
                                ? ExperienceWallet.ofLevels()
                                : ExperienceWallet.ofPoints(),
                        "");
            case BRIDGE -> new Kept(spec, BridgedWallet.ofServer(binding(spec), log), pool);
            case PLACEHOLDER ->
                new Kept(
                        spec,
                        new PlaceholderWallet(
                                Map.of("", placeholderPool(spec)),
                                new ServerPlaceholders(),
                                line -> console(scheduler, line)),
                        "");
        };
    }

    private static Material material(BankCurrencySpec spec) {
        String written = spec.option("material")
                .orElseThrow(() -> new IllegalArgumentException("an item currency names its material"));
        Material material = Material.matchMaterial(written);
        if (material == null) {
            throw new IllegalArgumentException("this server has no item called " + written);
        }
        return material;
    }

    private static PlaceholderWallet.Pool placeholderPool(BankCurrencySpec spec) {
        PlaceholderWallet.Pool pool = new PlaceholderWallet.Pool(
                spec.option("placeholder")
                        .orElseThrow(
                                () -> new IllegalArgumentException("a placeholder currency names its placeholder")),
                spec.option("take")
                        .orElseThrow(() -> new IllegalArgumentException("a placeholder currency names its take line")),
                spec.option("thousands").orElse(""));
        return spec.option("give").map(pool::paying).orElse(pool);
    }

    /** A console line, sent on the server's global thread, which is the only thread a console command runs on. */
    private static boolean console(SchedulerPort scheduler, String line) {
        if (scheduler.onGlobalThread()) {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
        }
        CompletableFuture<Boolean> sent = new CompletableFuture<>();
        scheduler.onGlobal(() -> sent.complete(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line)));
        try {
            return sent.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException unanswered) {
            return false;
        }
    }

    /** An economy plugin described by hand, as {@code currencies.conf} of the auction house describes one. */
    private static EconomyBinding binding(BankCurrencySpec spec) {
        EconomyBinding.Pools pools = spec.option("currency-class").isPresent()
                ? EconomyBinding.Pools.byObject(
                        spec.option("currency-class").orElseThrow(),
                        spec.option("currency-lookup")
                                .orElseThrow(
                                        () -> new IllegalArgumentException("currency-class needs currency-lookup")))
                : spec.option("currency-name").isPresent() ? EconomyBinding.Pools.byName() : EconomyBinding.Pools.one();
        return new EconomyBinding(
                required(spec, "plugin"),
                required(spec, "class"),
                EconomyBinding.Access.valueOf(required(spec, "access").toUpperCase(Locale.ROOT)),
                spec.option("accessor").orElse(null),
                required(spec, "balance"),
                required(spec, "take"),
                spec.option("give").orElse(null),
                EconomyBinding.Argument.valueOf(
                        required(spec, "argument").toUpperCase(Locale.ROOT).replace('-', '_')),
                EconomyBinding.Answer.valueOf(
                        required(spec, "answer").toUpperCase(Locale.ROOT).replace('-', '_')),
                pools,
                new EconomyBinding.Calls(
                        Boolean.parseBoolean(spec.option("take-negates").orElse("false")),
                        spec.option("naming").orElse(null)));
    }

    private static String required(BankCurrencySpec spec, String option) {
        return spec.option(option)
                .orElseThrow(() -> new IllegalArgumentException("a bridge currency names its " + option));
    }
}
