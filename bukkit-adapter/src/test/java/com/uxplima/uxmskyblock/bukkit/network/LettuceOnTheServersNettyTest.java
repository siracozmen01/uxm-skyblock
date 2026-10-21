package com.uxplima.uxmskyblock.bukkit.network;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.uxplima.uxmlib.redis.LettuceRedisBus;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Lettuce talks to a real Redis over the netty the server carries, which is the only netty this plugin's jar may
 * use: the library's packet pipeline speaks to the server's own channels, and a relocated copy of netty in the jar
 * would rewrite those references too. The test runtime is held to the server's netty version, so a Lettuce that
 * needs another one fails here and not on a cluster.
 */
@Tag("redis-integration")
@Testcontainers
class LettuceOnTheServersNettyTest {

    /** The netty line the server ships; the build holds the test runtime to it. */
    private static final String SERVER_NETTY = "4.2.17.Final";

    @Container
    @SuppressWarnings("resource") // the extension stops it
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Test
    void aFramePublishedOnOneBusArrivesOnAnother() throws Exception {
        io.netty.util.Version transport = java.util.Objects.requireNonNull(
                io.netty.util.Version.identify().get("netty-transport"), "netty-transport is on the class path");
        assertThat(transport.artifactVersion())
                .describedAs("the test runtime carries the server's netty, not the one Lettuce was built on")
                .isEqualTo(SERVER_NETTY);
        String uri = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        // Each bus shuts its own client down when it closes.
        try (LettuceRedisBus out = new LettuceRedisBus(RedisClient.create(uri), warning -> {});
                LettuceRedisBus in = new LettuceRedisBus(RedisClient.create(uri), warning -> {})) {
            CompletableFuture<byte[]> arrived = new CompletableFuture<>();
            in.subscribe("uxmskyblock:test", arrived::complete);
            // A subscription is confirmed asynchronously; publish until the first frame lands.
            for (int attempt = 0; attempt < 50 && !arrived.isDone(); attempt++) {
                out.publish("uxmskyblock:test", "hello".getBytes(StandardCharsets.UTF_8));
                Thread.sleep(100);
            }

            assertThat(new String(arrived.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8))
                    .isEqualTo("hello");
            assertThat(out.healthy()).isTrue();
        }
    }
}
