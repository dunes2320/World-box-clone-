package com.worldbox.tools;

import com.worldbox.config.Config;
import com.worldbox.sim.Events;
import com.worldbox.sim.GameState;
import com.worldbox.sim.Nation;
import com.worldbox.sim.Population;
import com.worldbox.sim.Weather;
import com.worldbox.world.VoxelWorld;
import com.worldbox.world.WorldGrid;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class GodTools {

  public static class ToolDef {
    public final String id, name, group;
    public ToolDef(String id, String name, String group) { this.id = id; this.name = name; this.group = group; }
  }

  /** Group names double as the toolbar's tab labels (see GameHud) - Select
   * is pinned outside the tabs since it's the default/most-used tool. */
  public static final List<ToolDef> TOOLS = List.of(
      new ToolDef("select", "Select", "Select"),
      new ToolDef("water", "Water", "Terrain"),
      new ToolDef("sand", "Sand", "Terrain"),
      new ToolDef("grass", "Grass", "Terrain"),
      new ToolDef("dirt", "Dirt", "Terrain"),
      new ToolDef("stone", "Mountain", "Terrain"),
      new ToolDef("dig", "Dig", "Terrain"),
      new ToolDef("build", "Build", "Terrain"),
      new ToolDef("forest", "Plant Forest", "Terrain"),
      new ToolDef("human", "Wanderer", "Civilizations"),
      new ToolDef("foundNation", "Found Nation", "Civilizations"),
      new ToolDef("monster", "Kaiju", "Creatures"),
      new ToolDef("zombie", "Outbreak", "Creatures"),
      new ToolDef("fire", "Fire", "Disasters"),
      new ToolDef("extinguish", "Extinguish", "Disasters"),
      new ToolDef("storm", "Call Storm", "Disasters"),
      new ToolDef("meteor", "Meteor", "Disasters"),
      new ToolDef("nuke", "Nuke", "Disasters"),
      new ToolDef("earthquake", "Earthquake", "Disasters"),
      new ToolDef("tornado", "Tornado", "Disasters"),
      new ToolDef("blessing", "Blessing", "Disasters")
  );

  public static final Set<String> CONTINUOUS_TOOLS = new LinkedHashSet<>(List.of(
      "water", "sand", "grass", "dirt", "stone", "dig", "build", "forest", "fire", "extinguish"));

  private interface CellFn { void apply(int x, int y); }

  /** forEachInRadius measures from the cell-center point (cx+0.5, cz+0.5),
   * so even the single cell directly under the cursor is 0.707 away from
   * it - a radius of exactly 0 (brush size 1) would visit nothing at all. */
  public static double brushRadius(int size) { return Math.max(0.75, size - 1); }

  private static void forEachInBrush(WorldGrid grid, int cx, int cz, int size, CellFn fn) {
    double r = brushRadius(size);
    grid.forEachInRadius(cx + 0.5, cz + 0.5, r, (x, y, d) -> fn.apply(x, y));
  }

  private static void paintTerrain(GameState state, int cx, int cz, int size, byte terrainType) {
    WorldGrid grid = state.grid;
    VoxelWorld voxels = state.voxels;
    forEachInBrush(grid, cx, cz, size, (x, y) -> {
      boolean wasWater = grid.terrain[grid.idx(x, y)] == Config.WATER;
      grid.setTerrain(x, y, terrainType);
      if (terrainType == Config.WATER) {
        voxels.paintWaterColumn(x, y);
      } else if (wasWater) {
        voxels.fillColumnSolid(x, y, VoxelWorld.blockForTerrain(terrainType));
      } else {
        voxels.paintColumnSurface(x, y, VoxelWorld.blockForTerrain(terrainType));
      }
      voxels.resyncHeight(grid, x, y);
    });
  }

  /** Returns true if the tool actually mutated something. */
  public static boolean apply(GameState state, String toolId, int cx, int cz, int brushSize) {
    WorldGrid grid = state.grid;
    if (!grid.inBounds(cx, cz)) return false;

    switch (toolId) {
      case "water": paintTerrain(state, cx, cz, brushSize, Config.WATER); return true;
      case "sand": paintTerrain(state, cx, cz, brushSize, Config.SAND); return true;
      case "grass": paintTerrain(state, cx, cz, brushSize, Config.GRASS); return true;
      case "dirt": paintTerrain(state, cx, cz, brushSize, Config.DIRT); return true;
      case "stone": paintTerrain(state, cx, cz, brushSize, Config.STONE); return true;

      case "dig":
        forEachInBrush(grid, cx, cz, brushSize, (x, y) -> {
          state.voxels.digColumn(x, y);
          state.voxels.resyncHeight(grid, x, y);
          grid.markDirtyIdx(grid.idx(x, y));
        });
        return true;

      case "build":
        forEachInBrush(grid, cx, cz, brushSize, (x, y) -> {
          state.voxels.buildColumn(x, y, VoxelWorld.DIRT);
          state.voxels.resyncHeight(grid, x, y);
          grid.markDirtyIdx(grid.idx(x, y));
        });
        return true;

      case "forest":
        forEachInBrush(grid, cx, cz, brushSize, (x, y) -> {
          int i = grid.idx(x, y);
          if (grid.terrain[i] == Config.GRASS && grid.resource[i] == Config.RES_NONE && Math.random() < 0.7) {
            grid.resource[i] = Config.RES_FOREST;
            grid.resourceAmount[i] = Config.RESOURCE_INFO.get(Config.RES_FOREST).yieldAmt * 6;
            grid.markDirtyIdx(i);
          }
        });
        return true;

      case "human": {
        int i = grid.idx(cx, cz);
        if (grid.isLand(i) && state.humans.size() < Config.MAX_HUMANS) {
          state.humans.add(Population.createHuman(cx + 0.5, cz + 0.5, -1, -1));
          return true;
        }
        return false;
      }

      case "foundNation": {
        int i = grid.idx(cx, cz);
        if (grid.isBuildable(i) && grid.slopeAt(cx, cz) < 1.4 && grid.settlementAt[i] < 0) {
          Nation.foundNewNation(state, cx, cz, null);
          return true;
        }
        return false;
      }

      case "fire":
        forEachInBrush(grid, cx, cz, brushSize, (x, y) -> Events.igniteCell(grid, x, y));
        return true;

      case "extinguish":
        Events.extinguish(grid, cx, cz, brushRadius(brushSize) + 1);
        return true;

      case "storm":
        Weather.triggerStorm(state, cx + 0.5, cz + 0.5);
        return true;

      case "meteor":
        Events.explode(state, cx + 0.5, cz + 0.5, Math.max(2.5, brushSize + 1.5), true);
        return true;

      case "nuke":
        Events.explode(state, cx + 0.5, cz + 0.5, Math.max(6, brushSize + 5), false);
        return true;

      case "earthquake":
        Events.earthquake(state, cx + 0.5, cz + 0.5, Math.max(4, brushSize + 3));
        return true;

      case "tornado":
        Events.spawnTornado(state, cx + 0.5, cz + 0.5);
        return true;

      case "monster":
        Events.spawnMonster(state, cx + 0.5, cz + 0.5);
        return true;

      case "zombie":
        Events.zombieOutbreak(state, cx + 0.5, cz + 0.5, Math.max(2, brushSize), 1 + brushSize);
        return true;

      case "blessing":
        Events.blessing(state, cx + 0.5, cz + 0.5, Math.max(4, brushSize + 3));
        return true;

      default:
        return false;
    }
  }
}
