import { DIPLOMACY } from "../config.js";
import { totalMilitaryPower } from "./nation.js";

const { PEACE, WAR, ALLIANCE, TRUCE } = DIPLOMACY.STATUS;

function key(a, b) { return a < b ? `${a}-${b}` : `${b}-${a}`; }

export class DiplomacyManager {
  constructor() {
    this.relations = new Map();
  }

  _get(a, b) {
    const k = key(a, b);
    let r = this.relations.get(k);
    if (!r) { r = { status: PEACE, score: 0, truceTimer: 0 }; this.relations.set(k, r); }
    return r;
  }

  getStatus(a, b) { return a === b ? ALLIANCE : this._get(a, b).status; }
  getScore(a, b) { return a === b ? 100 : this._get(a, b).score; }
  setStatus(a, b, status, opts = {}) {
    const r = this._get(a, b);
    r.status = status;
    if (status === TRUCE) r.truceTimer = opts.truceTicks ?? 200;
  }
  adjustScore(a, b, delta) {
    const r = this._get(a, b);
    r.score = clamp(r.score + delta, -100, 100);
  }

  pairsInvolving(nationId) {
    const out = [];
    this.relations.forEach((r, k) => {
      const [a, b] = k.split("-").map(Number);
      if (a === nationId || b === nationId) out.push({ other: a === nationId ? b : a, ...r });
    });
    return out;
  }
}

function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

function nationsAreNear(state, a, b, maxDist) {
  for (const sidA of a.settlementIds) {
    const sA = state.settlements.get(sidA);
    if (!sA) continue;
    for (const sidB of b.settlementIds) {
      const sB = state.settlements.get(sidB);
      if (!sB) continue;
      if (Math.hypot(sA.x - sB.x, sA.z - sB.z) <= maxDist) return true;
    }
  }
  return false;
}

export function updateDiplomacy(state) {
  const dip = state.diplomacy;
  const nations = [...state.nations.values()].filter((n) => n.alive);

  // drift & truce expiry
  dip.relations.forEach((r) => {
    if (r.status === WAR) r.score = clamp(r.score - 0.04, -100, 100);
    else if (r.status === ALLIANCE) r.score = clamp(r.score + 0.02, -100, 100);
    else if (r.status === TRUCE) {
      r.truceTimer--;
      if (r.truceTimer <= 0) r.status = PEACE;
    } else {
      r.score += (Math.random() - 0.5) * 0.06;
      r.score = clamp(r.score, -100, 100);
    }
  });

  for (const nation of nations) {
    if ((state.tick + nation.id * 3) % DIPLOMACY.DECISION_INTERVAL !== 0) continue;
    const myPower = totalMilitaryPower(state, nation.id) + 1;

    for (const other of nations) {
      if (other.id === nation.id) continue;
      if (!nationsAreNear(state, nation, other, 34)) continue;
      const status = dip.getStatus(nation.id, other.id);
      const score = dip.getScore(nation.id, other.id);
      const theirPower = totalMilitaryPower(state, other.id) + 1;

      if (status === PEACE) {
        if (score > 55 && Math.random() < 0.5) {
          dip.setStatus(nation.id, other.id, ALLIANCE);
          dip.adjustScore(nation.id, other.id, 10);
        } else if (score < -35 && myPower > theirPower * 1.3 && Math.random() < 0.3) {
          dip.setStatus(nation.id, other.id, WAR);
          dip.adjustScore(nation.id, other.id, -20);
        }
      } else if (status === WAR) {
        if (myPower < theirPower * 0.35 || myPower < 3) {
          dip.setStatus(nation.id, other.id, TRUCE, { truceTicks: 220 });
          dip.adjustScore(nation.id, other.id, 6);
        }
      } else if (status === ALLIANCE) {
        if (score < 10 && Math.random() < 0.03) {
          dip.setStatus(nation.id, other.id, PEACE);
        }
      }
    }
  }
}

export function onConquest(state, winnerNationId, loserNationId) {
  state.diplomacy.adjustScore(winnerNationId, loserNationId, -15);
}

// --- player ("god") actions: any nation can be directly commanded ---
export function forceWar(state, a, b) { state.diplomacy.setStatus(a, b, WAR); state.diplomacy.adjustScore(a, b, -25); }
export function forcePeace(state, a, b) { state.diplomacy.setStatus(a, b, TRUCE, { truceTicks: 260 }); state.diplomacy.adjustScore(a, b, 8); }
export function forceAlliance(state, a, b) { state.diplomacy.setStatus(a, b, ALLIANCE); state.diplomacy.adjustScore(a, b, 20); }
export function divineGift(state, nationId, amount) {
  const n = state.nations.get(nationId);
  if (n) n.treasury += amount;
}
