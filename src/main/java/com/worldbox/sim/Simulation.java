package com.worldbox.sim;

import com.worldbox.config.Config;
import com.worldbox.util.Rng;
import com.worldbox.world.WorldGen;
import com.worldbox.world.WorldGrid;

import java.util.ArrayList;
import java.util.List;

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

    seedStartingNations(state, 6);
    Settlement.recomputeTerritory(state);
    return state;
  }

  private static void seedStartingNations(GameState state, int count) {
    WorldGrid grid = state.grid;
    List<WorldGen.Spot> spots = new ArrayList<>();
    int attempts = 0;
    while (spots.size() < count && attempts < 400) {
      attempts++;
      double cx = state.rng.range(grid.cols * 0.15, grid.cols * 0.85);
      double cy = state.rng.range(grid.rows * 0.15, grid.rows * 0.85);
      WorldGen.Spot spot = WorldGen.findLandSpot(grid, cx, cy, 3, state.rng);
      if (spot == null) continue;
      boolean tooClose = false;
      for (WorldGen.Spot s : spots) {
        if (Math.hypot(s.x - spot.x, s.y - spot.y) < grid.cols * 0.16) { tooClose = true; break; }
      }
      if (tooClose) continue;
      spots.add(spot);
    }
    for (WorldGen.Spot spot : spots) {
      Nation.foundNewNation(state, spot.x, spot.y, Nation.randomNationName(state.rng));
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
