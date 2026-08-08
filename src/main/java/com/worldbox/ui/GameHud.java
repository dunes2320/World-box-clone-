package com.worldbox.ui;

import com.jme3.asset.AssetManager;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.DefaultRangedValueModel;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Slider;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;
import com.worldbox.config.Config;
import com.worldbox.sim.Diplomacy;
import com.worldbox.sim.GameState;
import com.worldbox.sim.GlobalMarket;
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
  private final float screenW, screenH;

  private final Container toolbar = new Container(new SpringGridLayout(Axis.Y, Axis.X));
  private final Container topBar = new Container(new SpringGridLayout(Axis.X, Axis.Y));
  private final Container sidePanel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
  private final Label statLabel;
  private final Map<String, Button> toolButtons = new LinkedHashMap<>();
  private Label brushLabel;
  private Slider brushSlider;

  private String sidePanelMode; // "settlement" | "nation" | "nationsList" | "market"
  private double lastStatRefresh, lastPanelRefresh;

  private static final float TOOLBAR_WIDTH = 190f;
  private static final float TOPBAR_HEIGHT = 42f;
  private static final float SIDEPANEL_WIDTH = 330f;

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

    toolbar.setLocalTranslation(0, height - 46, 1);
    toolbar.setBackground(new QuadBackgroundComponent(BG));
    guiNode.attachChild(toolbar);
    buildToolbar();

    sidePanel.setBackground(new QuadBackgroundComponent(BG));
    guiNode.attachChild(sidePanel);
    sidePanel.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
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
    else sidePanel.setCullHint(com.jme3.scene.Spatial.CullHint.Always);

    sidePanel.setLocalTranslation(screenW - 320, screenH - 46, 1);
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
    statRow("Nation", nation != null ? nation.name : "-");
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
    Label title = sidePanel.addChild(new Label(n.name));
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

    Button gift = sidePanel.addChild(new Button("Gift 200 gold"));
    gift.setColor(GOOD);
    gift.addClickCommands(src -> Diplomacy.divineGift(state, id, 200));

    Label relHeader = sidePanel.addChild(new Label("DIPLOMACY"));
    relHeader.setColor(MUTED); relHeader.setFontSize(12);
    for (Nation other : state.nations.values()) {
      if (other.id == id) continue;
      String status = state.diplomacy.getStatus(id, other.id);
      Container row = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
      Label name = row.addChild(new Label(other.name + " (" + status + ")"));
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
      Button row = sidePanel.addChild(new Button(n.name + "   " + pop + "p  " + (int) Math.floor(n.treasury) + "g"));
      row.setColor(new ColorRGBA(((n.color >> 16) & 0xFF) / 255f, ((n.color >> 8) & 0xFF) / 255f, (n.color & 0xFF) / 255f, 1f));
      final int nid = n.id;
      row.addClickCommands(src -> { ctx.setSelection(new GameState.Selection("nation", nid)); refreshSidePanel(); });
    }
  }

  private void renderMarket(GameState state) {
    Label title = sidePanel.addChild(new Label("World Market"));
    title.setFontSize(17);
    closeButton();
    GlobalMarket market = state.market;
    for (String key : GlobalMarket.keys()) {
      ArrayDeque<Double> hist = (ArrayDeque<Double>) market.history.get(key);
      double price = market.prices.get(key);
      double prev = hist.size() > 1 ? (Double) hist.toArray()[hist.size() - 2] : price;
      Container row = sidePanel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
      Label l = row.addChild(new Label(key));
      l.setPreferredSize(new Vector3f(120, 20, 0));
      Label v = row.addChild(new Label(String.format("%.2fg %s", price, price >= prev ? "↑" : "↓")));
      v.setColor(price >= prev ? GOOD : DANGER);
    }
  }
}
