package com.worldbox.sim;

import java.util.LinkedHashMap;
import java.util.Map;

public class Army {
  private static int nextId = 1;

  public final int id;
  public int nationId;
  public int homeSettlementId;
  public double x, z, prevX, prevZ;
  public double targetX, targetZ;
  public final Map<String, Integer> units = new LinkedHashMap<>();
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
    units.put("militia", 0);
    units.put("swordsman", 0);
    units.put("archer", 0);
    units.put("knight", 0);
  }
}
