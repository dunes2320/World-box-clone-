package com.game.sim;

import java.util.Random;

/**
 * Owns the whole simulation state and advances it one fixed tick at a time.
 *
 * <p>Determinism contract: the same seed plus the same sequence of god-tool
 * commands produces a bit-identical world. That means one seeded {@link Random}
 * for everything, no {@code Math.random()}, no wall-clock reads, and no
 * iteration over hash-ordered collections anywhere inside {@link #tick()}.
 */
public final class Simulation {

    private final long seed;
    private final World world;
    private final Random random;

    private long tickCount;

    public Simulation(long seed) {
        this.seed = seed;
        this.world = WorldGen.generate(seed);
        // Offset from the world seed so the gameplay stream is independent of
        // the terrain stream: regenerating terrain must not shift gameplay rolls.
        this.random = new Random(seed ^ 0x5DEECE66DL);
    }

    public long getSeed() {
        return seed;
    }

    public World getWorld() {
        return world;
    }

    public Random getRandom() {
        return random;
    }

    public long getTickCount() {
        return tickCount;
    }

    /**
     * Advances the world by exactly one fixed step.
     *
     * <p>Phase 1 has no living systems yet - the world is terrain only, by
     * design, since units arrive with the spawn tool in phase 3. The tick
     * counter still advances so the clock, speed controls and pause can all be
     * exercised and tested before there is anything to watch.
     */
    public void tick() {
        tickCount++;
    }
}
