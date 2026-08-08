import { TOOL_GROUPS } from "../tools/godTools.js";
import { MILITARY } from "../config.js";
import { raiseArmy } from "../sim/military.js";
import { forceWar, forcePeace, forceAlliance, divineGift } from "../sim/diplomacy.js";

const RESOURCE_LABEL = { food: "🌾 Food", wood: "🪵 Wood", stone: "🪨 Stone", iron: "⛏️ Iron", gold_ore: "🥇 Ore" };

export class Hud {
  constructor(ctx) {
    this.ctx = ctx; // { getState, getTool, setTool, getBrushSize, setBrushSize, getSelection, setSelection, setSpeed, resetWorld }
    this.sidePanelMode = null; // 'settlement' | 'nation' | 'nationsList' | 'market'
    this._buildToolbar();
    this._buildTopbar();
    this._lastStatRefresh = 0;
    this._lastPanelRefresh = 0;
  }

  _buildToolbar() {
    const el = document.getElementById("toolbar");
    el.innerHTML = "";
    for (const group of TOOL_GROUPS) {
      const label = document.createElement("div");
      label.className = "toolGroupLabel";
      label.textContent = group.label;
      el.appendChild(label);
      for (const tool of group.tools) {
        const btn = document.createElement("button");
        btn.className = "toolBtn";
        btn.dataset.tool = tool.id;
        btn.innerHTML = `<div>${tool.icon}</div><span>${tool.name}</span>`;
        btn.addEventListener("click", () => {
          this.ctx.setTool(tool.id);
          this._refreshToolButtons();
        });
        el.appendChild(btn);
      }
    }
    const brushWrap = document.createElement("div");
    brushWrap.id = "brushWrap";
    brushWrap.innerHTML = `<div class="toolGroupLabel">Brush size</div>
      <input type="range" id="brushSize" min="1" max="9" value="${this.ctx.getBrushSize()}" />
      <div id="brushSizeLabel">${this.ctx.getBrushSize()}</div>`;
    el.appendChild(brushWrap);
    brushWrap.querySelector("#brushSize").addEventListener("input", (e) => {
      const v = parseInt(e.target.value, 10);
      this.ctx.setBrushSize(v);
      brushWrap.querySelector("#brushSizeLabel").textContent = v;
    });

    const resetBtn = document.createElement("button");
    resetBtn.className = "wideBtn small";
    resetBtn.style.width = "100%";
    resetBtn.style.marginTop = "10px";
    resetBtn.textContent = "🔄 Reset World";
    resetBtn.addEventListener("click", () => {
      if (confirm("Start a brand new world?")) this.ctx.resetWorld();
    });
    el.appendChild(resetBtn);

    this._refreshToolButtons();
  }

  _refreshToolButtons() {
    const active = this.ctx.getTool();
    document.querySelectorAll(".toolBtn").forEach((b) => b.classList.toggle("active", b.dataset.tool === active));
    const slider = document.getElementById("brushSize");
    if (slider) slider.value = this.ctx.getBrushSize();
    const label = document.getElementById("brushSizeLabel");
    if (label) label.textContent = this.ctx.getBrushSize();
  }

  _buildTopbar() {
    document.querySelectorAll(".speedBtn").forEach((btn) => {
      btn.addEventListener("click", () => {
        document.querySelectorAll(".speedBtn").forEach((b) => b.classList.remove("active"));
        btn.classList.add("active");
        this.ctx.setSpeed(parseInt(btn.dataset.speed, 10));
      });
    });
    document.getElementById("nationsToggle").addEventListener("click", () => {
      this.sidePanelMode = this.sidePanelMode === "nationsList" ? null : "nationsList";
      this.ctx.setSelection(null);
      this._render();
    });
    document.getElementById("marketToggle").addEventListener("click", () => {
      this.sidePanelMode = this.sidePanelMode === "market" ? null : "market";
      this.ctx.setSelection(null);
      this._render();
    });
  }

  notifySelectionChanged() {
    const sel = this.ctx.getSelection();
    this.sidePanelMode = sel ? sel.type : this.sidePanelMode;
    this._render();
  }

  tick(now) {
    if (now - this._lastStatRefresh >= 280) {
      this._lastStatRefresh = now;
      const state = this.ctx.getState();
      document.getElementById("statPop").textContent = state.humans.length;
      document.getElementById("statNations").textContent = state.nations.size;
      document.getElementById("statTick").textContent = state.tick;
    }
    // side panels rebuild their whole DOM on refresh; throttle harder so
    // clicks inside them (nation rows, diplomacy buttons) aren't fighting a
    // node replacement every quarter second.
    if (now - this._lastPanelRefresh >= 1000) {
      this._lastPanelRefresh = now;
      this._render();
    }
  }

  _render() {
    const panel = document.getElementById("sidePanel");
    const state = this.ctx.getState();
    const sel = this.ctx.getSelection();

    let mode = this.sidePanelMode;
    if (sel) mode = sel.type;

    if (!mode) { panel.classList.add("hidden"); return; }
    panel.classList.remove("hidden");

    if (mode === "settlement" && sel) this._renderSettlement(panel, state, sel.id);
    else if (mode === "nation" && sel) this._renderNation(panel, state, sel.id);
    else if (mode === "nationsList") this._renderNationsList(panel, state);
    else if (mode === "market") this._renderMarket(panel, state);
    else panel.classList.add("hidden");
  }

  _closeBtn() {
    return `<button data-close="1">✕</button>`;
  }

  _wireClose(panel) {
    const btn = panel.querySelector("[data-close]");
    if (btn) btn.addEventListener("click", () => {
      this.sidePanelMode = null;
      this.ctx.setSelection(null);
      this._render();
    });
  }

  _renderSettlement(panel, state, id) {
    const s = state.settlements.get(id);
    if (!s) { this.sidePanelMode = null; this.ctx.setSelection(null); panel.classList.add("hidden"); return; }
    const nation = state.nations.get(s.nationId);
    panel.innerHTML = `
      <div class="panelTitle"><span>🏘️ ${s.name}</span>${this._closeBtn()}</div>
      <div class="statRow"><span>Nation</span><b>${nation ? nation.name : "—"}</b></div>
      <div class="statRow"><span>Population</span><b>${s.populationCount}</b></div>
      <div class="statRow"><span>Territory radius</span><b>${s.radius.toFixed(1)}</b></div>
      <div class="statRow"><span>Under siege</span><b>${s.siegeProgress > 0.5 ? "⚔️ yes" : "no"}</b></div>
      <div class="sectionLabel">Stockpile</div>
      ${Object.entries(s.stock).map(([k, v]) => `<div class="statRow"><span>${RESOURCE_LABEL[k] || k}</span><b>${Math.floor(v)}</b></div>`).join("")}
      <div class="sectionLabel">Raise army (from this settlement's workers)</div>
      <div id="raiseButtons"></div>
      <div id="raiseMsg" style="font-size:11px;color:var(--muted);margin-top:4px;"></div>
    `;
    this._wireClose(panel);
    const raiseWrap = panel.querySelector("#raiseButtons");
    for (const key of Object.keys(MILITARY.UNIT_TYPES)) {
      const spec = MILITARY.UNIT_TYPES[key];
      const btn = document.createElement("button");
      btn.className = "actionBtn";
      btn.textContent = `${spec.name} (${spec.cost.gold}g)`;
      btn.addEventListener("click", () => {
        const res = raiseArmy(state, id, key);
        panel.querySelector("#raiseMsg").textContent = res.ok
          ? `Raised ${res.count} ${spec.name}(s).`
          : `Can't raise: ${res.reason}.`;
      });
      raiseWrap.appendChild(btn);
    }
  }

  _renderNation(panel, state, id) {
    const n = state.nations.get(id);
    if (!n) { this.sidePanelMode = "nationsList"; this.ctx.setSelection(null); this._render(); return; }
    const swatch = `#${n.color.toString(16).padStart(6, "0")}`;
    let pop = 0, treasureResources = { food: 0, wood: 0, stone: 0, iron: 0, gold_ore: 0 };
    for (const sid of n.settlementIds) {
      const s = state.settlements.get(sid);
      if (!s) continue;
      pop += s.populationCount;
      for (const k in treasureResources) treasureResources[k] += s.stock[k];
    }
    let military = 0;
    for (const a of state.armies.values()) if (a.nationId === id) military += a.strength || 0;

    panel.innerHTML = `
      <div class="panelTitle"><span><span class="nationSwatch" style="display:inline-block;background:${swatch}"></span> ${n.name}</span>${this._closeBtn()}</div>
      <div class="statRow"><span>Treasury</span><b>${Math.floor(n.treasury)}g</b></div>
      <div class="statRow"><span>Tax rate</span><b>${(n.taxRate * 100).toFixed(0)}%</b></div>
      <div class="statRow"><span>Settlements</span><b>${n.settlementIds.size}</b></div>
      <div class="statRow"><span>Population</span><b>${pop}</b></div>
      <div class="statRow"><span>Military power</span><b>${military.toFixed(0)}</b></div>
      <div class="sectionLabel">Divine gift</div>
      <button class="actionBtn good" id="giftBtn">✨ Gift 200 gold</button>
      <div class="sectionLabel">Settlements</div>
      ${[...n.settlementIds].map((sid) => {
        const s = state.settlements.get(sid);
        return s ? `<div class="statRow"><span>${s.name}</span><b>${s.populationCount} pop</b></div>` : "";
      }).join("")}
      <div class="sectionLabel">Diplomacy</div>
      <div id="relList"></div>
    `;
    this._wireClose(panel);
    panel.querySelector("#giftBtn").addEventListener("click", () => divineGift(state, id, 200));

    const relList = panel.querySelector("#relList");
    const others = [...state.nations.values()].filter((o) => o.id !== id);
    for (const other of others) {
      const status = state.diplomacy.getStatus(id, other.id);
      const score = state.diplomacy.getScore(id, other.id).toFixed(0);
      const row = document.createElement("div");
      row.className = "relRow";
      row.innerHTML = `
        <span class="nationSwatch" style="background:#${other.color.toString(16).padStart(6, "0")}"></span>
        <span style="flex:1">${other.name}</span>
        <span class="relBadge ${status}">${status}</span>
        <span style="color:var(--muted);font-size:10.5px;">${score}</span>
      `;
      const actions = document.createElement("div");
      actions.style.marginBottom = "8px";
      const mkBtn = (label, fn, cls) => {
        const b = document.createElement("button");
        b.className = `actionBtn ${cls || ""}`;
        b.textContent = label;
        b.style.fontSize = "10.5px";
        b.style.padding = "3px 6px";
        b.addEventListener("click", () => { fn(state, id, other.id); this._render(); });
        return b;
      };
      actions.appendChild(mkBtn("Declare War", forceWar, "danger"));
      actions.appendChild(mkBtn("Force Peace", forcePeace));
      actions.appendChild(mkBtn("Alliance", forceAlliance, "good"));
      relList.appendChild(row);
      relList.appendChild(actions);
    }
  }

  _renderNationsList(panel, state) {
    const nations = [...state.nations.values()].sort((a, b) => b.treasury - a.treasury);
    panel.innerHTML = `<div class="panelTitle"><span>🏳️ Nations (${nations.length})</span>${this._closeBtn()}</div><div id="natRows"></div>`;
    this._wireClose(panel);
    const rows = panel.querySelector("#natRows");
    if (nations.length === 0) {
      rows.innerHTML = `<div class="statRow"><span>No nations yet. Use "Found Nation".</span></div>`;
    }
    for (const n of nations) {
      let pop = 0;
      for (const sid of n.settlementIds) { const s = state.settlements.get(sid); if (s) pop += s.populationCount; }
      const row = document.createElement("div");
      row.className = "nationRow";
      row.innerHTML = `<span class="nationSwatch" style="background:#${n.color.toString(16).padStart(6, "0")}"></span>
        <span class="n-name">${n.name}</span>
        <span class="n-meta">${pop}p · ${Math.floor(n.treasury)}g</span>`;
      row.addEventListener("click", () => { this.ctx.setSelection({ type: "nation", id: n.id }); this._render(); });
      rows.appendChild(row);
    }
  }

  _renderMarket(panel, state) {
    const market = state.market;
    panel.innerHTML = `<div class="panelTitle"><span>💰 World Market</span>${this._closeBtn()}</div><div id="priceRows"></div>`;
    this._wireClose(panel);
    const wrap = panel.querySelector("#priceRows");
    for (const key of Object.keys(market.prices)) {
      const hist = market.history[key];
      const price = market.prices[key];
      const prev = hist.length > 1 ? hist[hist.length - 2] : price;
      const trend = price >= prev ? "priceUp" : "priceDown";
      const row = document.createElement("div");
      row.innerHTML = `<div class="priceRow"><span>${RESOURCE_LABEL[key] || key}</span><span class="${trend}">${price.toFixed(2)}g</span></div>
        <svg class="miniSpark" viewBox="0 0 100 22" preserveAspectRatio="none"></svg>`;
      wrap.appendChild(row);
      const svg = row.querySelector("svg");
      const max = Math.max(...hist, 0.001), min = Math.min(...hist);
      const range = Math.max(0.001, max - min);
      const pts = hist.map((v, i) => `${(i / Math.max(1, hist.length - 1)) * 100},${20 - ((v - min) / range) * 18}`).join(" ");
      svg.innerHTML = `<polyline points="${pts}" fill="none" stroke="#4fa3ff" stroke-width="1.5" />`;
    }
  }
}
