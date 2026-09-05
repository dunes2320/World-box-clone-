package com.game.ui;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.game.sim.SimClock;
import com.game.sim.Simulation;
import com.game.sim.TileType;
import com.game.sim.World;
import com.game.sim.WorldStats;

/**
 * The whole 2D overlay: tool palette along the bottom, speed controls and a
 * world readout at the top, tile inspector down the right.
 *
 * <p>Owns the {@link Stage}, which must be first in the input chain so a click
 * on a button never also carves a hole in the terrain underneath it.
 */
public final class Hud implements Disposable {

    private final Stage stage;
    private final Skin skin;
    private final ToolPalette palette;
    private final SpeedControls speedControls;
    private final InspectorPanel inspector;
    private final StatsPanel stats;
    private final RelationsPanel relations;
    private final Label worldLabel;
    private final Label hintLabel;

    private final int[] tileCounts = new int[TileType.COUNT];

    public Hud(ToolState toolState, SimClock clock) {
        stage = new Stage(new ScreenViewport());
        skin = UiSkin.create();

        palette = new ToolPalette(skin, toolState);
        speedControls = new SpeedControls(skin, clock);
        inspector = new InspectorPanel(skin);
        stats = new StatsPanel(skin);
        relations = new RelationsPanel(skin);

        worldLabel = new Label("", skin);
        hintLabel = new Label(
            "LMB tool  |  MMB or Alt+LMB rotate  |  RMB / WASD pan  |  wheel zoom  |  [ ] brush  "
                + "|  space pause  |  1-6 tools  |  M L F Q V P disasters",
            skin, "dim");

        Table topLeft = new Table();
        topLeft.setBackground(skin.getDrawable("panel"));
        topLeft.pad(8f);
        topLeft.add(worldLabel).left();

        Table root = new Table();
        root.setFillParent(true);
        root.top();

        Table topRow = new Table();
        topRow.add(speedControls).left();
        topRow.add(topLeft).left().padLeft(10f);
        topRow.add().expandX();
        root.add(topRow).expandX().fillX().pad(10f).top().left();
        root.row();

        // Middle band: inspector pinned right, everything else left clear so
        // the world stays visible.
        Table right = new Table();
        right.add(stats).right().top();
        right.row();
        right.add(relations).right().top().padTop(10f);
        right.row();
        right.add(inspector).right().top().padTop(10f);

        Table middle = new Table();
        middle.add().expandX().fillX();
        middle.add(right).right().top();
        root.add(middle).expand().fill().pad(10f);
        root.row();

        Table bottom = new Table();
        bottom.setBackground(skin.getDrawable("panel"));
        bottom.pad(8f);
        bottom.add(hintLabel).center();
        bottom.row();
        bottom.add(palette).center().padTop(4f);
        root.add(bottom).bottom().pad(10f);

        stage.addActor(root);
    }

    public Stage getStage() {
        return stage;
    }

    public InspectorPanel getInspector() {
        return inspector;
    }

    /** True when the pointer is over a widget, so world input should stand down. */
    public boolean isPointerOverUi(float screenX, float screenY) {
        return stage.hit(screenX, stage.getViewport().getScreenHeight() - screenY, true) != null;
    }

    public void update(Simulation simulation, SimClock clock, float delta) {
        palette.sync();
        speedControls.sync();

        World world = simulation.getWorld();
        inspector.refresh(world, simulation.getUnits(), simulation.getVillages());
        stats.refresh(simulation.getUnits());
        relations.refresh(simulation.getRelations(), simulation.getTickCount());

        WorldStats.countByType(world, tileCounts);
        var disasters = simulation.getDisasters();
        worldLabel.setText(String.format(
            "seed %d   tick %d   land %.0f%%   forest %d   villages %d   war dead %d%n"
                + "burning %d   sick %d   lost to fire %d   plague %d   disasters %d",
            simulation.getSeed(),
            simulation.getTickCount(),
            WorldStats.landFraction(world) * 100f,
            tileCounts[TileType.FOREST],
            simulation.getVillages().getLiveCount(),
            simulation.getWarCasualties(),
            disasters.getBurningTiles(),
            disasters.getInfectedUnits(),
            disasters.getFireDeaths(),
            disasters.getPlagueDeaths(),
            simulation.getDisasterCasualties()));

        stage.act(delta);
    }

    public void render() {
        stage.draw();
    }

    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    @Override
    public void dispose() {
        stage.dispose();
        skin.dispose();
    }

    /** Exposed so callers can size text consistently with the rest of the HUD. */
    public BitmapFont getFont() {
        return skin.getFont("default-font");
    }
}
