package com.worldbox.ui;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Quad;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.DefaultRangedValueModel;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Slider;
import com.simsilica.lemur.component.SpringGridLayout;
import com.worldbox.config.Config;
import com.worldbox.sim.Business;
import com.worldbox.sim.Diplomacy;
import com.worldbox.sim.GameState;
import com.worldbox.sim.GlobalMarket;
import com.worldbox.sim.Government;
import com.worldbox.sim.Military;
import com.worldbox.sim.Nation;
import com.worldbox.sim.Settlement;
import com.worldbox.sim.WorldEvent;
import com.worldbox.tools.GodTools;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

public class GameHud {
  private static final ColorRGBA ACTIVE = new ColorRGBA(0.31f, 0.64f, 1f, 1f);
  private static final ColorRGBA TEXT = new ColorRGBA(0.91f, 0.93f, 0.96f, 1f);
  private static final ColorRGBA MUTED = new ColorRGBA(0.55f, 0.59f, 0.66f, 1f);
  private static final ColorRGBA GOOD = new ColorRGBA(0.31f, 0.75f, 0.42f, 1f);
  private static final ColorRGBA DANGER = new ColorRGBA(0.88f, 0.33f, 0.30f, 1f);

  private final HudContext ctx;
  private final AssetManager assetManager;
  private final float screenW, screenH;

  private final Container toolbar = new Container(new SpringGridLayout(Axis.Y, Axis.X));
  private final Container topBar = new Container(new SpringGridLayout(Axis.X, Axis.Y));
  private final Container sidePanel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
  private final Label statLabel;
  private final Map<String, Button> toolButtons = new LinkedHashMap<>();
  private final Map<String, Button> toolTabButtons = new LinkedHashMap<>();
  private Container toolGroupContainer;
  private String activeToolTab = "Terrain";
  private Label brushLabel;
  private Slider brushSlider;
  private Slider zoomSlider;
  private Label zoomLabel;

  private String sidePanelMode; // "settlement" | "nation" | "nationsList" | "market" | "graph" | "settings" | "log"
  private double lastStatRefresh, lastPanelRefresh;
  private final Map<Integer, Button> speedButtons = new LinkedHashMap<>();
  private int lastSpeedShown = -1;

  // a brief on-screen toast whenever a new world event lands (war, disaster,
  // a nation rising or falling) - the log book (see renderLog) is where a
  // player goes looking for history, but they shouldn't have to keep it
  // open just to notice something happened in the first place
  private final Label toastLabel;
  private double toastTimer = 0;
  private int lastSeenEventCount = 0;

  // Long lists (dozens of nations, a nation's relations with everyone
  // else) have no scrollable widget available in this stripped-down Lemur
  // build, so they're paginated instead - bounded page size keeps the
  // panel from spilling off the bottom of the screen with no way back.
  private static final int LIST_PAGE_SIZE = 10;
  private int listPage = 0;
  private int listPageKey = Integer.MIN_VALUE;

  private void resetListPage(int key) {
    if (key != listPageKey) { listPageKey = key; listPage = 0; }
  }

  /** Adds Prev/Page/Next controls beneath a paginated section. Returns the
   * [start, end) row range the caller should actually render. */
  private int[] pager(int totalItems) {
    int pages = Math.max(1, (totalItems + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE);
    listPage = Math.max(0, Math.min(listPage, pages - 1));
    int start = listPage * LIST_PAGE_SIZE;
    int end = Math.min(totalItems, start + LIST_PAGE_SIZE);
    if (pages > 1) {
      Container nav = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
      Button prev = nav.addChild(new Button("< Prev"));
      prev.addClickCommands(src -> { listPage = Math.max(0, listPage - 1); refreshSidePanel(); });
      Label pageLbl = nav.addChild(new Label((listPage + 1) + " / " + pages));
      pageLbl.setColor(MUTED);
      Button next = nav.addChild(new Button("Next >"));
      next.addClickCommands(src -> { listPage = Math.min(pages - 1, listPage + 1); refreshSidePanel(); });
    }
    return new int[]{start, end};
  }

  // Economy graph: raw jME quads, positioned by hand since Lemur's grid
  // layout has no clean way to anchor bars of varying height to a shared
  // baseline.
  private final Node chartNode = new Node("economyChart");
  private final java.util.List<Geometry> chartBars = new java.util.ArrayList<>();
  private String graphView; // null | "nation" | "world"
  private int graphNationId = -1;
  private String graphMetric = "marketcap"; // marketcap | unemployment | gdp | currency

  private static final float TOOLBAR_WIDTH = 190f;
  private static final float TOPBAR_HEIGHT = 42f;
  private static final float SIDEPANEL_WIDTH = 330f;
  private static final float CHART_WIDTH = 290f;
  private static final float CHART_HEIGHT = 150f;
  private static final float CHART_TOP_OFFSET = 226f; // px below sidePanel's top edge

  /** Coarse screen-space guard so a click on a HUD panel doesn't also paint
   * the world underneath it. Panel positions are fixed (non-resizable
   * window), so a few hardcoded regions are enough. */
  public boolean isOverUi(float sx, float sy) {
    if (sy > screenH - TOPBAR_HEIGHT) return true;
    if (sx < TOOLBAR_WIDTH) return true;
    if (sidePanel.getCullHint() != com.jme3.scene.Spatial.CullHint.Always && sx > screenW - SIDEPANEL_WIDTH) return true;
    return false;
  }

  public GameHud(Node guiNode, AssetManager assets, int width, int height, HudContext ctx) {
    this.ctx = ctx;
    this.assetManager = assets;
    this.screenW = width;
    this.screenH = height;

    topBar.setLocalTranslation(0, height, 1);
    topBar.setBackground(UiTextures.panelBackground());
    guiNode.attachChild(topBar);

    Label title = topBar.addChild(new Label("WORLD BOX 3D"));
    title.setFontSize(20);
    title.setColor(TEXT);
    topBar.addChild(spacer(30));
    statLabel = topBar.addChild(new Label("Pop: 0   Nations: 0"));
    statLabel.setColor(MUTED);
    statLabel.setBackground(UiTextures.chipBackground());
    topBar.addChild(spacer(30));

    for (int speed : new int[]{0, 1, 2, 4}) {
      Button b = topBar.addChild(new Button(speedLabel(speed)));
      b.addClickCommands(src -> { ctx.setGameSpeed(speed); refreshSpeedButtons(); });
      speedButtons.put(speed, b);
      topBar.addChild(spacer(4));
    }
    topBar.addChild(spacer(20));
    Button nationsBtn = topBar.addChild(new Button("Nations"));
    nationsBtn.addClickCommands(src -> {
      sidePanelMode = "nationsList".equals(sidePanelMode) ? null : "nationsList";
      ctx.setSelection(null);
      refreshSidePanel();
    });
    topBar.addChild(spacer(6));
    Button marketBtn = topBar.addChild(new Button("Market"));
    marketBtn.addClickCommands(src -> {
      sidePanelMode = "market".equals(sidePanelMode) ? null : "market";
      ctx.setSelection(null);
      refreshSidePanel();
    });
    topBar.addChild(spacer(6));
    Button worldEconBtn = topBar.addChild(new Button("Market Index"));
    worldEconBtn.addClickCommands(src -> showGraph("world", -1));
    topBar.addChild(spacer(6));
    Button logBtn = topBar.addChild(new Button("Log"));
    logBtn.addClickCommands(src -> {
      sidePanelMode = "log".equals(sidePanelMode) ? null : "log";
      ctx.setSelection(null);
      resetListPage(-999);
      refreshSidePanel();
    });
    topBar.addChild(spacer(6));
    Button settingsBtn = topBar.addChild(new Button("Settings"));
    settingsBtn.addClickCommands(src -> {
      sidePanelMode = "settings".equals(sidePanelMode) ? null : "settings";
      ctx.setSelection(null);
      refreshSidePanel();
    });

    toolbar.setLocalTranslation(0, height - 46, 1);
    toolbar.setBackground(UiTextures.panelBackground());
    guiNode.attachChild(toolbar);
    buildToolbar();

    sidePanel.setBackground(UiTextures.panelBackground());
    guiNode.attachChild(sidePanel);
    sidePanel.setCullHint(Spatial.CullHint.Always);

    chartNode.setQueueBucket(RenderQueue.Bucket.Gui);
    chartNode.setCullHint(Spatial.CullHint.Always);
    guiNode.attachChild(chartNode);

    toastLabel = new Label(" ");
    toastLabel.setFontSize(15);
    toastLabel.setColor(TEXT);
    toastLabel.setBackground(UiTextures.panelBackground());
    toastLabel.setTextHAlignment(com.simsilica.lemur.HAlignment.Center);
    toastLabel.setPreferredSize(new Vector3f(520, 34, 0));
    toastLabel.setLocalTranslation(width / 2f - 260, height - TOPBAR_HEIGHT - 14, 5);
    toastLabel.setCullHint(Spatial.CullHint.Always);
    guiNode.attachChild(toastLabel);

    styleButtons(topBar);
    refreshSpeedButtons();
  }

  private static String speedLabel(int s) {
    switch (s) { case 0: return "Pause"; case 1: return "1x"; case 2: return "2x"; default: return "4x"; }
  }

  /** Walks every Button under a container and gives it the resting chip
   * background if it doesn't already have a background set - lets a
   * whole panel's worth of buttons (built across many call sites) get
   * restyled from a couple of call sites instead of touching every
   * individual `new Button(...)` construction. Buttons that already carry
   * a specific background (the active tool/tab, an active-state row) are
   * left alone so this doesn't stomp on that state. */
  private void styleButtons(com.jme3.scene.Node root) {
    for (com.jme3.scene.Spatial child : root.getChildren()) {
      if (child instanceof Button) {
        Button b = (Button) child;
        if (b.getBackground() == null) b.setBackground(UiTextures.buttonBackground());
      }
      if (child instanceof com.jme3.scene.Node) styleButtons((com.jme3.scene.Node) child);
    }
  }

  private Label spacer(float width) {
    Label l = new Label(" ");
    l.setPreferredSize(new Vector3f(width, 1, 0));
    return l;
  }

  private static final String[] TOOL_TABS = {"Terrain", "Civilizations", "Creatures", "Disasters"};

  /** Tools used to be one long always-visible list; now they're grouped
   * into clickable tabs (Select stays pinned above them since it's the
   * default/most-used tool) so the toolbar reads as organized categories
   * instead of a wall of buttons. */
  private void buildToolbar() {
    GodTools.ToolDef selectDef = GodTools.TOOLS.get(0);
    Button selectBtn = toolbar.addChild(new Button(selectDef.name));
    selectBtn.setTextHAlignment(com.simsilica.lemur.HAlignment.Left);
    selectBtn.addClickCommands(src -> {
      ctx.setTool(selectDef.id);
      refreshToolButtons();
    });
    toolButtons.put(selectDef.id, selectBtn);

    Container tabRow = toolbar.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
    boolean firstTab = true;
    for (String tabName : TOOL_TABS) {
      if (!firstTab) tabRow.addChild(spacer(4));
      firstTab = false;
      Button tabBtn = tabRow.addChild(new Button(tabName));
      tabBtn.setFontSize(12);
      tabBtn.addClickCommands(src -> {
        activeToolTab = tabName;
        rebuildToolGroup();
      });
      toolTabButtons.put(tabName, tabBtn);
    }

    toolGroupContainer = toolbar.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
    rebuildToolGroup();

    Label brushHeader = toolbar.addChild(new Label("BRUSH SIZE"));
    brushHeader.setColor(MUTED);
    brushHeader.setFontSize(12);
    brushSlider = toolbar.addChild(new Slider(new DefaultRangedValueModel(1, 9, ctx.getBrushSize()), Axis.X));
    brushSlider.setDelta(1);
    brushSlider.setPreferredSize(new Vector3f(150, 24, 0));
    brushLabel = toolbar.addChild(new Label(String.valueOf(ctx.getBrushSize())));
    brushLabel.setColor(MUTED);

    Button reset = toolbar.addChild(new Button("Reset World"));
    reset.addClickCommands(src -> ctx.resetWorld());

    styleButtons(toolbar);
    refreshToolButtons();
  }

  private void rebuildToolGroup() {
    toolGroupContainer.clearChildren();
    for (String tabName : TOOL_TABS) {
      Button b = toolTabButtons.get(tabName);
      boolean active = tabName.equals(activeToolTab);
      b.setColor(active ? ACTIVE : TEXT);
      b.setBackground(active ? UiTextures.activeButtonBackground() : UiTextures.buttonBackground());
    }
    for (GodTools.ToolDef tool : GodTools.TOOLS) {
      if (!tool.group.equals(activeToolTab)) continue;
      Button b = toolGroupContainer.addChild(new Button(tool.name));
      b.setTextHAlignment(com.simsilica.lemur.HAlignment.Left);
      b.addClickCommands(src -> {
        ctx.setTool(tool.id);
        refreshToolButtons();
      });
      toolButtons.put(tool.id, b);
    }
    styleButtons(toolGroupContainer);
    refreshToolButtons();
  }

  private void refreshToolButtons() {
    String active = ctx.getTool();
    for (Map.Entry<String, Button> e : toolButtons.entrySet()) {
      boolean isActive = e.getKey().equals(active);
      e.getValue().setColor(isActive ? ACTIVE : TEXT);
      e.getValue().setBackground(isActive ? UiTextures.activeButtonBackground() : UiTextures.buttonBackground());
    }
  }

  private void refreshSpeedButtons() {
    int active = ctx.getGameSpeed();
    if (active == lastSpeedShown) return;
    lastSpeedShown = active;
    for (Map.Entry<Integer, Button> e : speedButtons.entrySet()) {
      boolean isActive = e.getKey() == active;
      e.getValue().setColor(isActive ? ACTIVE : TEXT);
      e.getValue().setBackground(isActive ? UiTextures.activeButtonBackground() : UiTextures.buttonBackground());
    }
  }

  /** Test-only hook (used by GameApp's headless verification script). */
  public void debugShowGraph(String view, int nationId) {
    showGraph(view, nationId);
  }

  /** Test-only hook (used by GameApp's headless verification script). */
  public void debugSetGraphMetric(String metric) {
    graphMetric = metric;
    refreshSidePanel();
  }

  /** Test-only hook (used by GameApp's headless verification script). */
  public void debugSetPanelMode(String mode) {
    sidePanelMode = mode;
    ctx.setSelection(null);
    refreshSidePanel();
  }

  public void notifySelectionChanged() {
    GameState.Selection sel = ctx.getSelection();
    if (sel != null) sidePanelMode = sel.type;
    refreshSidePanel();
  }

  public void update(float tpf, double now) {
    int brushVal = (int) Math.round(brushSlider.getModel().getValue());
    if (brushVal != ctx.getBrushSize()) {
      ctx.setBrushSize(brushVal);
      brushLabel.setText(String.valueOf(brushVal));
    }

    if (zoomSlider != null && "settings".equals(sidePanelMode)) {
      float zoomVal = Math.round(zoomSlider.getModel().getValue() * 10f) / 10f;
      if (Math.abs(zoomVal - ctx.getZoomSensitivity()) > 0.001f) {
        ctx.setZoomSensitivity(zoomVal);
        zoomLabel.setText(String.format("%.1fx", zoomVal));
      }
    }
    refreshSpeedButtons();

    if (now - lastStatRefresh > 0.28) {
      lastStatRefresh = now;
      GameState state = ctx.getState();
      statLabel.setText("Pop: " + state.humans.size() + "   Nations: " + state.nations.size()
          + "   " + com.worldbox.util.Calendar.dateString(state.tick));
    }

    GameState st = ctx.getState();
    if (st.eventLog.size() > lastSeenEventCount) {
      lastSeenEventCount = st.eventLog.size();
      WorldEvent latest = st.eventLog.peekLast();
      if (latest != null) {
        toastLabel.setText(latest.message);
        toastLabel.setColor(logCategoryColor(latest.category));
        toastTimer = 6.0;
        toastLabel.setCullHint(Spatial.CullHint.Inherit);
      }
    }
    if (toastTimer > 0) {
      toastTimer -= tpf;
      if (toastTimer <= 0) toastLabel.setCullHint(Spatial.CullHint.Always);
    }
    // "settings" is deliberately excluded from the periodic auto-refresh -
    // rebuilding the panel every second would recreate the slider mid-drag
    // and reset it out from under the player's mouse.
    // Every button in a rebuilt panel now carries its own background
    // geometry (see styleButtons) instead of zero, so a full clear+rebuild
    // is a heavier operation than it used to be - a slower refresh cadence
    // trades a little UI freshness for a lot less GPU buffer churn over a
    // long session.
    if (now - lastPanelRefresh > 2.5 && !"settings".equals(sidePanelMode)) {
      lastPanelRefresh = now;
      refreshSidePanel();
    }
  }

  private void refreshSidePanel() {
    GameState state = ctx.getState();
    GameState.Selection sel = ctx.getSelection();
    String mode = sel != null ? sel.type : sidePanelMode;

    sidePanel.clearChildren();
    if (mode == null) {
      sidePanel.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
      return;
    }
    sidePanel.setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);

    if ("settlement".equals(mode) && sel != null) renderSettlement(state, sel.id);
    else if ("nation".equals(mode) && sel != null) renderNation(state, sel.id);
    else if ("human".equals(mode) && sel != null) renderHuman(state, sel.id);
    else if ("nationsList".equals(mode)) renderNationsList(state);
    else if ("market".equals(mode)) renderMarket(state);
    else if ("log".equals(mode)) renderLog(state);
    else if ("graph".equals(mode)) renderGraph(state);
    else if ("settings".equals(mode)) renderSettings();
    else sidePanel.setCullHint(com.jme3.scene.Spatial.CullHint.Always);

    sidePanel.setLocalTranslation(screenW - 320, screenH - 46, 1);
    styleButtons(sidePanel);

    if (!"graph".equals(mode)) {
      chartNode.setCullHint(Spatial.CullHint.Always);
    } else {
      chartNode.setCullHint(Spatial.CullHint.Inherit);
      layoutChart();
    }
  }

  private void showGraph(String view, int nationId) {
    graphView = view;
    graphNationId = nationId;
    graphMetric = "marketcap";
    sidePanelMode = "graph";
    ctx.setSelection(null);
    refreshSidePanel();
  }

  private Button closeButton() {
    Button b = sidePanel.addChild(new Button("Close"));
    b.addClickCommands(src -> {
      sidePanelMode = null;
      ctx.setSelection(null);
      refreshSidePanel();
    });
    return b;
  }

  private void statRow(String label, String value) {
    Container row = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
    Label l = row.addChild(new Label(label));
    l.setColor(MUTED);
    l.setPreferredSize(new Vector3f(160, 20, 0));
    Label v = row.addChild(new Label(value));
    v.setColor(TEXT);
  }

  private void renderSettings() {
    Label title = sidePanel.addChild(new Label("Settings"));
    title.setFontSize(17);
    closeButton();

    Label camHeader = sidePanel.addChild(new Label("CAMERA"));
    camHeader.setColor(MUTED); camHeader.setFontSize(12);

    Label zoomHeader = sidePanel.addChild(new Label("Scroll zoom sensitivity"));
    zoomHeader.setColor(TEXT);
    zoomSlider = sidePanel.addChild(new Slider(new DefaultRangedValueModel(0.2, 3.0, ctx.getZoomSensitivity()), Axis.X));
    zoomSlider.setDelta(0.1);
    zoomSlider.setPreferredSize(new Vector3f(260, 24, 0));
    zoomLabel = sidePanel.addChild(new Label(String.format("%.1fx", ctx.getZoomSensitivity())));
    zoomLabel.setColor(MUTED);
  }

  private void renderSettlement(GameState state, int id) {
    Settlement s = state.settlements.get(id);
    if (s == null) { sidePanelMode = null; ctx.setSelection(null); return; }
    Label title = sidePanel.addChild(new Label(s.name));
    title.setFontSize(17);
    closeButton();
    Nation nation = state.nations.get(s.nationId);
    statRow("Nation", s.abandoned ? "(abandoned ruin)" : nation != null ? nation.displayName() : "-");
    statRow("Population", String.valueOf(s.populationCount));
    statRow("Housing", s.populationCount + " / " + (int) (s.housingStock * Settlement.PEOPLE_PER_HOUSE));
    statRow("Territory radius", String.format("%.1f", s.radius));
    statRow("Under siege", s.siegeProgress > 0.5 ? "yes" : "no");

    Label stockHeader = sidePanel.addChild(new Label("STOCKPILE"));
    stockHeader.setColor(MUTED); stockHeader.setFontSize(12);
    for (Map.Entry<String, Double> e : s.stock.entrySet()) {
      statRow(e.getKey(), String.valueOf((int) Math.floor(e.getValue())));
    }

    Label raiseHeader = sidePanel.addChild(new Label("RAISE ARMY"));
    raiseHeader.setColor(MUTED); raiseHeader.setFontSize(12);
    Label msg = new Label("");
    msg.setColor(MUTED);
    for (Config.UnitSpec spec : Config.UNIT_TYPES.values()) {
      String unitKey = keyForSpec(spec);
      Button b = sidePanel.addChild(new Button(spec.name + " (" + spec.cost.getOrDefault("gold", 0.0).intValue() + "g)"));
      b.addClickCommands(src -> {
        Military.RaiseResult r = Military.raiseArmy(state, id, unitKey);
        msg.setText(r.ok ? ("Raised " + r.count + " " + spec.name + "(s).") : ("Can't raise: " + r.reason));
      });
    }
    sidePanel.addChild(msg);
  }

  private String keyForSpec(Config.UnitSpec spec) {
    for (Map.Entry<String, Config.UnitSpec> e : Config.UNIT_TYPES.entrySet()) if (e.getValue() == spec) return e.getKey();
    return "militia";
  }

  /** People are paid in their own country's currency, not literal gold -
   * this is the short label ("Crown", "Franc", ...) pulled off the
   * nation's currencyName for money amounts. Null (no nation) falls back
   * to a generic unit. */
  private String currencyAbbrev(Nation n) {
    if (n == null || n.currencyName == null) return "g";
    String[] parts = n.currencyName.split(" ");
    return parts[parts.length - 1];
  }

  private void renderHuman(GameState state, int id) {
    com.worldbox.sim.Human h = null;
    for (com.worldbox.sim.Human candidate : state.humans) {
      if (candidate.id == id) { h = candidate; break; }
    }
    if (h == null) { sidePanelMode = null; ctx.setSelection(null); return; }
    Label title = sidePanel.addChild(new Label(h.name));
    title.setFontSize(17);
    closeButton();

    boolean undead = h.nationId == Config.UNDEAD_NATION_ID;
    Nation nation = state.nations.get(h.nationId);
    Settlement settlement = state.settlements.get(h.settlementId);
    statRow("Nation", undead ? "(undead)" : nation != null ? nation.displayName() : "wanderer");
    statRow("Home", settlement != null ? settlement.name : "-");
    statRow("Age", com.worldbox.util.Calendar.ageYears(h.age) + " years");
    if (!undead) {
      statRow("Job", h.job != null ? h.job : "unemployed");
      statRow("Activity", h.state);
      if (h.nationId >= 0) statRow("Routine", h.routine);
      String cur = currencyAbbrev(nation);
      statRow("Wealth", String.format("%.1f %s", h.wealth, cur));
      if (h.debt > 0.5) statRow("Debt", String.format("%.1f %s", h.debt, cur));
      statRow("Housing", h.hasHouse ? "Owns a house" : "Repossessed - homeless");

      Label persHeader = sidePanel.addChild(new Label("PERSONALITY: " + h.personality.archetype()));
      persHeader.setColor(MUTED); persHeader.setFontSize(12);
      statRow("Industrious", String.format("%.0f%%", h.personality.industriousness * 100));
      statRow("Ambition", String.format("%.0f%%", h.personality.ambition * 100));
      statRow("Sociable", String.format("%.0f%%", h.personality.sociability * 100));
      statRow("Wisdom", String.format("%.0f%%", h.personality.wisdom * 100));
      statRow("Greed", String.format("%.0f%%", h.personality.greed * 100));
    }
  }

  private void renderNation(GameState state, int id) {
    Nation n = state.nations.get(id);
    if (n == null) { sidePanelMode = "nationsList"; ctx.setSelection(null); return; }
    Label title = sidePanel.addChild(new Label(n.displayName()));
    title.setFontSize(17);
    title.setColor(new ColorRGBA(((n.color >> 16) & 0xFF) / 255f, ((n.color >> 8) & 0xFF) / 255f, (n.color & 0xFF) / 255f, 1f));
    closeButton();

    if (n.leader != null) {
      Label leaderHeader = sidePanel.addChild(new Label("LEADER"));
      leaderHeader.setColor(MUTED); leaderHeader.setFontSize(12);
      statRow(n.leader.title, n.leader.name);
      statRow("Character", n.leader.personality.archetype());
    }

    int pop = 0;
    double military = 0;
    for (int sid : n.settlementIds) { Settlement s = state.settlements.get(sid); if (s != null) pop += s.populationCount; }
    for (var a : state.armies.values()) if (a.nationId == id) military += a.strength;

    String cur = currencyAbbrev(n);
    statRow("Treasury", (int) Math.floor(n.treasury) + " " + cur);
    statRow("Tax rate", (int) Math.round(n.taxRate * 100) + "%");
    statRow("Wage policy", (int) Math.round(n.wagePolicy * 100) + "% of sale value");

    Label currencyHeader = sidePanel.addChild(new Label("CURRENCY: " + n.currencyName));
    currencyHeader.setColor(MUTED); currencyHeader.setFontSize(12);
    if (n.currencyCollapsed) {
      Label collapsed = sidePanel.addChild(new Label("COLLAPSED - worthless, permanently"));
      collapsed.setColor(new ColorRGBA(0.9f, 0.25f, 0.25f, 1f));
      statRow("Pegged to", n.goldStandard ? "gold (peg broken)" : "nothing (was floating fiat)");
      statRow("In circulation", (int) Math.floor(n.moneySupply) + " " + cur);
    } else {
      statRow("Pegged to", n.goldStandard ? "gold" : "nothing (free-floating fiat)");
      statRow("In circulation", (int) Math.floor(n.moneySupply) + " " + cur);
      statRow("Worth vs peg", String.format("%.3fx", n.exchangeRate));
      statRow("Inflation (last year)", String.format("%+.1f%%", annualInflation(n) * 100));
      statRow("Monetary policy", n.monetaryPolicy);
    }

    statRow("Settlements", String.valueOf(n.settlementIds.size()));
    statRow("Population", String.valueOf(pop));
    statRow("Military power", String.format("%.0f", military));

    Label govHeader = sidePanel.addChild(new Label("GOVERNMENT"));
    govHeader.setColor(MUTED); govHeader.setFontSize(12);
    statRow("Type", n.government);
    Container stabilityRow = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
    Label stabLabel = stabilityRow.addChild(new Label("Stability"));
    stabLabel.setColor(MUTED);
    stabLabel.setPreferredSize(new Vector3f(160, 20, 0));
    Label stabValue = stabilityRow.addChild(new Label(String.format("%.0f%%", n.stability)));
    stabValue.setColor(n.stability < 25 ? DANGER : n.stability < 50 ? ACTIVE : GOOD);
    if (n.stability < 15) {
      Label unrest = sidePanel.addChild(new Label("Unrest brewing - revolt risk!"));
      unrest.setColor(DANGER);
    }
    Container govButtons = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
    for (String gtype : Government.TYPES) {
      Button gb = govButtons.addChild(new Button(gtype));
      if (gtype.equals(n.government)) gb.setColor(ACTIVE);
      gb.addClickCommands(src -> { n.government = gtype; refreshSidePanel(); });
    }

    Button gift = sidePanel.addChild(new Button("Gift 200 gold"));
    gift.setColor(GOOD);
    gift.addClickCommands(src -> Diplomacy.divineGift(state, id, 200));

    Label econHeader = sidePanel.addChild(new Label("ECONOMY"));
    econHeader.setColor(MUTED); econHeader.setFontSize(12);
    Container cycleRow = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
    Label cycleLbl = cycleRow.addChild(new Label("Business cycle"));
    cycleLbl.setColor(MUTED);
    cycleLbl.setPreferredSize(new Vector3f(160, 20, 0));
    Label cycleVal = cycleRow.addChild(new Label(econCycleLabel(n.econCycle)));
    cycleVal.setColor(n.econCycle > 1.08 ? GOOD : n.econCycle < 0.92 ? DANGER : TEXT);
    statRow("Ideology", n.ideology);
    Button toggleIdeology = sidePanel.addChild(new Button(
        n.ideology.equals("capitalism") ? "Switch to Communism" : "Switch to Capitalism"));
    toggleIdeology.addClickCommands(src -> {
      n.ideology = n.ideology.equals("capitalism") ? "communism" : "capitalism";
      refreshSidePanel();
    });

    statRow("Bank reserves", (int) Math.floor(n.bank.reserves) + " " + cur);
    statRow("Bank loans", (int) Math.floor(n.bank.loans) + " " + cur);
    if (n.bank.justCrashed) {
      Label crashLabel = sidePanel.addChild(new Label("BANK RUN! Reserves wiped out."));
      crashLabel.setColor(DANGER);
    }

    int bizCount = 0;
    double bizCapital = 0;
    for (Business b : state.businesses.values()) {
      if (b.nationId != id) continue;
      bizCount++;
      bizCapital += b.capital;
    }
    statRow("Businesses", String.valueOf(bizCount));
    statRow("Business capital", (int) Math.floor(bizCapital) + " " + cur);

    Button viewGraph = sidePanel.addChild(new Button("View Stock Chart"));
    viewGraph.addClickCommands(src -> showGraph("nation", id));

    Label relHeader = sidePanel.addChild(new Label("DIPLOMACY"));
    relHeader.setColor(MUTED); relHeader.setFontSize(12);
    resetListPage(id);
    java.util.List<Nation> others = new java.util.ArrayList<>();
    for (Nation other : state.nations.values()) if (other.id != id) others.add(other);
    int[] range = pager(others.size());
    for (Nation other : others.subList(range[0], range[1])) {
      String status = state.diplomacy.getStatus(id, other.id);
      Container row = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
      Label name = row.addChild(new Label(other.displayName() + " (" + status + ")"));
      name.setPreferredSize(new Vector3f(180, 20, 0));
      name.setColor(statusColor(status));

      Container actions = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
      Button war = actions.addChild(new Button("War"));
      war.setColor(DANGER);
      war.addClickCommands(src -> { Diplomacy.forceWar(state, id, other.id); refreshSidePanel(); });
      Button peace = actions.addChild(new Button("Peace"));
      peace.addClickCommands(src -> { Diplomacy.forcePeace(state, id, other.id); refreshSidePanel(); });
      Button ally = actions.addChild(new Button("Alliance"));
      ally.setColor(GOOD);
      ally.addClickCommands(src -> { Diplomacy.forceAlliance(state, id, other.id); refreshSidePanel(); });
    }
  }

  private ColorRGBA statusColor(String status) {
    switch (status) {
      case Config.WAR: return DANGER;
      case Config.ALLIANCE: return GOOD;
      case Config.TRUCE: return ACTIVE;
      default: return MUTED;
    }
  }

  private void renderNationsList(GameState state) {
    Label title = sidePanel.addChild(new Label("Nations (" + state.nations.size() + ")"));
    title.setFontSize(17);
    closeButton();
    if (state.nations.isEmpty()) {
      Label l = sidePanel.addChild(new Label("No nations yet. Use \"Found Nation\"."));
      l.setColor(MUTED);
      return;
    }
    resetListPage(-2); // fixed key: there's only one nations list, unlike per-nation panels
    java.util.List<Nation> sorted = new java.util.ArrayList<>(state.nations.values());
    sorted.sort((a, b) -> Double.compare(b.treasury, a.treasury));
    int[] range = pager(sorted.size());
    for (Nation n : sorted.subList(range[0], range[1])) {
      int pop = 0;
      for (int sid : n.settlementIds) { Settlement s = state.settlements.get(sid); if (s != null) pop += s.populationCount; }
      Button row = sidePanel.addChild(new Button(
          n.displayName() + "   " + pop + "p  " + (int) Math.floor(n.treasury) + " " + currencyAbbrev(n)));
      row.setColor(new ColorRGBA(((n.color >> 16) & 0xFF) / 255f, ((n.color >> 8) & 0xFF) / 255f, (n.color & 0xFF) / 255f, 1f));
      final int nid = n.id;
      row.addClickCommands(src -> { ctx.setSelection(new GameState.Selection("nation", nid)); refreshSidePanel(); });
    }
  }

  private static final ColorRGBA DISASTER_COLOR = new ColorRGBA(0.95f, 0.6f, 0.25f, 1f);

  private ColorRGBA logCategoryColor(String category) {
    switch (category) {
      case "war": return DANGER;
      case "disaster": return DISASTER_COLOR;
      case "economy": return DANGER;
      default: return TEXT;
    }
  }

  private void renderLog(GameState state) {
    Label title = sidePanel.addChild(new Label("World Log (" + state.eventLog.size() + ")"));
    title.setFontSize(17);
    closeButton();
    if (state.eventLog.isEmpty()) {
      Label l = sidePanel.addChild(new Label("Nothing has happened yet."));
      l.setColor(MUTED);
      return;
    }
    resetListPage(-3); // fixed key: one log, unlike per-nation panels
    // newest first
    java.util.List<WorldEvent> events = new java.util.ArrayList<>(state.eventLog);
    java.util.Collections.reverse(events);
    int[] range = pager(events.size());
    for (WorldEvent e : events.subList(range[0], range[1])) {
      Container row = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
      row.setBackground(UiTextures.cardBackground());
      Label dateLbl = row.addChild(new Label(com.worldbox.util.Calendar.dateString(e.tick)));
      dateLbl.setColor(MUTED);
      dateLbl.setFontSize(12);
      dateLbl.setPreferredSize(new Vector3f(110, 18, 0));
      Label msg = row.addChild(new Label(e.message));
      msg.setColor(logCategoryColor(e.category));
      msg.setFontSize(12);
    }
  }

  private void renderMarket(GameState state) {
    Label title = sidePanel.addChild(new Label("World Market"));
    title.setFontSize(17);
    closeButton();
    if (state.market.crashedThisTick) {
      Label crash = sidePanel.addChild(new Label("MARKET CRASH just hit a resource!"));
      crash.setColor(DANGER);
    }
    GlobalMarket market = state.market;
    for (String key : GlobalMarket.keys()) {
      ArrayDeque<Double> hist = (ArrayDeque<Double>) market.history.get(key);
      double price = market.prices.get(key);
      double base = Config.BASE_PRICES.get(key);
      double prev = hist.size() > 1 ? (Double) hist.toArray()[hist.size() - 2] : price;
      double greed = market.greed.getOrDefault(key, 0.0);
      Container row = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
      Label l = row.addChild(new Label(key));
      l.setPreferredSize(new Vector3f(110, 20, 0));
      Label v = row.addChild(new Label(String.format("%.2fg %s", price, price >= prev ? "↑" : "↓")));
      v.setColor(price >= prev ? GOOD : DANGER);
      v.setPreferredSize(new Vector3f(80, 20, 0));
      if (price > base * 1.8) {
        Label bubble = row.addChild(new Label(greed > 0.5 ? "BUBBLE" : "high"));
        bubble.setColor(greed > 0.5 ? DANGER : ACTIVE);
      }
    }
  }

  private static final String[] GRAPH_METRICS_NATION = {"marketcap", "unemployment", "gdp", "currency", "inflation"};
  private static final String[] GRAPH_METRICS_WORLD = {"marketcap", "gdp"};

  private String metricTabLabel(String metric) {
    switch (metric) {
      case "unemployment": return "Jobs";
      case "gdp": return "GDP";
      case "currency": return "FX";
      case "inflation": return "Inflation";
      default: return "Cap";
    }
  }

  private String metricPanelTitle(String metric) {
    switch (metric) {
      case "unemployment": return "Unemployment";
      case "gdp": return "GDP";
      case "currency": return "Exchange Rate";
      case "inflation": return "Inflation";
      default: return "Market Cap";
    }
  }

  private String formatMetric(String metric, double v) {
    switch (metric) {
      case "unemployment": return String.format("%.1f%%", v * 100);
      case "currency": return String.format("%.3fx", v);
      case "inflation": return String.format("%+.1f%%", v * 100);
      default: return (int) Math.floor(v) + "g";
    }
  }

  /** Every metric but market cap needs a specific nation (unemployment,
   * exchange rate and inflation aren't tracked world-wide - there's no
   * single meaningful "world inflation rate" the way a nation's is). */
  private java.util.ArrayDeque<Double> metricHistory(GameState state, String metric, boolean isWorld, Nation n) {
    switch (metric) {
      case "unemployment": return isWorld ? null : n.unemploymentHistory;
      case "gdp": return isWorld ? state.worldGdpHistory : n.gdpHistory;
      case "currency": return isWorld ? null : n.currencyHistory;
      case "inflation": return isWorld ? null : n.inflationHistory;
      default: return isWorld ? state.worldMarketCapHistory : n.marketCapHistory;
    }
  }

  private void renderGraph(GameState state) {
    boolean isWorld = "world".equals(graphView);
    Nation n = isWorld ? null : state.nations.get(graphNationId);
    if (!isWorld && n == null) { sidePanelMode = "nationsList"; refreshSidePanel(); return; }

    Label title = sidePanel.addChild(new Label((isWorld ? "World Economy" : n.displayName()) + " - " + metricPanelTitle(graphMetric)));
    title.setFontSize(17);
    Container navRow = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
    Button back = navRow.addChild(new Button("Back"));
    back.addClickCommands(src -> {
      if (isWorld) {
        sidePanelMode = "market";
      } else {
        ctx.setSelection(new GameState.Selection("nation", graphNationId));
      }
      graphView = null;
      refreshSidePanel();
    });
    Button close = navRow.addChild(new Button("Close"));
    close.addClickCommands(src -> {
      sidePanelMode = null;
      ctx.setSelection(null);
      graphView = null;
      refreshSidePanel();
    });

    Container tabs = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
    for (String metric : isWorld ? GRAPH_METRICS_WORLD : GRAPH_METRICS_NATION) {
      Button tab = tabs.addChild(new Button(metricTabLabel(metric)));
      if (metric.equals(graphMetric)) tab.setColor(ACTIVE);
      tab.addClickCommands(src -> { graphMetric = metric; refreshSidePanel(); });
    }

    java.util.ArrayDeque<Double> hist = metricHistory(state, graphMetric, isWorld, n);
    if (hist == null || hist.size() < 2) {
      Label l = sidePanel.addChild(new Label("Collecting data..."));
      l.setColor(MUTED);
    } else {
      Double[] arr = hist.toArray(new Double[0]);
      double latest = arr[arr.length - 1];
      double prev = arr[arr.length - 2];
      double change = latest - prev;
      double changePct = Math.abs(prev) > 0.001 ? (change / prev) * 100 : 0;
      double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
      for (double v : hist) { min = Math.min(min, v); max = Math.max(max, v); }
      if ("inflation".equals(graphMetric) && !isWorld) {
        statRow("Inflation (last year)", String.format("%+.1f%%", annualInflation(n) * 100));
      } else {
        statRow(metricTabLabel(graphMetric), formatMetric(graphMetric, latest));
      }
      Container changeRow = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
      Label changeLbl = changeRow.addChild(new Label("Change"));
      changeLbl.setColor(MUTED);
      changeLbl.setPreferredSize(new Vector3f(160, 20, 0));
      Label changeVal = changeRow.addChild(new Label(String.format("%s%.1f%%  (%s%s)",
          change >= 0 ? "+" : "", changePct, change >= 0 ? "+" : "", formatMetric(graphMetric, Math.abs(change)))));
      changeVal.setColor(change >= 0 ? GOOD : DANGER);
      statRow("Range", formatMetric(graphMetric, min) + " - " + formatMetric(graphMetric, max));
    }
    if (!isWorld) {
      statRow("Treasury", (int) Math.floor(n.treasury) + "g");
      statRow("Currency", n.currencyName);
    } else {
      statRow("Treasury", (int) Math.floor(sumLivingTreasury(state)) + "g");
    }

    Label chartSpacer = sidePanel.addChild(new Label(" "));
    chartSpacer.setPreferredSize(new Vector3f(SIDEPANEL_WIDTH - 20, CHART_HEIGHT + 16, 0));
  }

  /** inflationRate is a smoothed per-sample-window figure (samples every
   * 20 ticks), which read as a near-meaningless "+0.1%/window" number to
   * a player. Averaging the last year's worth of windows and annualizing
   * that turns it into the plain "inflation over the last year" percentage
   * everyone actually expects from an "Inflation" readout. */
  private double annualInflation(Nation n) {
    int windowsPerYear = com.worldbox.util.Calendar.MONTHS_PER_YEAR;
    java.util.ArrayDeque<Double> hist = n.inflationHistory;
    if (hist.isEmpty()) return n.inflationRate * windowsPerYear;
    Double[] arr = hist.toArray(new Double[0]);
    int take = Math.min(windowsPerYear, arr.length);
    double sum = 0;
    for (int i = arr.length - take; i < arr.length; i++) sum += arr[i];
    return (sum / take) * windowsPerYear;
  }

  private String econCycleLabel(double cycle) {
    if (cycle > 1.25) return "Booming";
    if (cycle > 1.08) return "Growing";
    if (cycle > 0.92) return "Stable";
    if (cycle > 0.75) return "Slowing";
    return "Recession";
  }

  private double sumLivingTreasury(GameState state) {
    double total = 0;
    for (Nation n : state.nations.values()) if (n.alive) total += n.treasury;
    return total;
  }

  /** Rebuilds the raw jME line-chart geometry for the currently selected
   * graph, positioned by hand beneath the panel's text content. The whole
   * retained history is shown (not just a recent slice) and always
   * rescaled to that data's own min/max, so a long-run plateau reads as a
   * flat line at whatever height it actually sits at instead of looking
   * like the chart itself has a hard ceiling. Segments are colored
   * green/red against the previous sample, like an up/down day on a stock
   * chart, rather than by absolute magnitude. */
  private void layoutChart() {
    for (Geometry g : chartBars) g.removeFromParent();
    chartBars.clear();

    GameState state = ctx.getState();
    boolean isWorld = "world".equals(graphView);
    Nation n = isWorld ? null : state.nations.get(graphNationId);
    if (!isWorld && n == null) return;
    java.util.ArrayDeque<Double> hist = metricHistory(state, graphMetric, isWorld, n);
    if (hist == null || hist.size() < 2) return;

    java.util.List<Double> values = new java.util.ArrayList<>(hist);
    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
    for (double v : values) { min = Math.min(min, v); max = Math.max(max, v); }
    double span = Math.max(1.0, max - min);

    float originX = sidePanel.getLocalTranslation().x + 16;
    float baselineY = sidePanel.getLocalTranslation().y - CHART_TOP_OFFSET - CHART_HEIGHT;
    float stepX = CHART_WIDTH / (values.size() - 1);
    float lineThickness = 2.5f;

    for (int i = 1; i < values.size(); i++) {
      double v0 = values.get(i - 1), v1 = values.get(i);
      float x0 = originX + (i - 1) * stepX, y0 = baselineY + (float) ((v0 - min) / span) * CHART_HEIGHT;
      float x1 = originX + i * stepX, y1 = baselineY + (float) ((v1 - min) / span) * CHART_HEIGHT;
      float dx = x1 - x0, dy = y1 - y0;
      float len = Math.max(0.01f, (float) Math.sqrt(dx * dx + dy * dy));
      Quad quad = new Quad(len, lineThickness);
      Geometry segment = new Geometry("stockLine" + i, quad);
      Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
      mat.setColor("Color", v1 >= v0 ? GOOD : DANGER);
      segment.setMaterial(mat);
      segment.setQueueBucket(RenderQueue.Bucket.Gui);
      segment.setLocalTranslation(x0, y0 - lineThickness / 2f, 2);
      segment.rotate(0, 0, FastMath.atan2(dy, dx));
      chartNode.attachChild(segment);
      chartBars.add(segment);
    }

    Quad baseline = new Quad(CHART_WIDTH, 2f);
    Geometry baselineBar = new Geometry("econBaseline", baseline);
    Material baseMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
    baseMat.setColor("Color", MUTED);
    baselineBar.setMaterial(baseMat);
    baselineBar.setQueueBucket(RenderQueue.Bucket.Gui);
    baselineBar.setLocalTranslation(originX, baselineY - 2f, 2);
    chartNode.attachChild(baselineBar);
    chartBars.add(baselineBar);
  }
}
