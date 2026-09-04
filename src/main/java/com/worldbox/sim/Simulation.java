package com.worldbox.sim;

import com.worldbox.config.Config;
import com.worldbox.util.Rng;
import com.worldbox.world.VoxelWorld;
import com.worldbox.world.WorldGen;
import com.worldbox.world.WorldGrid;

public class Simulation {

  public static GameState createInitialState() {
    return createInitialState(Config.WORLD_SEED + (long) (Math.random() * 1_000_000));
  }

  public static GameState createInitialState(long seed) {
    WorldGrid grid = new WorldGrid();
    WorldGen.generate(grid, seed);

    GameState state = new GameState();
    state.grid = grid;
    state.rng = new Rng(seed + 99);
    state.voxels = new VoxelWorld(grid);
    for (int z = 0; z < grid.rows; z++) {
      for (int x = 0; x < grid.cols; x++) state.voxels.resyncHeight(grid, x, z);
    }

    seedWanderers(state, 30);
    Wildlife.spawn(state, seed);
    return state;
  }

  // The world starts with no nations at all - just people. Nations only
  // come into existence when an isolated wanderer (see Population.java)
  // gives up looking for a settlement to join and founds one.
  private static void seedWanderers(GameState state, int count) {
    WorldGrid grid = state.grid;
    int spawned = 0, attempts = 0;
    while (spawned < count && attempts < 3000) {
      attempts++;
      int x = (int) state.rng.range(0, grid.cols);
      int y = (int) state.rng.range(0, grid.rows);
      if (!grid.inBounds(x, y) || grid.terrain[grid.idx(x, y)] == Config.WATER) continue;
      state.humans.add(Population.createAdult(x + 0.5, y + 0.5, -1, -1));
      spawned++;
    }
  }

  /** How many voxel cells' worth of pending water flow to resolve per
   * tick (see VoxelWorld.tickWaterFlow) - cheap per cell (a handful of
   * neighbor lookups), but a huge dug-out area could otherwise queue
   * tens of thousands of them at once, so this is spread out instead of
   * drained in a single tick. */
  private static final int WATER_FLOW_BUDGET = 800;

  public static void tick(GameState state) {
    state.tick++;
    for (long coarseKey : state.voxels.tickWaterFlow(WATER_FLOW_BUDGET)) {
      int cx = (int) (coarseKey % state.grid.cols);
      int cz = (int) (coarseKey / state.grid.cols);
      state.voxels.resyncHeight(state.grid, cx, cz);
      state.grid.markDirtyIdx(state.grid.idx(cx, cz));
    }
    Population.update(state);
    Wildlife.update(state);
    Settlement.update(state);
    Nation.update(state);
    Economy.update(state);
    Government.update(state);
    Military.update(state);
    Diplomacy.update(state);
    Events.update(state);
    Weather.update(state);
  }
}
