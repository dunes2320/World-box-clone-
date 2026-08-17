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
  private static final ColorRGBA MUTED = new ColorRGBA(0.68f, 0.72f, 0.79f, 1f);
  private static final ColorRGBA GOOD = new ColorRGBA(0.31f, 0.75f, 0.42f, 1f);
  private static final ColorRGBA DANGER = new ColorRGBA(0.88f, 0.33f, 0.30f, 1f);

  private final HudContext ctx;
  private final AssetManager assetManager;
  private final float screenW, screenH;
  // Every font size and icon-button dimension in this file was authored
  // as a bare pixel constant against a ~900px-tall reference window. On a
  // genuinely high-resolution or fullscreen display those same pixel
  // counts are a much smaller fraction of the screen, so the whole HUD
  // read as tiny relative to the window - this scales every one of those
  // constants by how much taller the real window is than that reference,
  // clamped so a very small or very large window doesn't produce
  // illegibly tiny or comically huge text either.
  private final float uiScale;

  /** Scales a base font-size/icon-margin constant by uiScale. */
  private float fs(float base) { return base * uiScale; }

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

  // The nation panel grew a leader/currency/government/economy/diplomacy
  // section over several rounds until it was taller than the screen with
  // no way to scroll to the rest - same fix as the list pagination above:
  // split it into tabs so each one fits, and jump back to the first tab
  // whenever a different nation gets selected.
  private static final String[] NATION_TABS = {"Overview", "Government", "Economy", "Diplomacy"};
  private String nationTab = NATION_TABS[0];
  private int nationTabKey = Integer.MIN_VALUE;

  private void resetNationTab(int key) {
    if (key != nationTabKey) { nationTabKey = key; nationTab = NATION_TABS[0]; }
  }

  private void nationTabBar() {
    Container tabs = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
    for (String t : NATION_TABS) {
      Button b = tabs.addChild(new Button(t));
      if (t.equals(nationTab)) b.setColor(ACTIVE);
      b.addClickCommands(src -> { nationTab = t; refreshSidePanel(); });
    }
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
  private final java.util.List<com.jme3.font.BitmapText> chartLabels = new java.util.ArrayList<>();
  private final com.jme3.font.BitmapFont chartFont;
  private String graphView; // null | "nation" | "world"
  private int graphNationId = -1;
  private String graphMetric = "marketcap"; // marketcap | unemployment | gdp | currency

  // scaled by uiScale, assigned in the constructor
  private final float SIDEPANEL_WIDTH;
  private final float CHART_WIDTH;
  private final float CHART_HEIGHT;
  private final float LEGEND_ROW_HEIGHT;

  // Measured after topBar's children are built rather than hardcoded - the
  // bar's real height depends on its tallest child (the icon buttons), and
  // a stale guess here is exactly what let the notification toast render
  // partly hidden behind the bar before.
  private float topBarHeight = 48f;
  private float dockTopY = 200f;

  // Current on-screen bounding box of sidePanel (updated every refresh,
  // see refreshSidePanel) - used by isOverUi since the panel is now
  // centered rather than docked to a fixed screen edge, so its bounds
  // move depending on content.
  private float sidePanelX, sidePanelY, sidePanelW, sidePanelH;

  // Whether any popup (side panel or a world-object selection) was open
  // last refresh - drives the auto-pause-while-reading behavior below.
  private boolean panelWasOpen = false;
  private int speedBeforePause = 1;

  /** Coarse screen-space guard so a click on a HUD panel doesn't also paint
   * the world underneath it. Panel positions are fixed (non-resizable
   * window), so a few hardcoded regions are enough. The tool dock now
   * lives bottom-center instead of running up the whole left edge, so its
   * guard is a bounded box, not a full-height column. */
  public boolean isOverUi(float sx, float sy) {
    if (sy > screenH - topBarHeight) return true;
    if (sy < dockTopY) {
      float dockLeft = screenW / 2f - DOCK_WIDTH_ESTIMATE / 2f;
      if (sx > dockLeft && sx < dockLeft + DOCK_WIDTH_ESTIMATE) return true;
    }
    if (sidePanel.getCullHint() != com.jme3.scene.Spatial.CullHint.Always
        && sx >= sidePanelX && sx <= sidePanelX + sidePanelW
        && sy <= sidePanelY && sy >= sidePanelY - sidePanelH) return true;
    return false;
  }

  public GameHud(Node guiNode, AssetManager assets, int width, int height, HudContext ctx) {
    this.ctx = ctx;
    this.assetManager = assets;
    this.screenW = width;
    this.screenH = height;
    this.uiScale = Math.max(0.9f, Math.min(1.9f, height / 900f));
    this.SIDEPANEL_WIDTH = 330f * uiScale;
    this.CHART_WIDTH = 290f * uiScale;
    this.CHART_HEIGHT = 150f * uiScale;
    this.LEGEND_ROW_HEIGHT = 16f * uiScale;
    this.DOCK_ICON_MARGIN = 6f * uiScale;
    this.DOCK_WIDTH_ESTIMATE = 620f * uiScale;

    // Minimal top bar: a wordmark, a couple of compact stat chips, speed
    // controls, and icon buttons for the occasional-use menus - not a row
    // of full-word buttons eating a quarter of the screen width.
    topBar.setLocalTranslation(0, height, 1);
    topBar.setBackground(UiTextures.panelBackground());
    guiNode.attachChild(topBar);

    Label title = topBar.addChild(new Label("WORLD BOX"));
    title.setFontSize(fs(18));
    title.setColor(TEXT);
    topBar.addChild(spacer(20));
    statLabel = topBar.addChild(new Label("Pop: 0   Nations: 0"));
    statLabel.setColor(MUTED);
    statLabel.setBackground(UiTextures.chipBackground());
    topBar.addChild(spacer(24));

    for (int speed : new int[]{0, 1, 2, 4}) {
      Button b = topBar.addChild(new Button(speedLabel(speed)));
      b.setFontSize(fs(13));
      b.addClickCommands(src -> { ctx.setGameSpeed(speed); refreshSpeedButtons(); });
      speedButtons.put(speed, b);
      topBar.addChild(spacer(3));
    }
    topBar.addChild(spacer(16));
    topBar.addChild(menuIconButton("menu_nations", () -> {
      sidePanelMode = "nationsList".equals(sidePanelMode) ? null : "nationsList";
      ctx.setSelection(null);
      refreshSidePanel();
    }));
    topBar.addChild(menuIconButton("menu_market", () -> {
      sidePanelMode = "market".equals(sidePanelMode) ? null : "market";
      ctx.setSelection(null);
      refreshSidePanel();
    }));
    topBar.addChild(menuIconButton("menu_log", () -> {
      sidePanelMode = "log".equals(sidePanelMode) ? null : "log";
      ctx.setSelection(null);
      resetListPage(-999);
      refreshSidePanel();
    }));
    topBar.addChild(menuIconButton("menu_settings", () -> {
      sidePanelMode = "settings".equals(sidePanelMode) ? null : "settings";
      ctx.setSelection(null);
      refreshSidePanel();
    }));
    topBar.addChild(spacer(10));
    topBar.addChild(menuIconButton("reset", ctx::resetWorld));

    // Icon buttons (48px-ish incl. margins) are taller than the plain text
    // this bar used to hold, so its real rendered height is bigger than any
    // hardcoded guess - measure it now and use that everywhere below
    // instead of re-guessing a constant.
    topBarHeight = Math.max(46f, topBar.getPreferredSize().y);

    // A bottom-center icon dock for the god tools, WorldBox-style, instead
    // of a tall always-open text list running down the left edge of the
    // screen - the previous layout's dominant source of visual clutter.
    // Anchored with a small Y (this coordinate system's origin is bottom-
    // left, Y increasing upward) so it sits near the bottom regardless of
    // screen height, same as topBar anchors to the top via `height`.
    toolbar.setBackground(UiTextures.panelBackground());
    guiNode.attachChild(toolbar);
    buildToolbar();
    // Measured after the dock is fully populated (three rows: category
    // tabs, tool icons, status line - each with icon+caption now, taller
    // than a single icon row) rather than a hardcoded guess - this is the
    // same lesson the top bar height learned: a stale guess here is exactly
    // what let the bottom status row clip off-screen or overlap the row
    // above it before.
    float dockHeight = Math.max(140f, toolbar.getPreferredSize().y);
    dockTopY = dockHeight + 20f;
    toolbar.setLocalTranslation(width / 2f - DOCK_WIDTH_ESTIMATE / 2f, dockTopY, 1);

    sidePanel.setBackground(UiTextures.panelBackground());
    guiNode.attachChild(sidePanel);
    sidePanel.setCullHint(Spatial.CullHint.Always);

    chartNode.setQueueBucket(RenderQueue.Bucket.Gui);
    chartNode.setCullHint(Spatial.CullHint.Always);
    guiNode.attachChild(chartNode);
    chartFont = assetManager.loadFont("Interface/Fonts/Default.fnt");

    toastLabel = new Label(" ");
    toastLabel.setFontSize(fs(15));
    toastLabel.setColor(TEXT);
    toastLabel.setBackground(UiTextures.panelBackground());
    toastLabel.setTextHAlignment(com.simsilica.lemur.HAlignment.Center);
    toastLabel.setTextVAlignment(com.simsilica.lemur.VAlignment.Center);
    toastLabel.setPreferredSize(new Vector3f(520 * uiScale, 42 * uiScale, 0));
    toastLabel.setLocalTranslation(width / 2f - 260, height - topBarHeight - 18, 5);
    toastLabel.setCullHint(Spatial.CullHint.Always);
    guiNode.attachChild(toastLabel);

    refreshSpeedButtons();
  }

  private static String speedLabel(int s) {
    switch (s) { case 0: return "Pause"; case 1: return "1x"; case 2: return "2x"; default: return "4x"; }
  }

  /** A small icon-only button for an infrequent top-bar action (open a
   * menu, reset the world) - a soft permanent slot background (not just on
   * hover/active) so the glyph reads clearly regardless of how bright the
   * 3D world behind the bar happens to be. */
  private Button menuIconButton(String iconKey, Runnable onClick) {
    Button b = new Button("");
    b.setIcon(new com.simsilica.lemur.component.IconComponent(
        IconTextures.icon(iconKey), new com.jme3.math.Vector2f(0.75f * uiScale, 0.75f * uiScale), fs(6), fs(6), 0, false));
    b.setBackground(UiTextures.iconSlotBackground());
    b.addClickCommands(src -> onClick.run());
    return b;
  }

  private Label spacer(float width) {
    Label l = new Label(" ");
    l.setPreferredSize(new Vector3f(width * uiScale, 1, 0));
    return l;
  }

  private static final String[] TOOL_TABS = {"Terrain", "Civilizations", "Creatures", "Disasters", "Powers"};
  private static final Map<String, String> TAB_ICONS = new LinkedHashMap<>();
  private static final Map<String, String> TAB_CAPTIONS = new LinkedHashMap<>();
  static {
    TAB_ICONS.put("Terrain", "tab_terrain");
    TAB_ICONS.put("Civilizations", "tab_civ");
    TAB_ICONS.put("Creatures", "tab_creatures");
    TAB_ICONS.put("Disasters", "tab_disasters");
    TAB_ICONS.put("Powers", "tab_powers");
    TAB_CAPTIONS.put("Terrain", "Terrain");
    TAB_CAPTIONS.put("Civilizations", "Civs");
    TAB_CAPTIONS.put("Creatures", "Fauna");
    TAB_CAPTIONS.put("Disasters", "Disasters");
    TAB_CAPTIONS.put("Powers", "Powers");
  }

  // Short captions for the dock's per-tool icons - distinct from
  // GodTools.ToolDef.name (used in the status line, where "Plant Forest" or
  // "Found Nation" reads fine) since a caption under a ~40px icon has to
  // stay short or it forces the column wider than the icon itself.
  private static final Map<String, String> TOOL_CAPTIONS = new LinkedHashMap<>();
  static {
    TOOL_CAPTIONS.put("select", "Select");
    TOOL_CAPTIONS.put("water", "Water");
    TOOL_CAPTIONS.put("sand", "Sand");
    TOOL_CAPTIONS.put("grass", "Grass");
    TOOL_CAPTIONS.put("dirt", "Dirt");
    TOOL_CAPTIONS.put("stone", "Stone");
    TOOL_CAPTIONS.put("dig", "Dig");
    TOOL_CAPTIONS.put("build", "Build");
    TOOL_CAPTIONS.put("forest", "Forest");
    TOOL_CAPTIONS.put("human", "Human");
    TOOL_CAPTIONS.put("foundNation", "Nation");
    TOOL_CAPTIONS.put("monster", "Kaiju");
    TOOL_CAPTIONS.put("zombie", "Outbreak");
    TOOL_CAPTIONS.put("fire", "Fire");
    TOOL_CAPTIONS.put("meteor", "Meteor");
    TOOL_CAPTIONS.put("nuke", "Nuke");
    TOOL_CAPTIONS.put("earthquake", "Quake");
    TOOL_CAPTIONS.put("tornado", "Tornado");
    TOOL_CAPTIONS.put("extinguish", "Douse");
    TOOL_CAPTIONS.put("storm", "Storm");
    TOOL_CAPTIONS.put("blessing", "Bless");
  }

  private final float DOCK_ICON_MARGIN;
  private final float DOCK_WIDTH_ESTIMATE;

  private Label activeToolLabel;

  /** A column: an icon button on top, a small caption label centered
   * beneath it - so a first-time player can tell what a glyph means
   * without having to click it and read the status line first. */
  private Container iconColumn(Button icon, String caption) {
    Container col = new Container(new SpringGridLayout(Axis.Y, Axis.X));
    col.addChild(icon);
    Label lbl = col.addChild(new Label(caption));
    lbl.setFontSize(fs(10));
    lbl.setColor(MUTED);
    lbl.setTextHAlignment(com.simsilica.lemur.HAlignment.Center);
    lbl.setPreferredSize(new Vector3f(Math.max(fs(40), lbl.getPreferredSize().x), fs(13), 0));
    return col;
  }

  /** A WorldBox-style bottom dock: a row of category icons, a row of that
   * category's tool icons, and a compact status line (current tool name +
   * brush size) - the whole god-tools UI in about 130px of height instead
   * of a permanently-open sidebar. Select is pinned in the category row
   * since it's the default/most-used tool, not buried in a category. */
  private void buildToolbar() {
    Container tabRow = toolbar.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
    GodTools.ToolDef selectDef = GodTools.TOOLS.get(0);
    Button selectBtn = new Button("");
    selectBtn.setIcon(new com.simsilica.lemur.component.IconComponent(
        IconTextures.icon(selectDef.id), new com.jme3.math.Vector2f(0.8f * uiScale, 0.8f * uiScale), DOCK_ICON_MARGIN, DOCK_ICON_MARGIN, 0, false));
    selectBtn.setBackground(UiTextures.iconSlotBackground());
    selectBtn.addClickCommands(src -> { ctx.setTool(selectDef.id); refreshToolButtons(); });
    toolButtons.put(selectDef.id, selectBtn);
    tabRow.addChild(iconColumn(selectBtn, TOOL_CAPTIONS.get(selectDef.id)));
    tabRow.addChild(spacer(16));
    for (String tabName : TOOL_TABS) {
      Button tabBtn = new Button("");
      tabBtn.setIcon(new com.simsilica.lemur.component.IconComponent(
          IconTextures.icon(TAB_ICONS.get(tabName)), new com.jme3.math.Vector2f(0.68f * uiScale, 0.68f * uiScale), DOCK_ICON_MARGIN, DOCK_ICON_MARGIN, 0, false));
      tabBtn.setBackground(UiTextures.iconSlotBackground());
      tabBtn.addClickCommands(src -> {
        activeToolTab = tabName;
        rebuildToolGroup();
      });
      toolTabButtons.put(tabName, tabBtn);
      tabRow.addChild(iconColumn(tabBtn, TAB_CAPTIONS.get(tabName)));
    }

    toolGroupContainer = toolbar.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
    rebuildToolGroup();

    Label rowGap = toolbar.addChild(new Label(" "));
    rowGap.setPreferredSize(new Vector3f(1 * uiScale, 8 * uiScale, 0));

    Container statusRow = toolbar.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
    activeToolLabel = statusRow.addChild(new Label(selectDef.name));
    activeToolLabel.setColor(ACTIVE);
    activeToolLabel.setFontSize(fs(13));
    activeToolLabel.setPreferredSize(new Vector3f(110 * uiScale, 18 * uiScale, 0));
    statusRow.addChild(spacer(14));
    Label brushHeader = statusRow.addChild(new Label("Brush"));
    brushHeader.setColor(MUTED);
    brushHeader.setFontSize(fs(12));
    brushSlider = statusRow.addChild(new Slider(new DefaultRangedValueModel(1, 9, ctx.getBrushSize()), Axis.X));
    brushSlider.setDelta(1);
    brushSlider.setPreferredSize(new Vector3f(100 * uiScale, 20 * uiScale, 0));
    brushLabel = statusRow.addChild(new Label(String.valueOf(ctx.getBrushSize())));
    brushLabel.setColor(MUTED);
    brushLabel.setFontSize(fs(12));

    refreshToolButtons();
  }

  private void rebuildToolGroup() {
    toolGroupContainer.clearChildren();
    for (String tabName : TOOL_TABS) {
      Button b = toolTabButtons.get(tabName);
      boolean active = tabName.equals(activeToolTab);
      b.setBackground(active ? UiTextures.activeButtonBackground() : UiTextures.iconSlotBackground());
    }
    boolean first = true;
    for (GodTools.ToolDef tool : GodTools.TOOLS) {
      if (!tool.group.equals(activeToolTab)) continue;
      if (!first) toolGroupContainer.addChild(spacer(4));
      first = false;
      Button b = new Button("");
      b.setIcon(new com.simsilica.lemur.component.IconComponent(
          IconTextures.icon(tool.id), new com.jme3.math.Vector2f(0.85f * uiScale, 0.85f * uiScale), DOCK_ICON_MARGIN, DOCK_ICON_MARGIN, 0, false));
      b.setBackground(UiTextures.iconSlotBackground());
      b.addClickCommands(src -> {
        ctx.setTool(tool.id);
        refreshToolButtons();
      });
      toolButtons.put(tool.id, b);
      toolGroupContainer.addChild(iconColumn(b, TOOL_CAPTIONS.getOrDefault(tool.id, tool.name)));
    }
    refreshToolButtons();
  }

  private void refreshToolButtons() {
    String active = ctx.getTool();
    for (Map.Entry<String, Button> e : toolButtons.entrySet()) {
      boolean isActive = e.getKey().equals(active);
      e.getValue().setBackground(isActive ? UiTextures.activeButtonBackground() : UiTextures.iconSlotBackground());
    }
    for (GodTools.ToolDef tool : GodTools.TOOLS) {
      if (tool.id.equals(active) && activeToolLabel != null) activeToolLabel.setText(tool.name);
    }
  }

  private void refreshSpeedButtons() {
    int active = ctx.getGameSpeed();
    if (active == lastSpeedShown) return;
    lastSpeedShown = active;
    for (Map.Entry<Integer, Button> e : speedButtons.entrySet()) {
      boolean isActive = e.getKey() == active;
      e.getValue().setColor(isActive ? ACTIVE : TEXT);
      e.getValue().setBackground(isActive ? UiTextures.activeButtonBackground() : null);
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

  /** Escape: closes whatever popup is open (a selection or a menu panel);
   * if nothing's open, it opens Settings instead - the standard "escape
   * always gets you somewhere useful, and eventually to quit" convention. */
  public void handleEscape() {
    boolean anyOpen = ctx.getSelection() != null || sidePanelMode != null;
    if (anyOpen) {
      sidePanelMode = null;
      ctx.setSelection(null);
      graphView = null;
    } else {
      sidePanelMode = "settings";
      ctx.setSelection(null);
    }
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
    // and reset it out from under the player's mouse. The refresh cadence
    // stays moderate (not every frame) since a full clear+rebuild still
    // costs real GPU buffer churn even without per-row backgrounds.
    if (now - lastPanelRefresh > 1.5 && !"settings".equals(sidePanelMode)) {
      lastPanelRefresh = now;
      refreshSidePanel();
    }
  }

  private void refreshSidePanel() {
    GameState state = ctx.getState();
    GameState.Selection sel = ctx.getSelection();
    String mode = sel != null ? sel.type : sidePanelMode;

    // Any popup - a settlement/nation/human selection or a menu panel -
    // pauses the sim while it's open so the player can actually read it
    // instead of numbers/positions changing underneath them. Only the
    // open/close transition touches game speed, not every periodic
    // refresh (this runs every ~1.5s while a panel stays open), and a
    // speed the player explicitly picks while a panel is open is left
    // alone - closing only restores the old speed if nothing since un-
    // paused it.
    boolean opening = mode != null;
    if (opening && !panelWasOpen) {
      speedBeforePause = ctx.getGameSpeed();
      ctx.setGameSpeed(0);
    } else if (!opening && panelWasOpen) {
      if (ctx.getGameSpeed() == 0) ctx.setGameSpeed(speedBeforePause);
    }
    panelWasOpen = opening;

    sidePanel.clearChildren();
    if (mode == null) {
      sidePanel.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
      // this early return used to skip the chart-hiding block below
      // entirely, so closing the graph (via Close, Escape, or toggling a
      // top-bar icon off - anything that lands on mode==null rather than
      // switching straight to a different non-graph panel) left its raw
      // jME quads rendered on screen forever, until some other panel open
      // happened to reach that code and finally hide them.
      chartNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
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

    // Centered on screen (clamped below the top bar, with a margin on
    // every side) instead of docked at a hardcoded 320px from the right
    // edge - that fixed offset was smaller than the panel's real,
    // uiScale-dependent width (up to 330*1.9=627px), so in fullscreen or
    // on a tall display the right side of every popup - including the
    // whole economy chart - rendered off-screen. Measuring the panel's
    // actual preferred size after it's populated keeps this correct at
    // any resolution instead of guessing a fixed position.
    Vector3f pref = sidePanel.getPreferredSize();
    float w = pref.x, h = pref.y;
    float margin = 16f * uiScale;
    float x = Math.max(margin, Math.min(screenW - w - margin, (screenW - w) / 2f));
    float y = Math.max(h + margin, Math.min(screenH - topBarHeight - margin, (screenH + h) / 2f));
    sidePanel.setLocalTranslation(x, y, 1);
    sidePanelX = x; sidePanelY = y; sidePanelW = w; sidePanelH = h;

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
    l.setPreferredSize(new Vector3f(160 * uiScale, 20 * uiScale, 0));
    Label v = row.addChild(new Label(value));
    v.setColor(TEXT);
  }

  private void renderSettings() {
    Label title = sidePanel.addChild(new Label("Settings"));
    title.setFontSize(fs(17));
    closeButton();

    Label camHeader = sidePanel.addChild(new Label("CAMERA"));
    camHeader.setColor(MUTED); camHeader.setFontSize(fs(12));

    Label zoomHeader = sidePanel.addChild(new Label("Scroll zoom sensitivity"));
    zoomHeader.setColor(TEXT);
    zoomSlider = sidePanel.addChild(new Slider(new DefaultRangedValueModel(0.2, 3.0, ctx.getZoomSensitivity()), Axis.X));
    zoomSlider.setDelta(0.1);
    zoomSlider.setPreferredSize(new Vector3f(260 * uiScale, 24 * uiScale, 0));
    zoomLabel = sidePanel.addChild(new Label(String.format("%.1fx", ctx.getZoomSensitivity())));
    zoomLabel.setColor(MUTED);

    Label saveHeader = sidePanel.addChild(new Label("SAVE / LOAD"));
    saveHeader.setColor(MUTED); saveHeader.setFontSize(fs(12));
    for (com.worldbox.save.SaveManager.SlotInfo slot : ctx.listSaveSlots()) {
      Container row = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
      String[] lines = slot.summary.split("\n");
      Label name = row.addChild(new Label("Slot " + slot.slot + ": " + lines[0]));
      name.setPreferredSize(new Vector3f(280 * uiScale, 20 * uiScale, 0));
      name.setColor(slot.occupied ? TEXT : MUTED);

      Container actions = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
      Button save = actions.addChild(new Button("Save"));
      save.setColor(GOOD);
      save.addClickCommands(src -> { ctx.saveToSlot(slot.slot); refreshSidePanel(); });
      if (slot.occupied) {
        Button load = actions.addChild(new Button("Load"));
        load.addClickCommands(src -> { ctx.loadFromSlot(slot.slot); refreshSidePanel(); });
        Button del = actions.addChild(new Button("Delete"));
        del.setColor(DANGER);
        del.addClickCommands(src -> { ctx.deleteSlot(slot.slot); refreshSidePanel(); });
      }
    }

    Label gameHeader = sidePanel.addChild(new Label("GAME"));
    gameHeader.setColor(MUTED); gameHeader.setFontSize(fs(12));
    Button quit = sidePanel.addChild(new Button("Quit Game"));
    quit.setColor(DANGER);
    quit.addClickCommands(src -> ctx.quitGame());
  }

  private void renderSettlement(GameState state, int id) {
    Settlement s = state.settlements.get(id);
    if (s == null) { sidePanelMode = null; ctx.setSelection(null); return; }
    Label title = sidePanel.addChild(new Label(s.name));
    title.setFontSize(fs(17));
    closeButton();
    Nation nation = state.nations.get(s.nationId);
    statRow("Nation", s.abandoned ? "(abandoned ruin)" : nation != null ? nation.displayName() : "-");
    statRow("Population", String.valueOf(s.populationCount));
    statRow("Housing", s.populationCount + " / " + (int) (s.housingStock * Settlement.PEOPLE_PER_HOUSE));
    statRow("Territory radius", String.format("%.1f", s.radius));
    boolean underAttack = s.garrisonHp >= 0 && s.garrisonHp < com.worldbox.sim.Military.garrisonMax(s);
    if (s.garrisonHp <= 0 && underAttack) {
      statRow("Garrison", "wiped out - city falling! (" + (int) s.siegeProgress + "%)");
    } else if (underAttack) {
      statRow("Garrison", (int) s.garrisonHp + " / " + (int) com.worldbox.sim.Military.garrisonMax(s));
    } else {
      statRow("Under siege", "no");
    }

    Label stockHeader = sidePanel.addChild(new Label("STOCKPILE"));
    stockHeader.setColor(MUTED); stockHeader.setFontSize(fs(12));
    for (Map.Entry<String, Double> e : s.stock.entrySet()) {
      statRow(e.getKey(), String.valueOf((int) Math.floor(e.getValue())));
    }

    int era = nation != null ? Nation.era(state, nation) : Config.ERA_ANCIENT;
    Label raiseHeader = sidePanel.addChild(new Label("RAISE ARMY (" + Config.ERA_NAMES[era] + " Era)"));
    raiseHeader.setColor(MUTED); raiseHeader.setFontSize(fs(12));
    Label msg = new Label("");
    msg.setColor(MUTED);
    for (Config.UnitSpec spec : Config.UNIT_TYPES.values()) {
      if (spec.era > era) continue;
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
    title.setFontSize(fs(17));
    closeButton();

    boolean undead = h.nationId == Config.UNDEAD_NATION_ID;
    Nation nation = state.nations.get(h.nationId);
    Settlement settlement = state.settlements.get(h.settlementId);
    statRow("Nation", undead ? "(undead)" : nation != null ? nation.displayName() : "wanderer");
    statRow("Home", settlement != null ? settlement.name : "-");
    statRow("Age", com.worldbox.util.Calendar.ageYears(h.age) + " years");
    if (!undead) {
      if ("soldier".equals(h.role)) {
        // a soldier is this settlement's own person, not a separate kind
        // of unit - show who they're serving under and what that army is
        // currently doing instead of the ordinary job/routine rows, which
        // don't apply while they're away.
        com.worldbox.sim.Army servingArmy = null;
        for (com.worldbox.sim.Army a : state.armies.values()) {
          if (!a.dead && a.memberHumanIds.contains(h.id)) { servingArmy = a; break; }
        }
        statRow("Status", "Serving in the military");
        if (servingArmy != null) {
          statRow("Deployment", servingArmy.targetSettlementId != null ? "Marching to war"
              : servingArmy.combatFlashTimer > 0 ? "In combat" : "Garrisoned, awaiting orders");
        }
      } else {
        statRow("Job", h.job != null ? h.job : "unemployed");
        statRow("Activity", h.state);
        if (h.nationId >= 0) statRow("Routine", h.routine);
      }
      String cur = currencyAbbrev(nation);
      statRow("Wealth", String.format("%.1f %s", h.wealth, cur));
      if (h.debt > 0.5) statRow("Debt", String.format("%.1f %s", h.debt, cur));
      statRow("Housing", h.hasHouse ? "Owns a house" : "Repossessed - homeless");

      Label persHeader = sidePanel.addChild(new Label("PERSONALITY: " + h.personality.archetype()));
      persHeader.setColor(MUTED); persHeader.setFontSize(fs(12));
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
    title.setFontSize(fs(17));
    title.setColor(new ColorRGBA(((n.color >> 16) & 0xFF) / 255f, ((n.color >> 8) & 0xFF) / 255f, (n.color & 0xFF) / 255f, 1f));
    closeButton();
    resetNationTab(id);
    nationTabBar();

    int pop = 0;
    double military = 0;
    for (int sid : n.settlementIds) { Settlement s = state.settlements.get(sid); if (s != null) pop += s.populationCount; }
    for (var a : state.armies.values()) if (a.nationId == id) military += a.strength;
    String cur = currencyAbbrev(n);

    if (nationTab.equals("Overview")) {
      if (n.leader != null) {
        Label leaderHeader = sidePanel.addChild(new Label("LEADER"));
        leaderHeader.setColor(MUTED); leaderHeader.setFontSize(fs(12));
        statRow(n.leader.title, n.leader.name);
        statRow("Character", n.leader.personality.archetype());
      }
      statRow("Treasury", (int) Math.floor(n.treasury) + " " + cur);
      statRow("Tax rate", (int) Math.round(n.taxRate * 100) + "%");
      statRow("Wage policy", (int) Math.round(n.wagePolicy * 100) + "% of sale value");
      statRow("Settlements", String.valueOf(n.settlementIds.size()));
      statRow("Population", String.valueOf(pop));
      statRow("Military power", String.format("%.0f", military));
      // how much of the map this nation actually controls right now - a
      // direct answer to "how big is this nation" beyond settlement count,
      // and one that now actually sticks once claimed (see
      // Settlement.claimTerritory - peaceful growth can no longer take an
      // already-claimed cell, only conquest can)
      int territoryCells = 0;
      for (int owner : state.grid.ownerNation) if (owner == id) territoryCells++;
      statRow("Territory", territoryCells + " cells");
    } else if (nationTab.equals("Government")) {
      statRow("Type", n.government);
      Container stabilityRow = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
      Label stabLabel = stabilityRow.addChild(new Label("Stability"));
      stabLabel.setColor(MUTED);
      stabLabel.setPreferredSize(new Vector3f(160 * uiScale, 20 * uiScale, 0));
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
    } else if (nationTab.equals("Economy")) {
      Label currencyHeader = sidePanel.addChild(new Label("CURRENCY: " + n.currencyName));
      currencyHeader.setColor(MUTED); currencyHeader.setFontSize(fs(12));
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

      Label econHeader = sidePanel.addChild(new Label("ECONOMY"));
      econHeader.setColor(MUTED); econHeader.setFontSize(fs(12));
      Container cycleRow = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
      Label cycleLbl = cycleRow.addChild(new Label("Business cycle"));
      cycleLbl.setColor(MUTED);
      cycleLbl.setPreferredSize(new Vector3f(160 * uiScale, 20 * uiScale, 0));
      Label cycleVal = cycleRow.addChild(new Label(econCycleLabel(n.econCycle)));
      cycleVal.setColor(n.econCycle > 1.08 ? GOOD : n.econCycle < 0.92 ? DANGER : TEXT);
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
      statRow("GDP (this window)", (int) Math.floor(n.gdpAccum) + " " + cur);
      statRow("GDP per capita", (pop > 0 ? String.format("%.1f", n.gdpAccum / pop) : "-") + " " + cur);
      statRow("Unemployment", String.format("%.0f%%", n.unemploymentRate * 100));
      double totalWealth = 0; int counted = 0;
      for (com.worldbox.sim.Human h : state.humans) if (h.nationId == id) { totalWealth += h.wealth; counted++; }
      statRow("Avg citizen wealth", (counted > 0 ? String.format("%.1f", totalWealth / counted) : "-") + " " + cur);

      Label housingHeader = sidePanel.addChild(new Label("HOUSING MARKET"));
      housingHeader.setColor(MUTED); housingHeader.setFontSize(fs(12));
      int owners = 0;
      for (com.worldbox.sim.Human h : state.humans) if (h.nationId == id && h.hasHouse) owners++;
      double ownRate = pop > 0 ? (double) owners / pop * 100 : 0;
      statRow("Homeowners", owners + " / " + pop + " (" + String.format("%.0f%%", ownRate) + ")");
      int totalHouses = 0;
      for (int sid : n.settlementIds) {
        Settlement s = state.settlements.get(sid);
        if (s != null) totalHouses += s.housingStock;
      }
      double houseCapacity = totalHouses * Settlement.PEOPLE_PER_HOUSE;
      statRow("Vacant house room", String.format("%.0f", Math.max(0, houseCapacity - owners)));

      Label tradeHeader = sidePanel.addChild(new Label("TRADE & LAND"));
      tradeHeader.setColor(MUTED); tradeHeader.setFontSize(fs(12));
      statRow("Land value index", String.format("%.2fx", n.landValueIndex));
      statRow("Export tax", (int) Math.round(n.exportTaxRate * 100) + "%");
      statRow("Import tax", (int) Math.round(n.importTaxRate * 100) + "%");
      // a real government stockpile (see Nation.stockpile) - only the
      // goods actually worth showing (nothing rounds to zero) so an empty
      // reserve just reads as no rows rather than a wall of "0"s
      boolean anyStock = false;
      for (String key : GlobalMarket.keys()) {
        double amt = n.stockpile.getOrDefault(key, 0.0);
        if (amt < 1) continue;
        anyStock = true;
        statRow("Stockpiled " + key, String.format("%.0f", amt));
      }
      if (!anyStock) {
        Label none = sidePanel.addChild(new Label("No government stockpile yet"));
        none.setColor(MUTED);
      }

      Container graphButtons = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
      Button viewGraph = graphButtons.addChild(new Button("Stock Chart"));
      viewGraph.addClickCommands(src -> showGraph("nation", id));
      Button viewSectors = graphButtons.addChild(new Button("Sector Chart"));
      viewSectors.addClickCommands(src -> { showGraph("nation", id); graphMetric = "sectors"; refreshSidePanel(); });
      return;
    }
    if (!nationTab.equals("Diplomacy")) return;

    Label relHeader = sidePanel.addChild(new Label("DIPLOMACY"));
    relHeader.setColor(MUTED); relHeader.setFontSize(fs(12));
    resetListPage(id);
    java.util.List<Nation> others = new java.util.ArrayList<>();
    for (Nation other : state.nations.values()) if (other.id != id) others.add(other);
    int[] range = pager(others.size());
    for (Nation other : others.subList(range[0], range[1])) {
      String status = state.diplomacy.getStatus(id, other.id);
      Container row = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
      Label name = row.addChild(new Label(other.displayName() + " (" + status + ")"));
      name.setPreferredSize(new Vector3f(180 * uiScale, 20 * uiScale, 0));
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
    title.setFontSize(fs(17));
    closeButton();
    if (state.nations.isEmpty()) {
      Label l = sidePanel.addChild(new Label("No nations yet. Use \"Found Nation\"."));
      l.setColor(MUTED);
      return;
    }

    // WORLD OVERVIEW: aggregate measures across every living nation, not
    // just whichever one happens to be open - the world-scale economy
    // readout the player asked for.
    Label worldHeader = sidePanel.addChild(new Label("WORLD OVERVIEW"));
    worldHeader.setColor(MUTED); worldHeader.setFontSize(fs(12));
    long worldPop = 0;
    double worldTreasury = 0, worldGdp = 0;
    int claimedCells = 0;
    for (int owner : state.grid.ownerNation) if (owner >= 0) claimedCells++;
    for (Nation n : state.nations.values()) {
      for (int sid : n.settlementIds) { Settlement s = state.settlements.get(sid); if (s != null) worldPop += s.populationCount; }
      worldTreasury += n.treasury;
      worldGdp += n.gdpAccum;
    }
    int atWarPairs = 0, alliancePairs = 0;
    for (var r : state.diplomacy.relations.values()) {
      if (Config.WAR.equals(r.status)) atWarPairs++;
      else if (Config.ALLIANCE.equals(r.status)) alliancePairs++;
    }
    double landCells = 0;
    for (byte t : state.grid.terrain) if (t != Config.WATER) landCells++;
    statRow("World population", String.valueOf(worldPop));
    statRow("World treasury", (int) Math.floor(worldTreasury) + "g (combined)");
    statRow("World GDP (window)", (int) Math.floor(worldGdp) + "g");
    statRow("Land claimed", landCells > 0 ? String.format("%.0f%%", claimedCells / landCells * 100) : "0%");
    statRow("Wars ongoing", String.valueOf(atWarPairs));
    statRow("Alliances", String.valueOf(alliancePairs));

    Label listHeader = sidePanel.addChild(new Label("BY TREASURY"));
    listHeader.setColor(MUTED); listHeader.setFontSize(fs(12));
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
    title.setFontSize(fs(17));
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
      Label dateLbl = row.addChild(new Label(com.worldbox.util.Calendar.dateString(e.tick)));
      dateLbl.setColor(MUTED);
      dateLbl.setFontSize(fs(12));
      dateLbl.setPreferredSize(new Vector3f(110 * uiScale, 18 * uiScale, 0));
      Label msg = row.addChild(new Label(e.message));
      msg.setColor(logCategoryColor(e.category));
      msg.setFontSize(fs(12));
    }
  }

  private void renderMarket(GameState state) {
    Label title = sidePanel.addChild(new Label("World Market"));
    title.setFontSize(fs(17));
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
      l.setPreferredSize(new Vector3f(110 * uiScale, 20 * uiScale, 0));
      Label v = row.addChild(new Label(String.format("%.2fg %s", price, price >= prev ? "↑" : "↓")));
      v.setColor(price >= prev ? GOOD : DANGER);
      v.setPreferredSize(new Vector3f(80 * uiScale, 20 * uiScale, 0));
      if (price > base * 1.8) {
        Label bubble = row.addChild(new Label(greed > 0.5 ? "BUBBLE" : "high"));
        bubble.setColor(greed > 0.5 ? DANGER : ACTIVE);
      }
    }
    sidePanel.addChild(new Label(" "));
    Container chartButtons = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
    Button viewWorldChart = chartButtons.addChild(new Button("World Total"));
    viewWorldChart.addClickCommands(src -> showGraph("world", -1));
    Button viewAllNations = chartButtons.addChild(new Button("By Nation"));
    viewAllNations.addClickCommands(src -> showGraph("allNations", -1));
  }

  private static final String[] GRAPH_METRICS_NATION = {"marketcap", "unemployment", "gdp", "currency", "inflation", "wealth", "stability", "sectors"};
  private static final String[] GRAPH_METRICS_WORLD = {"marketcap", "gdp", "stability", "sectors"};
  // "By Nation" only ever compares ONE metric across every living nation
  // at once - "sectors" is already a multi-series breakdown for a single
  // nation/world, so it doesn't have a sensible "by nation" overlay of its
  // own and is deliberately left out of this shorter list.
  private static final String[] GRAPH_METRICS_ALL_NATIONS = {"marketcap", "gdp", "stability"};
  // fixed per-sector display identity (label + line color), in the same
  // order as Config.SECTORS - shared by the summary rows, the legend and
  // the chart lines themselves so a sector always reads as the same color
  // everywhere it shows up.
  private static final String[] SECTOR_LABELS = {"Agriculture", "Extraction", "Manufacturing", "Luxury", "Commerce"};
  private static final ColorRGBA[] SECTOR_COLORS = {
      new ColorRGBA(0.42f, 0.78f, 0.35f, 1f),
      new ColorRGBA(0.72f, 0.52f, 0.28f, 1f),
      new ColorRGBA(0.35f, 0.58f, 0.88f, 1f),
      new ColorRGBA(0.82f, 0.62f, 0.18f, 1f),
      new ColorRGBA(0.35f, 0.78f, 0.78f, 1f),
  };
  // more than this many living nations would make the legend a wall of
  // text instead of something a player can actually read at a glance -
  // shown ranked by whoever's currently highest on the metric, with a
  // "+N more" note for the rest so the chart itself still plots every
  // living nation's line even when the legend is trimmed.
  private static final int LEGEND_MAX_ENTRIES = 14;

  private String metricTabLabel(String metric) {
    switch (metric) {
      case "unemployment": return "Jobs";
      case "gdp": return "GDP";
      case "currency": return "FX";
      case "inflation": return "Inflation";
      case "wealth": return "Wealth";
      case "stability": return "Stability";
      case "sectors": return "Sectors";
      default: return "Cap";
    }
  }

  private String metricPanelTitle(String metric) {
    switch (metric) {
      case "unemployment": return "Unemployment";
      case "gdp": return "GDP";
      case "currency": return "Exchange Rate";
      case "inflation": return "Inflation";
      case "wealth": return "Average Citizen Wealth";
      case "stability": return "Stability";
      case "sectors": return "Market Sectors";
      default: return "Market Cap";
    }
  }

  private String formatMetric(String metric, double v) {
    switch (metric) {
      case "unemployment": return String.format("%.1f%%", v * 100);
      case "currency": return String.format("%.3fx", v);
      case "inflation": return String.format("%+.1f%%", v * 100);
      case "wealth": return String.format("%.1f", v) + "g";
      case "stability": return String.format("%.0f%%", v);
      default: return (int) Math.floor(v) + "g";
    }
  }

  /** Every metric but market cap/GDP/stability needs a specific nation
   * (unemployment, exchange rate, inflation and average wealth aren't
   * tracked world-wide - there's no single meaningful "world inflation
   * rate" the way a nation's is). Stability IS tracked world-wide too -
   * see Government.update's population-weighted worldStabilityHistory. */
  private java.util.ArrayDeque<Double> metricHistory(GameState state, String metric, boolean isWorld, Nation n) {
    switch (metric) {
      case "unemployment": return isWorld ? null : n.unemploymentHistory;
      case "gdp": return isWorld ? state.worldGdpHistory : n.gdpHistory;
      case "currency": return isWorld ? null : n.currencyHistory;
      case "inflation": return isWorld ? null : negated(n.inflationHistory);
      case "wealth": return isWorld ? null : n.wealthHistory;
      case "stability": return isWorld ? state.worldStabilityHistory : n.stabilityHistory;
      default: return isWorld ? state.worldMarketCapHistory : n.marketCapHistory;
    }
  }

  /** The underlying inflationRate calculation is correct as designed (see
   * Government.updateInflationAndExchangeRate) - a healthy, non-printing
   * economy trends mildly deflationary, which is exactly what should
   * strengthen its currency. But shown to the player as "-2% inflation"
   * during ordinary healthy growth read as an obviously broken/flipped
   * number. This flips only the DISPLAYED sign (chart included, via
   * metricHistory above) so ordinary growth reads as a positive,
   * reassuring number and only real reckless-printing inflation reads
   * negative - the math driving exchangeRate itself is untouched. */
  private static java.util.ArrayDeque<Double> negated(java.util.ArrayDeque<Double> src) {
    java.util.ArrayDeque<Double> out = new java.util.ArrayDeque<>();
    for (double v : src) out.addLast(-v);
    return out;
  }

  private void renderGraph(GameState state) {
    boolean isWorld = "world".equals(graphView);
    boolean isAllNations = "allNations".equals(graphView);
    Nation n = (isWorld || isAllNations) ? null : state.nations.get(graphNationId);
    if (!isWorld && !isAllNations && n == null) { sidePanelMode = "nationsList"; refreshSidePanel(); return; }

    String titleText = isAllNations ? "All Nations" : isWorld ? "World Economy" : n.displayName();
    Label title = sidePanel.addChild(new Label(titleText + " - " + metricPanelTitle(graphMetric)));
    title.setFontSize(fs(17));
    Container navRow = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
    Button back = navRow.addChild(new Button("Back"));
    back.addClickCommands(src -> {
      if (isWorld || isAllNations) {
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
    String[] metricSet = isAllNations ? GRAPH_METRICS_ALL_NATIONS : isWorld ? GRAPH_METRICS_WORLD : GRAPH_METRICS_NATION;
    for (String metric : metricSet) {
      Button tab = tabs.addChild(new Button(metricTabLabel(metric)));
      if (metric.equals(graphMetric)) tab.setColor(ACTIVE);
      tab.addClickCommands(src -> { graphMetric = metric; refreshSidePanel(); });
    }

    boolean isSectors = "sectors".equals(graphMetric) && !isAllNations;
    int legendRows = 0;
    if (isAllNations) {
      // no single "latest/change/range" block here - there are many series
      // at once, each with its own value; the color-coded legend drawn
      // under the chart (see layoutAllNationsChart) is what identifies them.
      java.util.List<Nation> living = livingNationsSorted(state, graphMetric);
      statRow("Nations shown", Math.min(living.size(), LEGEND_MAX_ENTRIES) + " of " + living.size());
      legendRows = allNationsLegendRows(state);
    } else if (isSectors) {
      // same idea as the all-nations overlay above, but breaking down ONE
      // nation's (or the world's) own revenue by sector instead of
      // comparing nations against each other - each sector's own latest
      // reading, color-matched to its line in the chart below.
      for (int i = 0; i < Config.SECTORS.length; i++) {
        double v = latestSectorValue(state, isWorld, n, Config.SECTORS[i]);
        Container row = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        Label lbl = row.addChild(new Label(SECTOR_LABELS[i]));
        lbl.setColor(SECTOR_COLORS[i]);
        lbl.setPreferredSize(new Vector3f(160 * uiScale, 20 * uiScale, 0));
        row.addChild(new Label((int) Math.floor(v) + "g"));
      }
      legendRows = (int) Math.ceil(Config.SECTORS.length / 3.0);
    } else {
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
        changeLbl.setPreferredSize(new Vector3f(160 * uiScale, 20 * uiScale, 0));
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
    }

    Label chartSpacer = sidePanel.addChild(new Label(" "));
    chartSpacer.setPreferredSize(new Vector3f(SIDEPANEL_WIDTH - 20, CHART_HEIGHT + legendRows * LEGEND_ROW_HEIGHT + 16, 0));
  }

  /** Every currently-living nation, ranked highest-to-lowest by its most
   * recent sample of the given metric - used both to decide which nations
   * make the (space-limited) legend and in what order, and to draw the
   * chart lines themselves. */
  private java.util.List<Nation> livingNationsSorted(GameState state, String metric) {
    java.util.List<Nation> living = new java.util.ArrayList<>();
    for (Nation nation : state.nations.values()) if (nation.alive) living.add(nation);
    living.sort((a, b) -> Double.compare(latestValue(b, metric), latestValue(a, metric)));
    return living;
  }

  private double latestValue(Nation n, String metric) {
    java.util.ArrayDeque<Double> hist = metricHistory(null, metric, false, n);
    return hist == null || hist.isEmpty() ? 0 : hist.peekLast();
  }

  private double latestSectorValue(GameState state, boolean isWorld, Nation n, String sector) {
    java.util.ArrayDeque<Double> hist = isWorld ? state.worldSectorHistory.get(sector) : n.sectorHistory.get(sector);
    return hist == null || hist.isEmpty() ? 0 : hist.peekLast();
  }

  private int allNationsLegendRows(GameState state) {
    int shown = Math.min(livingNationsSorted(state, graphMetric).size(), LEGEND_MAX_ENTRIES);
    return shown == 0 ? 0 : (int) Math.ceil(shown / 3.0);
  }

  /** inflationRate is a smoothed per-sample-window figure (samples every
   * 20 ticks), which read as a near-meaningless "+0.1%/window" number to
   * a player. Averaging the last year's worth of windows and annualizing
   * that turns it into the plain "inflation over the last year" percentage
   * everyone actually expects from an "Inflation" readout. The sign is
   * flipped from the raw economic value (see metricHistory's "inflation"
   * case for why) so ordinary healthy growth reads as a reassuring
   * positive number, not a confusing negative one. */
  private double annualInflation(Nation n) {
    int windowsPerYear = com.worldbox.util.Calendar.MONTHS_PER_YEAR;
    java.util.ArrayDeque<Double> hist = n.inflationHistory;
    if (hist.isEmpty()) return -n.inflationRate * windowsPerYear;
    Double[] arr = hist.toArray(new Double[0]);
    int take = Math.min(windowsPerYear, arr.length);
    double sum = 0;
    for (int i = arr.length - take; i < arr.length; i++) sum += arr[i];
    return -(sum / take) * windowsPerYear;
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
    for (com.jme3.font.BitmapText t : chartLabels) t.removeFromParent();
    chartLabels.clear();

    GameState state = ctx.getState();
    boolean isWorld = "world".equals(graphView);
    boolean isAllNations = "allNations".equals(graphView);
    if (isAllNations) { layoutAllNationsChart(state); return; }
    Nation n = isWorld ? null : state.nations.get(graphNationId);
    if (!isWorld && n == null) return;
    if ("sectors".equals(graphMetric)) { layoutSectorsChart(state, isWorld, n); return; }
    java.util.ArrayDeque<Double> hist = metricHistory(state, graphMetric, isWorld, n);
    if (hist == null || hist.size() < 2) return;

    java.util.List<Double> values = new java.util.ArrayList<>(hist);
    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
    for (double v : values) { min = Math.min(min, v); max = Math.max(max, v); }
    double span = Math.max(1.0, max - min);

    float originX = sidePanel.getLocalTranslation().x + 16;
    // was a hardcoded guess (CHART_TOP_OFFSET) at how tall the panel's
    // text content above the chart would be - stale ever since more stat
    // rows were added to the nation panel, so the chart increasingly
    // drew too low, off the bottom of the (now also centered rather than
    // always anchored near the top) panel in fullscreen. The chart
    // spacer reserves exactly (CHART_HEIGHT+16) at the bottom of the
    // panel's real, measured height - whatever's left over is exactly
    // how much content actually precedes it, however much that's grown.
    float contentAboveChart = sidePanel.getPreferredSize().y - (CHART_HEIGHT + 16f);
    float baselineY = sidePanel.getLocalTranslation().y - contentAboveChart - CHART_HEIGHT;
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

  /** The "By Nation" overlay: every living nation's own history line, in
   * its own map color, sharing one y-axis so relative size reads
   * honestly, plus a ranked, color-swatched legend underneath (see
   * livingNationsSorted/LEGEND_MAX_ENTRIES) - the thing a plain per-
   * nation or world-total chart can't show: who's actually ahead. */
  private void layoutAllNationsChart(GameState state) {
    java.util.List<Nation> living = livingNationsSorted(state, graphMetric);
    java.util.Map<Integer, java.util.List<Double>> series = new java.util.LinkedHashMap<>();
    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
    int maxLen = 0;
    for (Nation nation : living) {
      java.util.ArrayDeque<Double> hist = metricHistory(state, graphMetric, false, nation);
      if (hist == null || hist.size() < 2) continue;
      java.util.List<Double> values = new java.util.ArrayList<>(hist);
      series.put(nation.id, values);
      maxLen = Math.max(maxLen, values.size());
      for (double v : values) { min = Math.min(min, v); max = Math.max(max, v); }
    }
    if (series.isEmpty() || maxLen < 2) return;
    double span = Math.max(1.0, max - min);

    float originX = sidePanel.getLocalTranslation().x + 16;
    int legendRows = allNationsLegendRows(state);
    float legendHeight = legendRows * LEGEND_ROW_HEIGHT;
    float contentAboveChart = sidePanel.getPreferredSize().y - (CHART_HEIGHT + legendHeight + 16f);
    float baselineY = sidePanel.getLocalTranslation().y - contentAboveChart - CHART_HEIGHT;
    float lineThickness = 2f;

    for (Nation nation : living) {
      java.util.List<Double> values = series.get(nation.id);
      if (values == null) continue;
      // a nation founded partway through the game has a shorter history
      // than an older one - align every series to the right (their most
      // recent samples share the same x) instead of stretching a short
      // history across the whole chart width, which would silently
      // distort its time axis relative to everyone else's.
      int offset = maxLen - values.size();
      float stepX = CHART_WIDTH / (maxLen - 1);
      ColorRGBA color = new ColorRGBA(((nation.color >> 16) & 0xFF) / 255f, ((nation.color >> 8) & 0xFF) / 255f, (nation.color & 0xFF) / 255f, 1f);
      for (int i = 1; i < values.size(); i++) {
        double v0 = values.get(i - 1), v1 = values.get(i);
        float x0 = originX + (offset + i - 1) * stepX, y0 = baselineY + (float) ((v0 - min) / span) * CHART_HEIGHT;
        float x1 = originX + (offset + i) * stepX, y1 = baselineY + (float) ((v1 - min) / span) * CHART_HEIGHT;
        float dx = x1 - x0, dy = y1 - y0;
        float len = Math.max(0.01f, (float) Math.sqrt(dx * dx + dy * dy));
        Quad quad = new Quad(len, lineThickness);
        Geometry segment = new Geometry("allNationsLine", quad);
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", color);
        segment.setMaterial(mat);
        segment.setQueueBucket(RenderQueue.Bucket.Gui);
        segment.setLocalTranslation(x0, y0 - lineThickness / 2f, 2);
        segment.rotate(0, 0, FastMath.atan2(dy, dx));
        chartNode.attachChild(segment);
        chartBars.add(segment);
      }
    }

    Quad baseline = new Quad(CHART_WIDTH, 2f);
    Geometry baselineBar = new Geometry("allNationsBaseline", baseline);
    Material baseMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
    baseMat.setColor("Color", MUTED);
    baselineBar.setMaterial(baseMat);
    baselineBar.setQueueBucket(RenderQueue.Bucket.Gui);
    baselineBar.setLocalTranslation(originX, baselineY - 2f, 2);
    chartNode.attachChild(baselineBar);
    chartBars.add(baselineBar);

    // legend: a color swatch + nation name per row, wrapped into 3
    // columns beneath the chart, in the same rank order as the lines
    // themselves so the top of the legend matches whoever's winning.
    int shown = Math.min(living.size(), LEGEND_MAX_ENTRIES);
    float colWidth = CHART_WIDTH / 3f;
    float swatchSize = 9f * uiScale;
    float legendTop = baselineY - 14f * uiScale;
    float fontSize = chartFont.getCharSet().getRenderedSize() * 0.62f * uiScale;
    for (int i = 0; i < shown; i++) {
      Nation nation = living.get(i);
      int col = i % 3, row = i / 3;
      float lx = originX + col * colWidth;
      float ly = legendTop - row * LEGEND_ROW_HEIGHT;
      ColorRGBA color = new ColorRGBA(((nation.color >> 16) & 0xFF) / 255f, ((nation.color >> 8) & 0xFF) / 255f, (nation.color & 0xFF) / 255f, 1f);
      Quad swatchQuad = new Quad(swatchSize, swatchSize);
      Geometry swatch = new Geometry("legendSwatch", swatchQuad);
      Material swatchMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
      swatchMat.setColor("Color", color);
      swatch.setMaterial(swatchMat);
      swatch.setQueueBucket(RenderQueue.Bucket.Gui);
      swatch.setLocalTranslation(lx, ly - swatchSize, 2);
      chartNode.attachChild(swatch);
      chartBars.add(swatch);

      // keep each legend entry inside its own column - an untruncated
      // long nation name would otherwise overlap the next column over
      String label = nation.name.length() > 11 ? nation.name.substring(0, 10) + "…" : nation.name;
      com.jme3.font.BitmapText text = new com.jme3.font.BitmapText(chartFont);
      text.setSize(fontSize);
      text.setColor(TEXT);
      text.setText(label);
      text.setQueueBucket(RenderQueue.Bucket.Gui);
      text.setLocalTranslation(lx + swatchSize + 4f * uiScale, ly, 2);
      chartNode.attachChild(text);
      chartLabels.add(text);
    }
    if (living.size() > shown) {
      com.jme3.font.BitmapText more = new com.jme3.font.BitmapText(chartFont);
      more.setSize(fontSize);
      more.setColor(MUTED);
      more.setText("+" + (living.size() - shown) + " more");
      more.setQueueBucket(RenderQueue.Bucket.Gui);
      int row = (int) Math.ceil(shown / 3.0);
      more.setLocalTranslation(originX, legendTop - row * LEGEND_ROW_HEIGHT, 2);
      chartNode.attachChild(more);
      chartLabels.add(more);
    }
  }

  /** The "Sectors" tab: one line per Config.SECTORS entry (fixed colors -
   * see SECTOR_COLORS), sharing one y-axis, for either a single nation's
   * own sectorHistory or the world-wide worldSectorHistory - same overlay
   * technique as layoutAllNationsChart, just breaking down ONE economy's
   * revenue by sector instead of comparing many nations on one metric. */
  private void layoutSectorsChart(GameState state, boolean isWorld, Nation n) {
    java.util.Map<String, java.util.List<Double>> series = new java.util.LinkedHashMap<>();
    double min = 0, max = -Double.MAX_VALUE; // sector revenue is never negative - anchor the floor at 0
    int maxLen = 0;
    for (String sector : Config.SECTORS) {
      java.util.ArrayDeque<Double> hist = isWorld ? state.worldSectorHistory.get(sector) : n.sectorHistory.get(sector);
      if (hist == null || hist.size() < 2) continue;
      java.util.List<Double> values = new java.util.ArrayList<>(hist);
      series.put(sector, values);
      maxLen = Math.max(maxLen, values.size());
      for (double v : values) max = Math.max(max, v);
    }
    if (series.isEmpty() || maxLen < 2) return;
    double span = Math.max(1.0, max - min);

    float originX = sidePanel.getLocalTranslation().x + 16;
    int legendRows = (int) Math.ceil(Config.SECTORS.length / 3.0);
    float legendHeight = legendRows * LEGEND_ROW_HEIGHT;
    float contentAboveChart = sidePanel.getPreferredSize().y - (CHART_HEIGHT + legendHeight + 16f);
    float baselineY = sidePanel.getLocalTranslation().y - contentAboveChart - CHART_HEIGHT;
    float lineThickness = 2f;

    for (int s = 0; s < Config.SECTORS.length; s++) {
      java.util.List<Double> values = series.get(Config.SECTORS[s]);
      if (values == null) continue;
      int offset = maxLen - values.size();
      float stepX = CHART_WIDTH / (maxLen - 1);
      ColorRGBA color = SECTOR_COLORS[s];
      for (int i = 1; i < values.size(); i++) {
        double v0 = values.get(i - 1), v1 = values.get(i);
        float x0 = originX + (offset + i - 1) * stepX, y0 = baselineY + (float) ((v0 - min) / span) * CHART_HEIGHT;
        float x1 = originX + (offset + i) * stepX, y1 = baselineY + (float) ((v1 - min) / span) * CHART_HEIGHT;
        float dx = x1 - x0, dy = y1 - y0;
        float len = Math.max(0.01f, (float) Math.sqrt(dx * dx + dy * dy));
        Quad quad = new Quad(len, lineThickness);
        Geometry segment = new Geometry("sectorLine", quad);
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", color);
        segment.setMaterial(mat);
        segment.setQueueBucket(RenderQueue.Bucket.Gui);
        segment.setLocalTranslation(x0, y0 - lineThickness / 2f, 2);
        segment.rotate(0, 0, FastMath.atan2(dy, dx));
        chartNode.attachChild(segment);
        chartBars.add(segment);
      }
    }

    Quad baseline = new Quad(CHART_WIDTH, 2f);
    Geometry baselineBar = new Geometry("sectorsBaseline", baseline);
    Material baseMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
    baseMat.setColor("Color", MUTED);
    baselineBar.setMaterial(baseMat);
    baselineBar.setQueueBucket(RenderQueue.Bucket.Gui);
    baselineBar.setLocalTranslation(originX, baselineY - 2f, 2);
    chartNode.attachChild(baselineBar);
    chartBars.add(baselineBar);

    float colWidth = CHART_WIDTH / 3f;
    float swatchSize = 9f * uiScale;
    float legendTop = baselineY - 14f * uiScale;
    float fontSize = chartFont.getCharSet().getRenderedSize() * 0.62f * uiScale;
    for (int s = 0; s < Config.SECTORS.length; s++) {
      int col = s % 3, row = s / 3;
      float lx = originX + col * colWidth;
      float ly = legendTop - row * LEGEND_ROW_HEIGHT;
      Quad swatchQuad = new Quad(swatchSize, swatchSize);
      Geometry swatch = new Geometry("sectorLegendSwatch", swatchQuad);
      Material swatchMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
      swatchMat.setColor("Color", SECTOR_COLORS[s]);
      swatch.setMaterial(swatchMat);
      swatch.setQueueBucket(RenderQueue.Bucket.Gui);
      swatch.setLocalTranslation(lx, ly - swatchSize, 2);
      chartNode.attachChild(swatch);
      chartBars.add(swatch);

      com.jme3.font.BitmapText text = new com.jme3.font.BitmapText(chartFont);
      text.setSize(fontSize);
      text.setColor(TEXT);
      text.setText(SECTOR_LABELS[s]);
      text.setQueueBucket(RenderQueue.Bucket.Gui);
      text.setLocalTranslation(lx + swatchSize + 4f * uiScale, ly, 2);
      chartNode.attachChild(text);
      chartLabels.add(text);
    }
  }
}
