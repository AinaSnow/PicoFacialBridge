package dev.pico.facialprobe;

/** One bounded acquisition/send pass per monotonic deadline; never catches up in a burst. */
final class DeadlinePacer {
    private final long interval;
    private long deadline;
    DeadlinePacer(int hz, long now) {
        if (hz < 30 || hz > 120) throw new IllegalArgumentException("rate must be 30..120 Hz");
        interval = 1_000_000_000L / hz;
        deadline = now;
    }
    long finishAndWaitMillis(long now) {
        deadline += interval;
        if (deadline <= now) deadline = now + interval;
        // Round only the remaining wait. Oversleep is deducted from the next interval.
        return Math.max(1, (deadline - now + 999_999L) / 1_000_000L);
    }
    void reset(long now) { deadline = now; }
}
