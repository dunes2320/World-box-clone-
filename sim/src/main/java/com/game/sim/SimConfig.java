package com.game.sim;

/** Every tunable the simulation reads, in one place. */
public final class SimConfig {

    private SimConfig() {
    }

    // ---- world dimensions ----

    public static final int WORLD_SIZE = 128;
    public static final int TILE_COUNT = WORLD_SIZE * WORLD_SIZE;
    public static final int CHUNK_SIZE = 16;
    public static final int CHUNKS_PER_AXIS = WORLD_SIZE / CHUNK_SIZE;
    public static final int CHUNK_COUNT = CHUNKS_PER_AXIS * CHUNKS_PER_AXIS;

    // ---- timing ----

    /** Fixed simulation rate. Render framerate is entirely independent of this. */
    public static final int TICKS_PER_SECOND = 10;
    public static final double SECONDS_PER_TICK = 1.0 / TICKS_PER_SECOND;
    /**
     * Ceiling on how many ticks a single frame may run to catch up. Without it,
     * one slow frame queues a backlog that takes even longer to process, which
     * queues a bigger backlog - the classic death spiral.
     */
    public static final int MAX_TICKS_PER_FRAME = 8;

    // ---- terrain elevation ----

    /** Anything below this is underwater. Land heights are measured from here. */
    public static final float SEA_LEVEL = 0.0f;
    public static final float DEEP_WATER_LEVEL = -1.5f;
    public static final float SAND_LEVEL = 0.7f;
    public static final float HILL_LEVEL = 4.0f;
    public static final float MOUNTAIN_LEVEL = 7.0f;
    public static final float SNOW_LEVEL = 10.0f;

    /** Clamp range for terraforming, so the brush cannot dig to infinity. */
    public static final float MIN_HEIGHT = -6.0f;
    public static final float MAX_HEIGHT = 14.0f;

    /** Fertility above this grows forest rather than plain grass. */
    public static final float FOREST_FERTILITY = 0.55f;

    // ---- world generation ----

    public static final float NOISE_SCALE = 0.028f;
    public static final int ELEVATION_OCTAVES = 5;
    public static final float FERTILITY_SCALE = 0.045f;
    public static final int FERTILITY_OCTAVES = 3;
    /**
     * How hard the island falloff pulls the map edge underwater. The world is
     * a bounded island rather than a wrapping plane, so the border is always
     * deep water and the player can see where the world stops.
     *
     * <p>Applied on a fourth-power ramp (see WorldGen.islandFalloff), which
     * stays near zero across the middle of the map and then climbs steeply.
     * A gentler ramp drowned the interior as well as the rim - measured at
     * ~9% land across twelve seeds, which is an ocean with specks in it.
     */
    public static final float ISLAND_FALLOFF = 20.0f;
    /**
     * Normalised fbm mostly lands in about [-0.45, 0.45] rather than the full
     * [-1, 1], so the amplitude has to be generously larger than the height
     * range it is meant to fill or the peaks never reach the mountain and snow
     * bands at all.
     */
    public static final float ELEVATION_AMPLITUDE = 22.0f;
    public static final float ELEVATION_BIAS = 2.5f;

    // ---- terraform brush ----

    public static final int MIN_BRUSH_RADIUS = 1;
    public static final int MAX_BRUSH_RADIUS = 12;
    public static final float TERRAFORM_STRENGTH = 0.55f;
}
