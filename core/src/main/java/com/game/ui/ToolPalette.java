package com.game.ui;

import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.graphics.Color;
import com.game.render.UnitRenderer;
import com.game.sim.SimConfig;
import com.game.sim.Species;

/** Bottom bar: one toggle per tool, plus the brush radius control. */
public final class ToolPalette extends Table {

    private final ToolState toolState;
    private final Slider radiusSlider;
    private final Label radiusLabel;
    private final ButtonGroup<TextButton> group = new ButtonGroup<>();
    private final ButtonGroup<TextButton> speciesGroup = new ButtonGroup<>();

    /** Marks the destructive row, so a meteor is never one absent-minded click away. */
    private static final Color DISASTER_TINT = new Color(0.96f, 0.52f, 0.36f, 1f);

    /**
     * Guards against the slider and the keyboard shortcuts fighting each other:
     * {@link #sync()} writes the slider from the tool state, which fires the
     * change listener, which would write straight back.
     */
    private boolean syncing;

    public ToolPalette(Skin skin, ToolState toolState) {
        this.toolState = toolState;

        // No background of its own: the HUD wraps this and the control hint in
        // one panel, so the hint is legible instead of floating unbacked over
        // bright terrain.
        pad(4f);
        defaults().pad(3f);

        // Same ordering trap as SpeedControls: with minCheckCount already at 1,
        // ButtonGroup.add checks the first button it is given and fires its
        // listener, overriding whatever tool the state actually holds.
        group.setMinCheckCount(0);
        group.setMaxCheckCount(1);

        // Disasters get their own row above the shaping tools. Twelve buttons in
        // one line does not fit at 1280 wide, and they are a different kind of
        // act anyway: the row below shapes a world, this one wrecks it.
        Table disasterRow = new Table();
        disasterRow.defaults().pad(3f);
        disasterRow.add(new Label("Disasters  ", skin, "dim"));
        for (ToolState.Tool tool : ToolState.Tool.values()) {
            if (!tool.isDisaster()) {
                continue;
            }
            TextButton button = makeToolButton(skin, toolState, tool);
            button.getLabel().setColor(DISASTER_TINT);
            disasterRow.add(button).width(84f).height(28f);
        }
        // Spans the whole lower row: the tool block, the brush controls and the
        // four species buttons.
        add(disasterRow).colspan(9).center();
        row();

        Table toolRow = new Table();
        toolRow.defaults().pad(3f);
        for (ToolState.Tool tool : ToolState.Tool.values()) {
            if (tool.isDisaster()) {
                continue;
            }
            toolRow.add(makeToolButton(skin, toolState, tool)).width(96f).height(34f);
        }
        group.setMinCheckCount(1);

        add(toolRow);

        add(new Label("  Brush", skin, "dim")).padLeft(14f);

        radiusSlider = new Slider(
            SimConfig.MIN_BRUSH_RADIUS, SimConfig.MAX_BRUSH_RADIUS, 1f, false, skin);
        radiusSlider.setValue(toolState.getRadius());
        radiusSlider.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, com.badlogic.gdx.scenes.scene2d.Actor actor) {
                if (!syncing) {
                    toolState.setRadius((int) radiusSlider.getValue());
                }
            }
        });
        add(radiusSlider).width(150f).height(28f);

        radiusLabel = new Label(String.valueOf(toolState.getRadius()), skin);
        add(radiusLabel).width(24f);

        // Species selector for the spawn tool. Always visible rather than
        // appearing only when Spawn is armed, so the palette does not resize
        // and shuffle every other button sideways as tools change.
        add(new Label("  Species", skin, "dim")).padLeft(14f);
        speciesGroup.setMinCheckCount(0);
        speciesGroup.setMaxCheckCount(1);
        for (byte species = 0; species < Species.COUNT; species++) {
            final byte id = species;
            TextButton button = new TextButton(Species.shortName(species), skin);
            Color tint = UnitRenderer.colorFor(species);
            button.getLabel().setColor(tint);
            button.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, com.badlogic.gdx.scenes.scene2d.Actor actor) {
                    if (button.isChecked()) {
                        toolState.setSpawnSpecies(id);
                        // Picking a species is a clear statement of intent, so
                        // arm the spawn tool rather than making it two clicks.
                        toolState.setTool(ToolState.Tool.SPAWN);
                    }
                }
            });
            speciesGroup.add(button);
            add(button).width(52f).height(30f);
        }
        speciesGroup.setMinCheckCount(1);

        sync();
    }

    /**
     * One tool toggle, wired to the shared state and added to the shared group
     * so that arming any tool disarms whichever was armed before - including
     * across the two rows.
     */
    private TextButton makeToolButton(Skin skin, ToolState toolState, ToolState.Tool tool) {
        TextButton button = new TextButton(tool.label(), skin);
        button.getLabel().setFontScale(1.0f);
        button.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, com.badlogic.gdx.scenes.scene2d.Actor actor) {
                if (button.isChecked()) {
                    toolState.setTool(tool);
                }
            }
        });
        group.add(button);
        return button;
    }

    /**
     * Pushes the tool state back into the widgets, so the bracket-key shortcuts
     * move the slider rather than silently diverging from it.
     */
    public void sync() {
        int radius = toolState.getRadius();
        if ((int) radiusSlider.getValue() != radius) {
            syncing = true;
            radiusSlider.setValue(radius);
            syncing = false;
        }
        radiusLabel.setText(String.valueOf(radius));

        String wantedSpecies = Species.shortName(toolState.getSpawnSpecies());
        TextButton checkedSpecies = speciesGroup.getChecked();
        if (checkedSpecies == null || !checkedSpecies.getText().toString().equals(wantedSpecies)) {
            for (TextButton button : speciesGroup.getButtons()) {
                if (button.getText().toString().equals(wantedSpecies)) {
                    button.setChecked(true);
                    break;
                }
            }
        }

        // Keep the checked button matching the state for the same reason.
        ToolState.Tool current = toolState.getTool();
        TextButton checked = group.getChecked();
        if (checked == null || !checked.getText().toString().equals(current.label())) {
            for (TextButton button : group.getButtons()) {
                if (button.getText().toString().equals(current.label())) {
                    button.setChecked(true);
                    break;
                }
            }
        }
    }
}
