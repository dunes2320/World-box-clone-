package com.worldbox.sim;

import com.worldbox.world.WorldGrid;

import java.util.ArrayList;
import java.util.List;

/** Clouds drift across the map as ambient atmosphere; any cloud can build
 * into a storm that actually rains - putting out fire underneath it. This
 * is the player's real answer to a wildfire that's gotten away from them
 * (beyond manually brushing it out cell by cell with Extinguish): trigger
 * a storm and let it do the work. */
public class Weather {
  private static final int MAX_CLOUDS = 6;
  private static final int MAX_CONCURRENT_STORMS = 2;
  // At the old 0.05/tick, a cloud took ~5000+ ticks (well over a decade of
  // game time) to cross a 256-cell map, so in practice clouds spent nearly
  // all their time sitting just off the edges - which read as "clouds are
  // outside the world" since the player is almost always looking at the
  // populated middle, not the fringes. This crosses the map in well under
  // two minutes of real time even at 1x.
  private static final double CLOUD_SPEED = 0.6;

  public static void update(GameState state) {
    WorldGrid grid = state.grid;

    while (state.clouds.size() < MAX_CLOUDS) spawnCloud(state);

    int stormCount = 0;
    for (Cloud c : state.clouds) if (c.stormy) stormCount++;

    List<Cloud> keep = new ArrayList<>();
    for (Cloud c : state.clouds) {
      // clouds only move once per SIMULATION TICK (a few times a second),
      // but the renderer draws every real frame - without prevX/prevZ to
      // interpolate between (the same fix already applied to armies and
      // humans), a cloud visibly jumped forward once per tick instead of
      // drifting smoothly, which read as jitter.
      c.prevX = c.x; c.prevZ = c.z;
      c.x += c.vx;
      c.z += c.vz;

      if (!c.stormy && stormCount < MAX_CONCURRENT_STORMS && Math.random() < 0.0015) {
        c.stormy = true;
        c.stormTimer = 60 + (int) (Math.random() * 90);
        stormCount++;
        EventLog.log(state, "disaster", "Storm clouds gathered " + nearDescription(state, c.x, c.z));
      }

      if (c.stormy) {
        rainAt(state, c.x, c.z, c.radius);
        c.stormTimer--;
        if (c.stormTimer <= 0) c.stormy = false;
      }

      // off the map on any side - respawn fresh rather than wrapping, so
      // a cloud's path doesn't look like it's teleporting back to a fixed
      // spawn edge every time
      if (c.x < -8 || c.x > grid.cols + 8 || c.z < -8 || c.z > grid.rows + 8) continue;
      keep.add(c);
    }
    while (keep.size() < MAX_CLOUDS) { spawnCloudInto(state, keep); }
    state.clouds = keep;
  }

  private static void rainAt(GameState state, double x, double z, double radius) {
    WorldGrid grid = state.grid;
    grid.forEachInRadius(x, z, radius, (gx, gy, d) -> {
      int i = grid.idx(gx, gy);
      if (grid.burning[i]) {
        grid.burning[i] = false;
        grid.burnTimer[i] = 0;
        grid.burningCells.remove(i);
        grid.markDirtyIdx(i);
      }
    });
  }

  private static String nearDescription(GameState state, double x, double z) {
    Settlement best = null;
    double bestD = Double.MAX_VALUE;
    for (Settlement s : state.settlements.values()) {
      if (s.abandoned) continue;
      double d = Math.hypot(s.x - x, s.z - z);
      if (d < bestD) { bestD = d; best = s; }
    }
    if (best != null && bestD < 18) return "over " + best.name;
    return "over the wilds";
  }

  private static void spawnCloud(GameState state) { spawnCloudInto(state, state.clouds); }

  private static void spawnCloudInto(GameState state, List<Cloud> into) {
    WorldGrid grid = state.grid;
    boolean fromWest = Math.random() < 0.5;
    double x = fromWest ? -6 : grid.cols + 6;
    double z = Math.random() * grid.rows;
    double vx = (fromWest ? 1 : -1) * CLOUD_SPEED * (0.6 + Math.random() * 0.8);
    double vz = (Math.random() - 0.5) * CLOUD_SPEED * 0.6;
    // widened from 4-9 - clouds were all reading as roughly the same size;
    // this gives a real mix of small wisps and big sprawling ones
    double radius = 2.5 + Math.random() * 9;
    into.add(new Cloud(x, z, vx, vz, radius));
  }

  /** A player-triggered storm - the real "put this fire out" tool, not
   * just the manual Extinguish brush. Reuses an existing cloud if one is
   * close enough, otherwise conjures one right overhead. */
  public static void triggerStorm(GameState state, double x, double z) {
    Cloud nearest = null;
    double bestD = Double.MAX_VALUE;
    for (Cloud c : state.clouds) {
      double d = Math.hypot(c.x - x, c.z - z);
      if (d < bestD) { bestD = d; nearest = c; }
    }
    Cloud c = (nearest != null && bestD < 10) ? nearest : new Cloud(x, z, 0, 0, 6);
    if (nearest == null || bestD >= 10) state.clouds.add(c);
    c.x = x; c.z = z;
    c.stormy = true;
    c.stormTimer = 100;
    EventLog.log(state, "disaster", "A storm was called down " + nearDescription(state, x, z));
  }
}
