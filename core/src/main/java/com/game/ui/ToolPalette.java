package com.game.ui;

import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.game.sim.SimConfig;

/** Bottom bar: one toggle per tool, plus the brush radius control. */
public final class ToolPalette extends Table {

    private final ToolState toolState;
    private final Slider radiusSlider;
    private final Label radiusLabel;
    private final ButtonGroup<TextButton> group = new ButtonGroup<>();

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

        for (ToolState.Tool tool : ToolState.Tool.values()) {
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
            add(button).width(96f).height(34f);
        }
        group.setMinCheckCount(1);

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

        sync();
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
