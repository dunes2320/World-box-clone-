// Central tunables for the whole simulation. Keeping them in one place
// makes balancing (and reading) the economy/military/diplomacy loops sane.

export const GRID = {
  COLS: 72,
  ROWS: 72,
  CELL_SIZE: 1, // world units per grid cell
};

export const TERRAIN = {
  WATER: 0,
  SAND: 1,
  GRASS: 2,
  DIRT: 3,
  STONE: 4,
};

export const RESOURCE = {
  NONE: 0,
  FOREST: 1,
  STONE_DEPOSIT: 2,
  IRON_DEPOSIT: 3,
  GOLD_DEPOSIT: 4,
};

export const RESOURCE_INFO = {
  [RESOURCE.FOREST]: { key: "wood", yield: 6, respawn: true },
  [RESOURCE.STONE_DEPOSIT]: { key: "stone", yield: 8, respawn: false },
  [RESOURCE.IRON_DEPOSIT]: { key: "iron", yield: 6, respawn: false },
  [RESOURCE.GOLD_DEPOSIT]: { key: "gold_ore", yield: 4, respawn: false },
};

export const SIM = {
  TICK_MS: 220, // one simulation tick at 1x speed
  MAX_HUMANS: 420,
  MAX_AGE: 3200,
  MATURE_AGE: 70,
  HUNGER_MAX: 260,
};

export const ECONOMY = {
  BASE_PRICES: { food: 2, wood: 3, stone: 4, iron: 6, gold_ore: 9 },
  TAX_RATE_DEFAULT: 0.22,
  SETTLEMENT_BUFFER: 40, // local stock kept before nation taxes/trades the rest
  MARKET_ELASTICITY: 0.02,
};

export const MILITARY = {
  UNIT_TYPES: {
    militia: { name: "Militia", power: 3, cost: { gold: 15, wood: 5 }, upkeep: 0.05, speed: 0.09 },
    swordsman: { name: "Swordsman", power: 7, cost: { gold: 35, iron: 8 }, upkeep: 0.12, speed: 0.08 },
    archer: { name: "Archer", power: 6, cost: { gold: 30, wood: 10 }, upkeep: 0.11, speed: 0.09 },
    knight: { name: "Knight", power: 14, cost: { gold: 70, iron: 15 }, upkeep: 0.25, speed: 0.13 },
  },
  RAISE_BATCH: 6, // villagers pulled from a settlement per "raise army" click
};

export const DIPLOMACY = {
  STATUS: { PEACE: "peace", WAR: "war", ALLIANCE: "alliance", TRUCE: "truce" },
  DECISION_INTERVAL: 45, // ticks between AI diplomatic considerations
};

export const NATION_COLORS = [
  0xe6553f, 0x3f8ee6, 0x4fbf5a, 0xe6c53f, 0xa35fe6,
  0xe67f3f, 0x3fd0c0, 0xd63f8e, 0x8fbf3f, 0x5f6fe6,
  0xe63f3f, 0x3fe67f,
];

export const WORLD_SEED = 1337;

// Reserved pseudo-nation id for the undead faction spawned by the zombie
// outbreak tool. Deliberately never added to state.nations so it's excluded
// from the normal economy/military/diplomacy loops.
export const UNDEAD_NATION_ID = -2;

export const EVENTS = {
  MONSTER_POWER: 55,
  MONSTER_HP: 900,
  MONSTER_SPEED: 0.12,
  MONSTER_LIFETIME: 900,
  TORNADO_LIFETIME: 130,
  TORNADO_SPEED: 0.5,
};
