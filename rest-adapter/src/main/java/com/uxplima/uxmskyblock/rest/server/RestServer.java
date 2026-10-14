package com.uxplima.uxmskyblock.rest.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.event.OutboxEventConsumer;
import com.uxplima.uxmskyblock.core.application.health.ServerHealthPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.rest.config.RestConfiguration;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinGson;
import org.jspecify.annotations.Nullable;

/**
 * Embedded lightweight REST server providing health, metrics, island queries,
 * bank operations, and leaderboard access (Section 2.44).
 */
public final class RestServer implements AutoCloseable {

    /** {@code POST} of a bank deposit: the request checked, held once under its idempotency key, and the money moved into the island's bank. */
    private final BankDepositHandler deposits = new BankDepositHandler(this);

    private static final Logger LOGGER = Logger.getLogger(RestServer.class.getName());

    final RestConfiguration config;
    final ServerNodeId serverNodeId;
    private final IslandStoragePort islandStoragePort;
    final IslandBankService bankService;
    private final IslandLeaderboardService leaderboardService;
    private final @Nullable ServerHealthPort healthPort;
    private final LiveEventFeed liveEventFeed = new LiveEventFeed();
    private @Nullable Javalin app;

    public RestServer(
            RestConfiguration config,
            ServerNodeId serverNodeId,
            IslandStoragePort islandStoragePort,
            IslandBankService bankService,
            IslandLeaderboardService leaderboardService,
            @Nullable ServerHealthPort healthPort) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.bankService = Objects.requireNonNull(bankService, "bankService must not be null");
        this.leaderboardService = Objects.requireNonNull(leaderboardService, "leaderboardService must not be null");
        this.healthPort = healthPort;
    }

    public synchronized void start() {
        if (app != null) {
            return;
        }

        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinGson());
        });

        // Bearer Token Authentication Filter
        app.before("/api/*", ctx -> {
            if (ctx.path().equals("/api/v1/health")) {
                return;
            }
            String authHeader = ctx.header("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "Missing or invalid Authorization header"));
                ctx.skipRemainingHandlers();
                return;
            }
            String token = authHeader.substring("Bearer ".length()).trim();
            if (!tokenMatches(token)) {
                ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "Invalid bearer token"));
                ctx.skipRemainingHandlers();
            }
        });

        // 1. Health
        app.get("/api/v1/health", this::handleHealth);

        // 2. Island Details
        app.get("/api/v1/islands/{id}", this::handleGetIsland);

        // 3. Island Members
        app.get("/api/v1/islands/{id}/members", this::handleGetMembers);

        // 4. Leaderboards
        app.get("/api/v1/leaderboards/{metric}", this::handleGetLeaderboard);

        // The same board under the path the enterprise foundation document publishes. A web store
        // written against the documented path used to get a 404, because the route was renamed in
        // code and the document was not.
        app.get("/api/v1/top/{metric}", this::handleGetLeaderboard);

        // 5. Bank Deposit (Tebex / CraftingStore Webstore)
        app.post("/api/v1/islands/{id}/bank/deposit", deposits::handleBankDeposit);

        // 6. Live event feed for map overlays.
        app.ws("/api/v1/events", ws -> {
            ws.onConnect(ctx -> {
                if (!authorizeSocket(ctx)) {
                    ctx.closeSession(1008, "Missing or invalid bearer token.");
                    return;
                }
                if (!liveEventFeed.register(ctx)) {
                    ctx.closeSession(1013, "Too many viewers are already connected.");
                    return;
                }
                ctx.enableAutomaticPings();
                liveEventFeed.send(ctx, LiveEventFeed.helloFrame(serverNodeId.value()));
            });
            ws.onClose(liveEventFeed::unregister);
            ws.onError(ctx -> liveEventFeed.unregister(ctx));
        });

        app.start(config.host(), config.port());
        LOGGER.info(() -> "REST server started on " + config.host() + ":" + config.port());
    }

    private void handleHealth(Context ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("nodeId", serverNodeId.value());
        body.put("timestamp", Instant.now().toString());
        // The three the enterprise foundation document publishes. They are absent rather than
        // invented when this node has no health source wired, which a caller can tell apart.
        if (healthPort != null) {
            double tps = healthPort.ticksPerSecond();
            if (!Double.isNaN(tps)) {
                body.put("ticksPerSecond", round(tps));
            }
            body.put("activeIslands", healthPort.activeIslandCount());
            body.put("cacheHitRatio", round(healthPort.spatialCacheHitRatio()));
        }
        ctx.status(HttpStatus.OK).json(body);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void handleGetIsland(Context ctx) {
        String rawId = ctx.pathParam("id");
        IslandId islandId;
        try {
            islandId = IslandId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Invalid island UUID format"));
            return;
        }

        Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
        if (optIsland.isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Island not found"));
            return;
        }

        Island island = optIsland.get();
        ctx.status(HttpStatus.OK)
                .json(Map.of(
                        "islandId", island.id().value().toString(),
                        "ownerProfileId", island.ownerProfileId().value().toString(),
                        "memberCount", island.members().size(),
                        "createdAt", island.createdAt().toString()));
    }

    private void handleGetMembers(Context ctx) {
        String rawId = ctx.pathParam("id");
        IslandId islandId;
        try {
            islandId = IslandId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Invalid island UUID format"));
            return;
        }

        Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
        if (optIsland.isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Island not found"));
            return;
        }

        Island island = optIsland.get();
        List<Map<String, Object>> memberList = island.members().entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "profileId", entry.getKey().value().toString(),
                        "role", entry.getValue().role().id(),
                        "joinedAt", entry.getValue().joinedAt().toString()))
                .toList();

        ctx.status(HttpStatus.OK).json(memberList);
    }

    private void handleGetLeaderboard(Context ctx) {
        String metric = ctx.pathParam("metric");
        Optional<LeaderboardCategory> optCategory = resolveCategory(metric);
        if (optCategory.isEmpty()) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Unknown leaderboard metric: " + metric));
            return;
        }
        LeaderboardCategory category = optCategory.get();

        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(10);
        List<LeaderboardEntry> entries = leaderboardService.getTop(category, limit);
        List<Map<String, Object>> response = entries.stream()
                .map(entry -> Map.<String, Object>of(
                        "rank", entry.rank(),
                        "islandId", entry.islandId().value().toString(),
                        "islandName", entry.islandName(),
                        "score", entry.score(),
                        "formattedScore", entry.formattedScore()))
                .toList();

        ctx.status(HttpStatus.OK).json(response);
    }

    /**
     * The metric a caller named, in either spelling. The document publishes the boards as
     * {@code top/levels} while the enum constant is {@code LEVEL}, and refusing one of the two
     * spellings buys nothing at all.
     */
    private static Optional<LeaderboardCategory> resolveCategory(String raw) {
        String name = raw.trim().toUpperCase(Locale.ROOT);
        String singular = name.endsWith("S") ? name.substring(0, name.length() - 1) : name;
        for (LeaderboardCategory category : LeaderboardCategory.values()) {
            if (category.name().equals(name) || category.name().equals(singular)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }

    private static final java.util.regex.Pattern UUID_V4_PATTERN = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private static final java.util.regex.Pattern ULID_PATTERN =
            java.util.regex.Pattern.compile("^[0123456789ABCDEFGHJKMNPQRSTVWXYZabcdefghjkmnpqrstvwxyz]{26}$");

    static boolean isValidIdempotencyKey(String key) {
        if (key == null) {
            return false;
        }
        return UUID_V4_PATTERN.matcher(key).matches()
                || ULID_PATTERN.matcher(key).matches();
    }

    public record IdempotentDepositRecord(
            IslandId islandId, long amount, String reason, HttpStatus status, Map<String, Object> responseBody) {}

    /** A remembered deposit and when it was remembered. */
    record RememberedDeposit(IdempotentDepositRecord record, Instant storedAt) {}

    /**
     * Deposits already made, so a retry is answered rather than made again.
     *
     * <p>The key comes from the caller, and nothing ever removed one. A web store retrying, or a
     * caller with the token sending fresh keys on purpose, grew this map for the life of the
     * process. It is bounded twice now: by how long a key is worth remembering, and by how many a
     * node will hold at once whatever the clock says.
     */
    final java.util.concurrent.ConcurrentMap<String, RememberedDeposit> idempotencyCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Remembers a deposit's answer, and drops what has aged out or overflowed the ceiling. */
    void rememberDeposit(String idempotencyKey, IdempotentDepositRecord record) {
        Instant now = Instant.now();
        idempotencyCache.put(idempotencyKey, new RememberedDeposit(record, now));
        // Pruned after the write, so the ceiling holds after every one of them rather than before.
        deposits.pruneIdempotency(now);
    }

    /** Remembers one deposit, for a test that drives the bound rather than the endpoint. */
    void rememberDepositForTest(String idempotencyKey, IdempotentDepositRecord record) {
        rememberDeposit(idempotencyKey, record);
    }

    /** How many deposits this node is remembering, for a caller that wants to say so. */
    int idempotencyEntries() {
        return idempotencyCache.size();
    }

    /**
     * Checks the bearer token on a socket upgrade.
     *
     * <p>The {@code before} filter covers the HTTP routes and never sees a websocket upgrade, so
     * the check is repeated here rather than assumed. A browser cannot set an Authorization header
     * on a websocket, so a query parameter is accepted too; an operator who cares that a token in a
     * URL reaches an access log should put the feed behind a proxy that strips it.
     */
    private boolean authorizeSocket(io.javalin.websocket.WsContext ctx) {
        String header = ctx.header("Authorization");
        String token = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length()).trim()
                : ctx.queryParam("token");
        return token != null && tokenMatches(token);
    }

    /**
     * Whether a presented token is this node's token, compared in a time nobody can read.
     *
     * <p>{@code String.equals} stops at the first byte that differs, so how long a refusal takes
     * says how much of the token was right. An attacker who can time the answers walks the token out
     * one byte at a time, and this is the door to an endpoint that moves money.
     *
     * <p>Both sides are hashed first so the comparison is over two arrays of the same length
     * whatever the presented token was, and the length of the real token leaks no more than its
     * bytes do.
     */
    boolean tokenMatches(String presented) {
        byte[] presentedDigest = sha256(presented);
        byte[] expectedDigest = sha256(config.bearerToken());
        return MessageDigest.isEqual(presentedDigest, expectedDigest);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            // Every Java runtime carries SHA-256. A runtime that does not cannot authenticate anybody.
            throw new IllegalStateException("SHA-256 is missing from this runtime", impossible);
        }
    }

    /**
     * The feed behind {@code WS /api/v1/events}, to register with the outbox dispatcher.
     *
     * <p>Registering it is the caller's job, because the dispatcher belongs to the plugin and this
     * module must not reach for it.
     */
    public OutboxEventConsumer liveEventFeed() {
        return liveEventFeed;
    }

    /** How many viewers the feed is holding, which is what the health endpoint reports. */
    public int liveEventViewers() {
        return liveEventFeed.viewerCount();
    }

    public int port() {
        return app != null ? app.port() : config.port();
    }

    @Override
    public synchronized void close() {
        liveEventFeed.closeAll();
        if (app != null) {
            app.stop();
            app = null;
        }
    }
}
