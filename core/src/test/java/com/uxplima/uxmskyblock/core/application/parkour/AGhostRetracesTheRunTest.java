package com.uxplima.uxmskyblock.core.application.parkour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A run is sampled as it goes, kept as bytes, and replayed by a ghost that follows the line between samples. */
class AGhostRetracesTheRunTest {

    @Test
    @DisplayName(
            "A sample is kept once the interval has passed, none past the most but the finish, and a new start clears them")
    void theRunIsSampled() {
        GhostPath.Recorder recorder = new GhostPath.Recorder(100, 4);
        recorder.at(0, 0, 0, 0, 0);
        recorder.at(50, 9, 9, 9, 0);
        recorder.at(100, 1, 0, 0, 0);
        recorder.at(250, 2, 0, 0, 0);
        recorder.at(260, 3, 0, 0, 0);

        assertThat(recorder.until(400, 4, 0, 0, 0).poses())
                .extracting(GhostPath.Pose::millis)
                .containsExactly(0L, 100L, 250L, 400L);

        recorder.at(500, 5, 0, 0, 0);
        recorder.at(600, 6, 0, 0, 0);
        // Four samples are the most; the finish is the end of the path all the same.
        assertThat(recorder.until(700, 7, 0, 0, 0).poses())
                .extracting(GhostPath.Pose::millis)
                .containsExactly(0L, 100L, 250L, 500L, 700L);

        recorder.clear();
        assertThat(recorder.until(30, 7, 1, 2, 90).poses()).containsExactly(new GhostPath.Pose(30, 7, 1, 2, 90));
        assertThatThrownBy(() -> new GhostPath.Recorder(0, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GhostPath.Recorder(10, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "The ghost waits at the start, follows the line between samples, turns the short way, and stands at the finish")
    void theGhostFollows() {
        GhostPath.Recorder recorder = new GhostPath.Recorder(1, 100);
        recorder.at(100, 0, 64, 0, 170);
        recorder.at(300, 10, 66, -4, -170);
        GhostPath path = recorder.until(500, 10, 70, -4, -170);

        assertThat(path.millis()).isEqualTo(500);
        assertThat(path.at(0)).isEqualTo(new GhostPath.Pose(100, 0, 64, 0, 170));
        GhostPath.Pose half = path.at(200);
        assertThat(half.x()).isCloseTo(5, within(1e-9));
        assertThat(half.y()).isCloseTo(65, within(1e-9));
        assertThat(half.z()).isCloseTo(-2, within(1e-9));
        assertThat(half.yaw()).isCloseTo(180, within(1e-3f));
        assertThat(path.at(400).y()).isCloseTo(68, within(1e-9));
        GhostPath.Recorder back = new GhostPath.Recorder(1, 100);
        back.at(0, 0, 0, 0, -170);
        assertThat(back.until(100, 0, 0, 0, 170).at(50).yaw())
                .describedAs("the other way round too")
                .isCloseTo(-180, within(1e-3f));
        assertThat(path.at(300).x()).isEqualTo(10);
        assertThat(path.at(9_000)).isEqualTo(new GhostPath.Pose(500, 10, 70, -4, -170));
        assertThat(GhostPath.decode(new byte[0]).at(42)).isEqualTo(new GhostPath.Pose(42, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("A path read back from its bytes is the same, and bytes it cannot read are an empty path")
    void thePathIsKept() {
        GhostPath.Recorder recorder = new GhostPath.Recorder(1, 100);
        recorder.at(0, 0.5, 64, 0.5, -90);
        recorder.at(250, 3.25, 65.5, -1.75, 45);
        GhostPath path = recorder.until(700, 8, 66, 2, 0);

        assertThat(GhostPath.decode(path.encode()).poses()).isEqualTo(path.poses());
        byte[] bytes = path.encode();
        bytes[0] = 9;
        assertThat(GhostPath.decode(bytes).poses()).isEmpty();
        byte[] cut = java.util.Arrays.copyOf(path.encode(), path.encode().length - 1);
        assertThat(GhostPath.decode(cut).poses()).isEmpty();
        assertThat(GhostPath.decode(new byte[] {1, -1, -1, -1, -1}).poses()).isEmpty();
    }
}
