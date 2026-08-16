package com.worldbox.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Army implements java.io.Serializable {
  private static int nextId = 1;

  /** Loading a save must never let a freshly created instance reuse an id
   * already present in the loaded data - bump the counter past whatever
   * the save actually contained. */
  public static void restoreNextId(int maxSeenId) { if (maxSeenId >= nextId) nextId = maxSeenId + 1; }

  public final int id;
  public int nationId;
  public int homeSettlementId;
  public double x, z, prevX, prevZ;
  public double targetX, targetZ;
  public final Map<String, Integer> units = new LinkedHashMap<>();
  /** The actual Human.id of every person currently serving in this army -
   * kept in sync with the units count map so combat losses and
   * demobilization affect real, specific people instead of an abstract
   * pool. See Military.raiseArmy/applyDamage/demobilize. */
  public final List<Integer> memberHumanIds = new ArrayList<>();
  public Integer targetSettlementId;
  public Integer targetArmyId;
  public String state = "idle";
  public double strength = 0;
  public boolean dead = false;
  /** Ticks left to show a "we're actually fighting right now" visual -
   * without this a battle was just two numbers quietly shrinking with no
   * way to tell from looking at the world that anything was happening. */
  public int combatFlashTimer = 0;

  public Army(int nationId, int homeSettlementId, double x, double z) {
    this.id = nextId++;
    this.nationId = nationId;
    this.homeSettlementId = homeSettlementId;
    this.x = x; this.z = z; this.prevX = x; this.prevZ = z;
    this.targetX = x; this.targetZ = z;
  }
}
