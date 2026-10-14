package com.uxplima.uxmskyblock.rest.server;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * {@code POST} of a bank deposit: the request checked, held once under its idempotency key, and the money moved into the island's bank. Split out of {@link RestServer}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class BankDepositHandler {

    private final RestServer host;

    BankDepositHandler(RestServer host) {
        this.host = host;
    }

    void handleBankDeposit(Context ctx) {
        String rawId = ctx.pathParam("id");
        IslandId islandId;
        try {
            islandId = IslandId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Invalid island UUID format"));
            return;
        }

        JsonObject body;
        try {
            body = JsonParser.parseString(ctx.body()).getAsJsonObject();
        } catch (Exception e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Malformed JSON body"));
            return;
        }

        if (!body.has("amount")) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Missing required field: amount"));
            return;
        }

        var amountElem = body.get("amount");
        if (!amountElem.isJsonPrimitive() || !amountElem.getAsJsonPrimitive().isNumber()) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Amount must be a numeric integer"));
            return;
        }

        long amount;
        try {
            java.math.BigDecimal bd = new java.math.BigDecimal(amountElem.getAsString());
            if (bd.scale() > 0 && bd.stripTrailingZeros().scale() > 0) {
                ctx.status(HttpStatus.BAD_REQUEST)
                        .json(Map.of("error", "Amount must be an integer, fractional values are not allowed"));
                return;
            }
            java.math.BigInteger bi = bd.toBigIntegerExact();
            if (bi.compareTo(java.math.BigInteger.ZERO) <= 0) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Amount must be strictly positive"));
                return;
            }
            if (bi.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Amount exceeds maximum supported value"));
                return;
            }
            amount = bi.longValueExact();
        } catch (Exception e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Invalid numeric amount format"));
            return;
        }

        String reason = body.has("reason") ? body.get("reason").getAsString() : "web_store_deposit";

        String rawIdempotencyKey = ctx.header("Idempotency-Key");
        if (rawIdempotencyKey == null || rawIdempotencyKey.isBlank()) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Missing required header: Idempotency-Key"));
            return;
        }

        String idempotencyKey = rawIdempotencyKey.trim();
        if (!RestServer.isValidIdempotencyKey(idempotencyKey)) {
            ctx.status(HttpStatus.BAD_REQUEST)
                    .json(Map.of("error", "Invalid Idempotency-Key format: must be UUIDv4 or ULID"));
            return;
        }

        RestServer.RememberedDeposit remembered = host.idempotencyCache.get(idempotencyKey);
        RestServer.IdempotentDepositRecord cached = remembered != null ? remembered.record() : null;
        if (cached != null) {
            if (cached.islandId().equals(islandId)
                    && cached.amount() == amount
                    && Objects.equals(cached.reason(), reason)) {
                // Replay cached response
                ctx.status(cached.status()).json(cached.responseBody());
                return;
            } else {
                // Conflict detected
                ctx.status(HttpStatus.CONFLICT)
                        .json(Map.of(
                                "error",
                                "Idempotency conflict: request payload differs from original request for key "
                                        + idempotencyKey,
                                "idempotencyKey",
                                idempotencyKey));
                return;
            }
        }

        UUID operationId = UUID.randomUUID();
        BankTransactionOutcome outcome = host.bankService.depositToIsland(
                islandId,
                PlayerUuid.WEBSTORE,
                amount,
                reason,
                host.serverNodeId,
                operationId,
                idempotencyKey,
                "REST_BANK_DEPOSIT");

        if (outcome instanceof BankTransactionOutcome.Success success) {
            Map<String, Object> resp = Map.of(
                    "status", "SUCCESS",
                    "islandId", islandId.value().toString(),
                    "newBalanceMinorUnits", success.updatedBank().primaryBalanceMinorUnits(),
                    "transactionId", success.transaction().transactionId().toString());
            host.rememberDeposit(
                    idempotencyKey,
                    new RestServer.IdempotentDepositRecord(islandId, amount, reason, HttpStatus.OK, resp));
            ctx.status(HttpStatus.OK).json(resp);
        } else if (outcome instanceof BankTransactionOutcome.DuplicateOperation dup) {
            if ("APPLIED".equalsIgnoreCase(dup.status()) && dup.resultPayload() != null) {
                try {
                    JsonObject cachedJson =
                            JsonParser.parseString(dup.resultPayload()).getAsJsonObject();
                    boolean islandMatch = cachedJson.has("islandId")
                            && cachedJson
                                    .get("islandId")
                                    .getAsString()
                                    .equals(islandId.value().toString());
                    boolean amountMatch = !cachedJson.has("amount")
                            || cachedJson.get("amount").getAsLong() == amount;
                    boolean reasonMatch = !cachedJson.has("reason")
                            || Objects.equals(cachedJson.get("reason").getAsString(), reason);
                    boolean actorMatch = !cachedJson.has("actorId")
                            || Objects.equals(cachedJson.get("actorId").getAsString(), PlayerUuid.WEBSTORE.toString());

                    if (islandMatch && amountMatch && reasonMatch && actorMatch) {
                        Map<String, Object> resp = Map.of(
                                "status", "SUCCESS",
                                "islandId", islandId.value().toString(),
                                "newBalanceMinorUnits",
                                        cachedJson.get("newBalanceMinorUnits").getAsLong(),
                                "transactionId", cachedJson.get("transactionId").getAsString());
                        host.rememberDeposit(
                                idempotencyKey,
                                new RestServer.IdempotentDepositRecord(islandId, amount, reason, HttpStatus.OK, resp));
                        ctx.status(HttpStatus.OK).json(resp);
                        return;
                    } else {
                        ctx.status(HttpStatus.CONFLICT)
                                .json(Map.of(
                                        "error",
                                        "Idempotency conflict: request payload differs from original persisted request for key "
                                                + idempotencyKey,
                                        "idempotencyKey",
                                        idempotencyKey));
                        return;
                    }
                } catch (Exception ignored) {
                    // Fallthrough to conflict
                }
            }
            if ("PENDING".equalsIgnoreCase(dup.status())) {
                ctx.status(HttpStatus.CONFLICT)
                        .json(Map.of(
                                "error",
                                "Operation in progress for idempotency key: " + idempotencyKey,
                                "idempotencyKey",
                                idempotencyKey));
                return;
            }
            ctx.status(HttpStatus.CONFLICT)
                    .json(Map.of("error", "Idempotency conflict: " + dup.message(), "idempotencyKey", idempotencyKey));
        } else {
            Map<String, Object> resp = Map.of(
                    "status", "FAILED",
                    "islandId", islandId.value().toString(),
                    "error", outcome.toString());
            host.rememberDeposit(
                    idempotencyKey,
                    new RestServer.IdempotentDepositRecord(
                            islandId, amount, reason, HttpStatus.UNPROCESSABLE_CONTENT, resp));
            ctx.status(HttpStatus.UNPROCESSABLE_CONTENT).json(resp);
        }
    }
    /** Drops what is too old, and then the oldest of what is left if there is still too much. */
    void pruneIdempotency(Instant now) {
        Duration retention = host.config.idempotencyRetention();
        host.idempotencyCache
                .entrySet()
                .removeIf(entry -> entry.getValue().storedAt().plus(retention).isBefore(now));

        int capacity = host.config.idempotencyCapacity();
        int over = host.idempotencyCache.size() - capacity;
        if (over <= 0) {
            return;
        }
        host.idempotencyCache.entrySet().stream()
                .sorted(java.util.Comparator.comparing(entry -> entry.getValue().storedAt()))
                .limit(over)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(host.idempotencyCache::remove);
    }
}
