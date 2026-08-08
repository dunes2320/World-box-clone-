import { ECONOMY } from "../config.js";

const KEYS = ["food", "wood", "stone", "iron", "gold_ore"];
const HISTORY_LEN = 90;

// A single shared world market. Nations sell surplus and buy scarcity from
// it, which is what actually makes it "international" rather than a simple
// nation-to-nation ledger: everyone trades against the same floating prices.
export class GlobalMarket {
  constructor() {
    this.prices = { ...ECONOMY.BASE_PRICES };
    this.history = Object.fromEntries(KEYS.map((k) => [k, [this.prices[k]]]));
    this.volume = Object.fromEntries(KEYS.map((k) => [k, 0]));
  }

  nudge(key, direction, magnitude = 1) {
    const p = this.prices;
    const base = ECONOMY.BASE_PRICES[key];
    p[key] = clamp(p[key] * (1 + direction * ECONOMY.MARKET_ELASTICITY * magnitude), base * 0.35, base * 3.2);
  }

  snapshot() {
    for (const k of KEYS) {
      const arr = this.history[k];
      arr.push(this.prices[k]);
      if (arr.length > HISTORY_LEN) arr.shift();
    }
  }
}

function biggestStockSettlement(state, nation, key) {
  let best = null, bestAmt = -Infinity;
  for (const sid of nation.settlementIds) {
    const s = state.settlements.get(sid);
    if (!s) continue;
    if (s.stock[key] > bestAmt) { bestAmt = s.stock[key]; best = s; }
  }
  return best;
}

function smallestStockSettlement(state, nation, key) {
  let best = null, bestAmt = Infinity;
  for (const sid of nation.settlementIds) {
    const s = state.settlements.get(sid);
    if (!s) continue;
    if (s.stock[key] < bestAmt) { bestAmt = s.stock[key]; best = s; }
  }
  return best;
}

export function updateEconomy(state) {
  const market = state.market;
  for (const nation of state.nations.values()) {
    if (!nation.alive) continue;

    for (const key of KEYS) {
      const seller = biggestStockSettlement(state, nation, key);
      if (seller && seller.stock[key] > ECONOMY.SETTLEMENT_BUFFER * 1.6) {
        const sellAmt = (seller.stock[key] - ECONOMY.SETTLEMENT_BUFFER) * 0.08;
        seller.stock[key] -= sellAmt;
        nation.treasury += sellAmt * market.prices[key];
        market.volume[key] += sellAmt;
        market.nudge(key, -1, sellAmt / 12);
      }

      const buyer = smallestStockSettlement(state, nation, key);
      if (buyer && buyer.stock[key] < 6 && nation.treasury > 40 && key !== "gold_ore") {
        const buyAmt = 8;
        const cost = buyAmt * market.prices[key];
        if (nation.treasury >= cost) {
          nation.treasury -= cost;
          buyer.stock[key] += buyAmt;
          market.volume[key] += buyAmt;
          market.nudge(key, 1, buyAmt / 12);
        }
      }
    }
  }

  for (const key of KEYS) {
    market.prices[key] += (ECONOMY.BASE_PRICES[key] - market.prices[key]) * 0.008;
  }

  if (state.tick % 4 === 0) market.snapshot();
}

function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }
