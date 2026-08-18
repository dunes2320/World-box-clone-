package com.worldbox.sim;

import com.worldbox.config.Config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DiplomacyManager implements java.io.Serializable {
  public static class Relation implements java.io.Serializable {
    public String status = Config.PEACE;
    public double score = 0;
    public int truceTimer = 0;
    /** Tick this pair's current war began, or -1 if not at war - lets a
     * war's real elapsed length gate whether either side can sue for
     * peace on their own (see Diplomacy.update). */
    public int warStartTick = -1;
    /** True once the player (not either nation's own AI) declared this
     * war via forceWar - while set, the AI on either side is never
     * allowed to sue for peace itself; only the player's own forcePeace/
     * forceAlliance can end it. Cleared whenever a player action ends the
     * war, or implicitly when one side is destroyed and the whole
     * relation entry is purged (see removeNation). */
    public boolean playerLocked = false;
  }

  public final Map<String, Relation> relations = new LinkedHashMap<>();

  private static String key(int a, int b) { return a < b ? a + "-" + b : b + "-" + a; }

  private Relation get(int a, int b) {
    String k = key(a, b);
    return relations.computeIfAbsent(k, kk -> new Relation());
  }

  public String getStatus(int a, int b) { return a == b ? Config.ALLIANCE : get(a, b).status; }
  public double getScore(int a, int b) { return a == b ? 100 : get(a, b).score; }

  public void setStatus(int a, int b, String status) { setStatus(a, b, status, 0); }
  public void setStatus(int a, int b, String status, int truceTicks) {
    Relation r = get(a, b);
    r.status = status;
    if (status.equals(Config.TRUCE)) r.truceTimer = truceTicks > 0 ? truceTicks : 200;
  }

  public void adjustScore(int a, int b, double delta) {
    Relation r = get(a, b);
    r.score = clamp(r.score + delta, -100, 100);
  }

  public void markWarStart(int a, int b, int tick) { get(a, b).warStartTick = tick; }
  public int getWarStartTick(int a, int b) { return get(a, b).warStartTick; }
  public void setPlayerLocked(int a, int b, boolean locked) { get(a, b).playerLocked = locked; }
  public boolean isPlayerLocked(int a, int b) { return get(a, b).playerLocked; }

  public static class PairInfo {
    public final int other;
    public final Relation relation;
    public PairInfo(int other, Relation relation) { this.other = other; this.relation = relation; }
  }

  public List<PairInfo> pairsInvolving(int nationId) {
    List<PairInfo> out = new ArrayList<>();
    for (Map.Entry<String, Relation> e : relations.entrySet()) {
      String[] parts = e.getKey().split("-");
      int a = Integer.parseInt(parts[0]), b = Integer.parseInt(parts[1]);
      if (a == nationId || b == nationId) out.add(new PairInfo(a == nationId ? b : a, e.getValue()));
    }
    return out;
  }

  private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

  /** Purges every relation entry involving a nation that just died. Without
   * this, `relations` only ever grew - nation IDs are never reused, so
   * over a long game where nations keep rising and falling this map (and
   * the full scan over it every tick in Diplomacy.update) would keep
   * growing for as long as the game ran, long after most of those nations
   * were gone. */
  public void removeNation(int nationId) {
    relations.keySet().removeIf(k -> {
      int dash = k.indexOf('-');
      return Integer.parseInt(k, 0, dash, 10) == nationId || Integer.parseInt(k, dash + 1, k.length(), 10) == nationId;
    });
  }
}
