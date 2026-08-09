package com.worldbox.sim;

import com.worldbox.config.Config;
import com.worldbox.util.Rng;
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

    seedWanderers(state, 30);
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
      state.humans.add(Population.createHuman(x + 0.5, y + 0.5, -1, -1));
      spawned++;
    }
  }

  public static void tick(GameState state) {
    state.tick++;
    Population.update(state);
    Settlement.update(state);
    Nation.update(state);
    Economy.update(state);
    Military.update(state);
    Diplomacy.update(state);
    Events.update(state);
  }
}
