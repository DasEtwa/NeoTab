package de.NeoTab.neotab;

/** Per-session monotonic time, independent of scheduler cadence and wall-clock corrections. */
record ElapsedSession(long startedAtNanos, long accumulatedNanos, boolean paused) {
    static ElapsedSession start(long now) {
        return new ElapsedSession(now, 0L, false);
    }

    long elapsedNanos(long now) {
        return accumulatedNanos + (paused ? 0L : Math.max(0L, now - startedAtNanos));
    }

    ElapsedSession pause(long now) {
        return paused ? this : new ElapsedSession(now, elapsedNanos(now), true);
    }

    ElapsedSession resume(long now) {
        return paused ? new ElapsedSession(now, accumulatedNanos, false) : this;
    }
}
