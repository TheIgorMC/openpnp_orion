package org.openpnp.machine.orion.vision;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Finds out which of several candidate units belongs to the feeder the camera is looking at, by
 * activating halves of the candidates and watching whether the effect shows up (binary search).
 * Works with any {@link Probe}: fiber light on, or tape moved a little backwards.
 */
public final class OrionIdentifier {
    private OrionIdentifier() {}

    /** Activate exactly the given candidates and report whether the effect was seen at the camera. */
    public interface Probe<T> {
        String name();

        boolean test(List<T> active) throws Exception;

        /** Leave everything in its normal state (fibers off, tape forward). */
        default void restore() throws Exception {
        }
    }

    /**
     * @return the single candidate that causes the effect, or null if the effect is not seen at all
     *         or the answers are inconsistent (so the caller can fall back or ask the user).
     */
    public static <T> T search(List<T> candidates, Probe<T> probe, Consumer<String> log)
            throws Exception {
        try {
            if (candidates.isEmpty()) {
                return null;
            }
            // Sanity: with all candidates active the effect must be visible, otherwise this fiducial
            // is not one of these units (or the detection is too weak) and halving would only guess.
            if (!probe.test(candidates)) {
                log.accept(probe.name() + ": nothing seen with all " + candidates.size()
                        + " candidates active");
                return null;
            }
            List<T> set = new ArrayList<>(candidates);
            int steps = 0;
            while (set.size() > 1) {
                int half = set.size() / 2;
                List<T> first = new ArrayList<>(set.subList(0, half));
                List<T> rest = new ArrayList<>(set.subList(half, set.size()));
                steps++;
                boolean seen = probe.test(first);
                log.accept(String.format("%s: step %d, %d of %d active -> %s", probe.name(), steps,
                        first.size(), set.size(), seen ? "seen" : "not seen"));
                set = seen ? first : rest;
            }
            // Confirm: alone it must be seen.
            if (!probe.test(Collections.singletonList(set.get(0)))) {
                log.accept(probe.name() + ": final candidate not confirmed, answers inconsistent");
                return null;
            }
            return set.get(0);
        } finally {
            probe.restore();
        }
    }

    // ------------------------------------------------------------------ fiber light

    public interface Fibers<T> {
        /** Switch the fibers of exactly the units in {@code on} on, every unit of {@code all} else off. */
        void set(Collection<T> on, Collection<T> all) throws Exception;
    }

    public interface Metric {
        /** Brightness of the fiber spot region, e.g. the peak of the masked, blurred gray image. */
        double measure() throws Exception;
    }

    /** Light on vs. off: seen when the brightness rises by at least {@code threshold}. */
    public static class FiberProbe<T> implements Probe<T> {
        private final Fibers<T> fibers;
        private final Metric metric;
        private final double threshold;
        private final long settleMs;
        private final List<T> all;

        public FiberProbe(List<T> all, Fibers<T> fibers, Metric metric, double threshold, long settleMs) {
            this.all = all;
            this.fibers = fibers;
            this.metric = metric;
            this.threshold = threshold;
            this.settleMs = settleMs;
        }

        @Override
        public String name() {
            return "fiber light";
        }

        @Override
        public boolean test(List<T> active) throws Exception {
            fibers.set(Collections.<T>emptyList(), all);
            sleep();
            double off = metric.measure();
            fibers.set(active, all);
            sleep();
            double on = metric.measure();
            fibers.set(Collections.<T>emptyList(), all);
            return on - off >= threshold;
        }

        private void sleep() throws InterruptedException {
            if (settleMs > 0) {
                Thread.sleep(settleMs);
            }
        }

        @Override
        public void restore() throws Exception {
            fibers.set(Collections.<T>emptyList(), all);
        }
    }

    // ------------------------------------------------------------------ tape movement (fallback)

    public interface Mover<T> {
        /** Move the tape of all given units a little backwards (back=true) or forwards again. */
        void move(Collection<T> units, boolean back) throws Exception;
    }

    public interface FrameSource {
        Object snap() throws Exception;

        /** How much two frames differ, e.g. number of changed pixels inside the hole mask. */
        double difference(Object a, Object b);
    }

    /**
     * Tape movement: take a picture of the masked sprocket hole, move the active units back a bit,
     * take another, compare, move forward again. Slower than the light, since the tapes really move.
     */
    public static class MovementProbe<T> implements Probe<T> {
        private final Mover<T> mover;
        private final FrameSource frames;
        private final double threshold;
        private final long settleMs;
        private List<T> moved = new ArrayList<>();

        public MovementProbe(Mover<T> mover, FrameSource frames, double threshold, long settleMs) {
            this.mover = mover;
            this.frames = frames;
            this.threshold = threshold;
            this.settleMs = settleMs;
        }

        @Override
        public String name() {
            return "tape movement";
        }

        @Override
        public boolean test(List<T> active) throws Exception {
            Object before = frames.snap();
            moved = new ArrayList<>(active);
            mover.move(moved, true);
            if (settleMs > 0) {
                Thread.sleep(settleMs);
            }
            Object after = frames.snap();
            mover.move(moved, false);
            moved = new ArrayList<>();
            return frames.difference(before, after) >= threshold;
        }

        @Override
        public void restore() throws Exception {
            if (!moved.isEmpty()) { // interrupted between back and forward
                mover.move(moved, false);
                moved = new ArrayList<>();
            }
        }
    }
}
