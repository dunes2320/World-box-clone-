package com.game.sim;

/**
 * Fixed-timestep accumulator. Render frames arrive at whatever rate the GPU
 * manages; the simulation must advance in equal, reproducible steps regardless,
 * or a fast machine would run the world faster than a slow one.
 *
 * <p>Lives in the sim package with no libGDX types anywhere near it, so the
 * accumulation logic - the part that is genuinely easy to get subtly wrong -
 * can be unit-tested directly.
 */
public final class SimClock {

    /** Speed multipliers the UI exposes. Index 0 is paused. */
    public static final int[] SPEEDS = {0, 1, 2, 5};

    /** See {@link #advance(double)} - absorbs floating-point accumulation error. */
    private static final double TICK_EPSILON = 1e-9;

    private final double secondsPerTick;
    private final int maxTicksPerFrame;

    private double accumulator;
    private int speed = 1;

    public SimClock() {
        this(SimConfig.SECONDS_PER_TICK, SimConfig.MAX_TICKS_PER_FRAME);
    }

    public SimClock(double secondsPerTick, int maxTicksPerFrame) {
        if (secondsPerTick <= 0.0) {
            throw new IllegalArgumentException("secondsPerTick must be positive");
        }
        this.secondsPerTick = secondsPerTick;
        this.maxTicksPerFrame = maxTicksPerFrame;
    }

    public int getSpeed() {
        return speed;
    }

    /**
     * Sets the speed multiplier. Setting it to 0 (pause) also drops any
     * partially accumulated time, so unpausing does not immediately fire a
     * burst of ticks that built up while the player was reading a panel.
     */
    public void setSpeed(int speed) {
        this.speed = Math.max(0, speed);
        if (this.speed == 0) {
            accumulator = 0.0;
        }
    }

    public boolean isPaused() {
        return speed == 0;
    }

    /**
     * Feeds one frame's elapsed time in and returns how many simulation ticks
     * should run now.
     *
     * <p>When the frame time is so long that catching up would need more than
     * {@code maxTicksPerFrame} ticks, the excess is discarded rather than
     * carried: keeping it would make the next frame even slower, which would
     * queue more ticks still. The world runs briefly in slow motion instead of
     * spiralling.
     */
    public int advance(double deltaSeconds) {
        if (speed == 0 || deltaSeconds <= 0.0) {
            return 0;
        }
        accumulator += deltaSeconds * speed;
        // Sixty frames of 1/60s sum to 0.9999999999999999, not 1.0, so a
        // straight division reports nine ticks for a second that has visibly
        // passed - the world would run about 10% slow at a steady 60fps. The
        // epsilon is far below any real frame time and simply lets a value
        // sitting a rounding error short of a tick boundary count as reaching
        // it.
        int ticks = (int) ((accumulator + TICK_EPSILON) / secondsPerTick);
        if (ticks <= 0) {
            return 0;
        }
        if (ticks > maxTicksPerFrame) {
            accumulator = 0.0;
            return maxTicksPerFrame;
        }
        accumulator -= ticks * secondsPerTick;
        if (accumulator < 0.0) {
            accumulator = 0.0;
        }
        return ticks;
    }
}
