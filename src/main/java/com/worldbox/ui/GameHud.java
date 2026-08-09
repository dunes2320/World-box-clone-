package com.worldbox.ui;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
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
import com.simsilica.lemur.component.QuadBackgroundComponent;
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
import com.worldbox.tools.GodTools;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

public class GameHud {
  private static final ColorRGBA BG = new ColorRGBA(0.11f, 0.13f, 0.17f, 0.92f);
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
  private Label brushLabel;
  private Slider brushSlider;

  private String sidePanelMode; // "settlement" | "nation" | "nationsList" | "market" | "graph"
  private double lastStatRefresh, lastPanelRefresh;

  // Economy graph: raw jME quads, positioned by hand since Lemur's grid
  // layout has no clean way to anchor bars of varying height to a shared
  // baseline.
  private final Node chartNode = new Node("economyChart");
  private final java.util.List<Geometry> chartBars = new java.util.ArrayList<>();
  private String graphView; // null | "nation" | "world"
  private int graphNationId = -1;

  private static final float TOOLBAR_WIDTH = 190f;
  private static final float TOPBAR_HEIGHT = 42f;
  private static final float SIDEPANEL_WIDTH = 330f;
  private static final float CHART_WIDTH = 290f;
  private static final float CHART_HEIGHT = 150f;
  private static final float CHART_TOP_OFFSET = 190f; // px below sidePanel's top edge

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
    topBar.setBackground(new QuadBackgroundComponent(BG));
    guiNode.attachChild(topBar);

    Label title = topBar.addChild(new Label("WORLD BOX 3D"));
    title.setFontSize(20);
    title.setColor(TEXT);
    topBar.addChild(spacer(30));
    statLabel = topBar.addChild(new Label("Pop: 0   Nations: 0   Tick: 0"));
    statLabel.setColor(MUTED);
    topBar.addChild(spacer(30));

    for (int speed : new int[]{0, 1, 2, 4}) {
      Button b = topBar.addChild(new Button(speedLabel(speed)));
      b.addClickCommands(src -> ctx.setGameSpeed(speed));
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

    toolbar.setLocalTranslation(0, height - 46, 1);
    toolbar.setBackground(new QuadBackgroundComponent(BG));
    guiNode.attachChild(toolbar);
    buildToolbar();

    sidePanel.setBackground(new QuadBackgroundComponent(BG));
    guiNode.attachChild(sidePanel);
    sidePanel.setCullHint(Spatial.CullHint.Always);

    chartNode.setQueueBucket(RenderQueue.Bucket.Gui);
    chartNode.setCullHint(Spatial.CullHint.Always);
    guiNode.attachChild(chartNode);
  }

  private static String speedLabel(int s) {
    switch (s) { case 0: return "Pause"; case 1: return "1x"; case 2: return "2x"; default: return "4x"; }
  }

  private Label spacer(float width) {
    Label l = new Label(" ");
    l.setPreferredSize(new Vector3f(width, 1, 0));
    return l;
  }

  private void buildToolbar() {
    String currentGroup = null;
    for (GodTools.ToolDef tool : GodTools.TOOLS) {
      if (!tool.group.equals(currentGroup)) {
        currentGroup = tool.group;
        Label header = toolbar.addChild(new Label(currentGroup.toUpperCase()));
        header.setColor(MUTED);
        header.setFontSize(12);
      }
      Button b = toolbar.addChild(new Button(tool.name));
      b.setTextHAlignment(com.simsilica.lemur.HAlignment.Left);
      b.addClickCommands(src -> {
        ctx.setTool(tool.id);
        refreshToolButtons();
      });
      toolButtons.put(tool.id, b);
    }

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

    refreshToolButtons();
  }

  private void refreshToolButtons() {
    String active = ctx.getTool();
    for (Map.Entry<String, Button> e : toolButtons.entrySet()) {
      e.getValue().setColor(e.getKey().equals(active) ? ACTIVE : TEXT);
    }
  }

  /** Test-only hook (used by GameApp's headless verification script). */
  public void debugShowGraph(String view, int nationId) {
    showGraph(view, nationId);
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

    if (now - lastStatRefresh > 0.28) {
      lastStatRefresh = now;
      GameState state = ctx.getState();
      statLabel.setText("Pop: " + state.humans.size() + "   Nations: " + state.nations.size() + "   Tick: " + state.tick);
    }
    if (now - lastPanelRefresh > 1.0) {
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
    else if ("nationsList".equals(mode)) renderNationsList(state);
    else if ("market".equals(mode)) renderMarket(state);
    else if ("graph".equals(mode)) renderGraph(state);
    else sidePanel.setCullHint(com.jme3.scene.Spatial.CullHint.Always);

    sidePanel.setLocalTranslation(screenW - 320, screenH - 46, 1);

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

  private void renderSettlement(GameState state, int id) {
    Settlement s = state.settlements.get(id);
    if (s == null) { sidePanelMode = null; ctx.setSelection(null); return; }
    Label title = sidePanel.addChild(new Label(s.name));
    title.setFontSize(17);
    closeButton();
    Nation nation = state.nations.get(s.nationId);
    statRow("Nation", nation != null ? nation.displayName() : "-");
    statRow("Population", String.valueOf(s.populationCount));
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

  private void renderNation(GameState state, int id) {
    Nation n = state.nations.get(id);
    if (n == null) { sidePanelMode = "nationsList"; ctx.setSelection(null); return; }
    Label title = sidePanel.addChild(new Label(n.displayName()));
    title.setFontSize(17);
    title.setColor(new ColorRGBA(((n.color >> 16) & 0xFF) / 255f, ((n.color >> 8) & 0xFF) / 255f, (n.color & 0xFF) / 255f, 1f));
    closeButton();

    int pop = 0;
    double military = 0;
    for (int sid : n.settlementIds) { Settlement s = state.settlements.get(sid); if (s != null) pop += s.populationCount; }
    for (var a : state.armies.values()) if (a.nationId == id) military += a.strength;

    statRow("Treasury", (int) Math.floor(n.treasury) + "g");
    statRow("Tax rate", (int) Math.round(n.taxRate * 100) + "%");
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
    statRow("Ideology", n.ideology);
    Button toggleIdeology = sidePanel.addChild(new Button(
        n.ideology.equals("capitalism") ? "Switch to Communism" : "Switch to Capitalism"));
    toggleIdeology.addClickCommands(src -> {
      n.ideology = n.ideology.equals("capitalism") ? "communism" : "capitalism";
      refreshSidePanel();
    });

    statRow("Bank reserves", (int) Math.floor(n.bank.reserves) + "g");
    statRow("Bank loans", (int) Math.floor(n.bank.loans) + "g");
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
    statRow("Business capital", (int) Math.floor(bizCapital) + "g");

    Button viewGraph = sidePanel.addChild(new Button("View Stock Chart"));
    viewGraph.addClickCommands(src -> showGraph("nation", id));

    Label relHeader = sidePanel.addChild(new Label("DIPLOMACY"));
    relHeader.setColor(MUTED); relHeader.setFontSize(12);
    for (Nation other : state.nations.values()) {
      if (other.id == id) continue;
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
    java.util.List<Nation> sorted = new java.util.ArrayList<>(state.nations.values());
    sorted.sort((a, b) -> Double.compare(b.treasury, a.treasury));
    for (Nation n : sorted) {
      int pop = 0;
      for (int sid : n.settlementIds) { Settlement s = state.settlements.get(sid); if (s != null) pop += s.populationCount; }
      Button row = sidePanel.addChild(new Button(n.displayName() + "   " + pop + "p  " + (int) Math.floor(n.treasury) + "g"));
      row.setColor(new ColorRGBA(((n.color >> 16) & 0xFF) / 255f, ((n.color >> 8) & 0xFF) / 255f, (n.color & 0xFF) / 255f, 1f));
      final int nid = n.id;
      row.addClickCommands(src -> { ctx.setSelection(new GameState.Selection("nation", nid)); refreshSidePanel(); });
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

  private void renderGraph(GameState state) {
    boolean isWorld = "world".equals(graphView);
    Nation n = isWorld ? null : state.nations.get(graphNationId);
    if (!isWorld && n == null) { sidePanelMode = "nationsList"; refreshSidePanel(); return; }

    Label title = sidePanel.addChild(new Label(isWorld ? "World Market Index" : n.displayName() + " - Market Cap"));
    title.setFontSize(17);
    Button back = sidePanel.addChild(new Button("Back"));
    back.addClickCommands(src -> {
      if (isWorld) {
        sidePanelMode = "market";
      } else {
        ctx.setSelection(new GameState.Selection("nation", graphNationId));
      }
      graphView = null;
      refreshSidePanel();
    });

    java.util.ArrayDeque<Double> hist = isWorld ? state.worldMarketCapHistory : n.marketCapHistory;
    if (hist.size() < 2) {
      Label l = sidePanel.addChild(new Label("Collecting data..."));
      l.setColor(MUTED);
    } else {
      Double[] arr = hist.toArray(new Double[0]);
      double latest = arr[arr.length - 1];
      double prev = arr[arr.length - 2];
      double change = latest - prev;
      double changePct = prev > 0.01 ? (change / prev) * 100 : 0;
      double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
      for (double v : hist) { min = Math.min(min, v); max = Math.max(max, v); }
      statRow("Market cap", (int) Math.floor(latest) + "g");
      Container changeRow = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
      Label changeLbl = changeRow.addChild(new Label("Change"));
      changeLbl.setColor(MUTED);
      changeLbl.setPreferredSize(new Vector3f(160, 20, 0));
      Label changeVal = changeRow.addChild(new Label(String.format("%s%.1f%%  (%s%dg)",
          change >= 0 ? "+" : "", changePct, change >= 0 ? "+" : "", (int) change)));
      changeVal.setColor(change >= 0 ? GOOD : DANGER);
      statRow("Range", (int) Math.floor(min) + "g - " + (int) Math.floor(max) + "g");
    }
    double treasury = isWorld ? sumLivingTreasury(state) : n.treasury;
    statRow("Treasury", (int) Math.floor(treasury) + "g");

    Label chartSpacer = sidePanel.addChild(new Label(" "));
    chartSpacer.setPreferredSize(new Vector3f(SIDEPANEL_WIDTH - 20, CHART_HEIGHT + 16, 0));
  }

  private double sumLivingTreasury(GameState state) {
    double total = 0;
    for (Nation n : state.nations.values()) if (n.alive) total += n.treasury;
    return total;
  }

  /** Rebuilds the raw jME bar-chart geometry for the currently selected
   * graph, positioned by hand beneath the panel's text content. Bars are
   * colored green/red against the previous sample, like an up/down day on
   * a stock chart, rather than by absolute magnitude. */
  private void layoutChart() {
    for (Geometry g : chartBars) g.removeFromParent();
    chartBars.clear();

    GameState state = ctx.getState();
    boolean isWorld = "world".equals(graphView);
    Nation n = isWorld ? null : state.nations.get(graphNationId);
    if (!isWorld && n == null) return;
    java.util.ArrayDeque<Double> hist = isWorld ? state.worldMarketCapHistory : n.marketCapHistory;
    if (hist.isEmpty()) return;

    java.util.List<Double> values = new java.util.ArrayList<>(hist);
    if (values.size() > 60) values = values.subList(values.size() - 60, values.size());
    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
    for (double v : values) { min = Math.min(min, v); max = Math.max(max, v); }
    double span = Math.max(1.0, max - min);

    float originX = sidePanel.getLocalTranslation().x + 16;
    float baselineY = sidePanel.getLocalTranslation().y - CHART_TOP_OFFSET - CHART_HEIGHT;
    float barW = CHART_WIDTH / values.size();

    for (int i = 0; i < values.size(); i++) {
      double v = values.get(i);
      double prev = i > 0 ? values.get(i - 1) : v;
      float t = (float) ((v - min) / span);
      float h = Math.max(2f, t * CHART_HEIGHT);
      Quad quad = new Quad(Math.max(1f, barW - 1.5f), h);
      Geometry bar = new Geometry("stockBar" + i, quad);
      Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
      mat.setColor("Color", v >= prev ? GOOD : DANGER);
      bar.setMaterial(mat);
      bar.setQueueBucket(RenderQueue.Bucket.Gui);
      bar.setLocalTranslation(originX + i * barW, baselineY, 2);
      chartNode.attachChild(bar);
      chartBars.add(bar);
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
