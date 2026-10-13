package com.uxplima.uxmskyblock.core.application.parkour;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The way a run went, as a ghost replays it: where the runner stood and which way they faced, sampled through the
 * run, each place measured from the start plate so the path stays with the course.
 *
 * <p>Between two samples the ghost is drawn on the straight line from one to the other. Before the run it waits at
 * the first, and after the last it stands at the finish.
 */
public final class GhostPath {

    /** Where the runner was a moment into the run, measured from the start plate. */
    public record Pose(long millis, double x, double y, double z, float yaw) {}

    /** The first byte of an encoded path, which says how the rest is written. */
    private static final byte FORMAT = 1;

    private static final int POSE_BYTES = Integer.BYTES + 4 * Float.BYTES;

    private final List<Pose> poses;

    private GhostPath(List<Pose> poses) {
        this.poses = List.copyOf(poses);
    }

    /** The samples, in the order the run went. */
    public List<Pose> poses() {
        return poses;
    }

    /** How long the run went, to its last sample. */
    public long millis() {
        return poses.isEmpty() ? 0 : poses.getLast().millis();
    }

    /** Where the ghost stands {@code millis} into the run. */
    public Pose at(long millis) {
        if (poses.isEmpty()) {
            return new Pose(millis, 0, 0, 0, 0);
        }
        if (millis <= poses.getFirst().millis()) {
            return poses.getFirst();
        }
        // The last sample at or before the moment: the samples are in order, so a binary search finds it.
        int low = 0;
        int high = poses.size() - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (poses.get(middle).millis() <= millis) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        Pose from = poses.get(low);
        if (low == poses.size() - 1) {
            return from;
        }
        Pose to = poses.get(low + 1);
        double part = (double) (millis - from.millis()) / (to.millis() - from.millis());
        return new Pose(
                millis,
                from.x() + (to.x() - from.x()) * part,
                from.y() + (to.y() - from.y()) * part,
                from.z() + (to.z() - from.z()) * part,
                from.yaw() + wrapped(to.yaw() - from.yaw()) * (float) part);
    }

    /** The turn from one facing to the other, the short way round. */
    private static float wrapped(float turn) {
        float within = turn % 360;
        if (within > 180) {
            return within - 360;
        }
        return within < -180 ? within + 360 : within;
    }

    /** The path as bytes, for the database. */
    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(1 + Integer.BYTES + poses.size() * POSE_BYTES);
        buffer.put(FORMAT).putInt(poses.size());
        for (Pose pose : poses) {
            buffer.putInt((int) pose.millis())
                    .putFloat((float) pose.x())
                    .putFloat((float) pose.y())
                    .putFloat((float) pose.z())
                    .putFloat(pose.yaw());
        }
        return buffer.array();
    }

    /** A path read back from its bytes; one this version cannot read is an empty path. */
    public static GhostPath decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.remaining() < 1 + Integer.BYTES || buffer.get() != FORMAT) {
            return new GhostPath(List.of());
        }
        int count = buffer.getInt();
        if (count < 0 || (long) count * POSE_BYTES != buffer.remaining()) {
            return new GhostPath(List.of());
        }
        List<Pose> poses = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            poses.add(new Pose(
                    buffer.getInt(), buffer.getFloat(), buffer.getFloat(), buffer.getFloat(), buffer.getFloat()));
        }
        return new GhostPath(poses);
    }

    /**
     * Records a run as it goes: a sample is kept once {@code every} milliseconds have passed since the last one, so a
     * runner who stands still costs nothing, and none is kept past {@code most}.
     */
    public static final class Recorder {

        private final long every;
        private final int most;
        private final List<Pose> poses = new ArrayList<>();

        public Recorder(long every, int most) {
            if (every < 1 || most < 2) {
                throw new IllegalArgumentException("a sample is taken at least every millisecond, and two are kept");
            }
            this.every = every;
            this.most = most;
        }

        /** The runner is here {@code millis} into the run. */
        public synchronized void at(long millis, double x, double y, double z, float yaw) {
            if (poses.size() >= most) {
                return;
            }
            if (!poses.isEmpty() && millis - poses.getLast().millis() < every) {
                return;
            }
            poses.add(new Pose(millis, x, y, z, yaw));
        }

        /** The run starts again: what was kept goes. */
        public synchronized void clear() {
            poses.clear();
        }

        /** The run so far, ending where the runner stands at {@code millis}, the finish. */
        public synchronized GhostPath until(long millis, double x, double y, double z, float yaw) {
            List<Pose> run = new ArrayList<>(poses);
            if (run.isEmpty() || run.getLast().millis() < millis) {
                run.add(new Pose(millis, x, y, z, yaw));
            }
            return new GhostPath(run);
        }
    }
}
