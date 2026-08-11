package com.worldbox.config;

import java.util.HashMap;
import java.util.Map;

/** Central tunables for the whole simulation, mirrored 1:1 from the original
 * design so balance stays consistent. */
public final class Config {
  private Config() {}

  public static final int COLS = 128;
  public static final int ROWS = 128;

  // terrain types
  public static final byte WATER = 0;
  public static final byte SAND = 1;
  public static final byte GRASS = 2;
  public static final byte DIRT = 3;
  public static final byte STONE = 4;

  // resource deposit types
  public static final byte RES_NONE = 0;
  public static final byte RES_FOREST = 1;
  public static final byte RES_STONE = 2;
  public static final byte RES_IRON = 3;
  public static final byte RES_GOLD = 4;

  public static final class ResourceInfo {
    public final String key;
    public final int yieldAmt;
    public final boolean respawns;
    public ResourceInfo(String key, int yieldAmt, boolean respawns) {
      this.key = key; this.yieldAmt = yieldAmt; this.respawns = respawns;
    }
  }

  public static final Map<Byte, ResourceInfo> RESOURCE_INFO = new HashMap<>();
  static {
    RESOURCE_INFO.put(RES_FOREST, new ResourceInfo("wood", 6, true));
    RESOURCE_INFO.put(RES_STONE, new ResourceInfo("stone", 8, false));
    RESOURCE_INFO.put(RES_IRON, new ResourceInfo("iron", 6, false));
    RESOURCE_INFO.put(RES_GOLD, new ResourceInfo("gold_ore", 4, false));
  }

  // simulation
  public static final int TICK_MS = 220;
  // was 420 - too tight a ceiling to see real multi-year growth play out;
  // a 10-15 year run hit this cap by year 8 and sat flat for the rest
  public static final int MAX_HUMANS = 600;
  public static final int MAX_AGE = 3200;
  public static final int MATURE_AGE = 70;

  // economy
  public static final Map<String, Double> BASE_PRICES = new HashMap<>();
  static {
    BASE_PRICES.put("food", 2.0);
    BASE_PRICES.put("wood", 3.0);
    BASE_PRICES.put("stone", 4.0);
    BASE_PRICES.put("iron", 6.0);
    BASE_PRICES.put("gold_ore", 9.0);
  }
  public static final double TAX_RATE_DEFAULT = 0.22;
  public static final double SETTLEMENT_BUFFER = 40;
  public static final double MARKET_ELASTICITY = 0.02;

  // military
  public static final class UnitSpec {
    public final String name;
    public final double power;
    public final Map<String, Double> cost;
    public final double upkeep;
    public final double speed;
    public UnitSpec(String name, double power, Map<String, Double> cost, double upkeep, double speed) {
      this.name = name; this.power = power; this.cost = cost; this.upkeep = upkeep; this.speed = speed;
    }
  }
  public static final Map<String, UnitSpec> UNIT_TYPES = new java.util.LinkedHashMap<>();
  static {
    UNIT_TYPES.put("militia", new UnitSpec("Militia", 3, mapOf("gold", 15.0, "wood", 5.0), 0.05, 0.09));
    UNIT_TYPES.put("swordsman", new UnitSpec("Swordsman", 7, mapOf("gold", 35.0, "iron", 8.0), 0.12, 0.08));
    UNIT_TYPES.put("archer", new UnitSpec("Archer", 6, mapOf("gold", 30.0, "wood", 10.0), 0.11, 0.09));
    UNIT_TYPES.put("knight", new UnitSpec("Knight", 14, mapOf("gold", 70.0, "iron", 15.0), 0.25, 0.13));
  }
  public static final int RAISE_BATCH = 6;

  // diplomacy
  public static final String PEACE = "peace";
  public static final String WAR = "war";
  public static final String ALLIANCE = "alliance";
  public static final String TRUCE = "truce";
  public static final int DECISION_INTERVAL = 45;

  public static final int[] NATION_COLORS = {
      0xe6553f, 0x3f8ee6, 0x4fbf5a, 0xe6c53f, 0xa35fe6,
      0xe67f3f, 0x3fd0c0, 0xd63f8e, 0x8fbf3f, 0x5f6fe6,
      0xe63f3f, 0x3fe67f,
  };

  public static final long WORLD_SEED = 1337;
  public static final int UNDEAD_NATION_ID = -2;

  // weird events
  public static final double MONSTER_POWER = 55;
  public static final double MONSTER_HP = 900;
  public static final double MONSTER_SPEED = 0.12;
  public static final int MONSTER_LIFETIME = 900;
  public static final int TORNADO_LIFETIME = 130;
  public static final double TORNADO_SPEED = 0.5;

  private static Map<String, Double> mapOf(String k1, double v1, String k2, double v2) {
    Map<String, Double> m = new HashMap<>();
    m.put(k1, v1);
    m.put(k2, v2);
    return m;
  }
}
