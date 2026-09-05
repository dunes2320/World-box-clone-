package com.game.ui;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.game.sim.SimClock;

/** Pause / 1x / 2x / 5x, driving the simulation clock directly. */
public final class SpeedControls extends Table {

    private final SimClock clock;
    private final ButtonGroup<TextButton> group = new ButtonGroup<>();

    public SpeedControls(Skin skin, SimClock clock) {
        this.clock = clock;

        setBackground(skin.getDrawable("panel"));
        pad(6f);
        defaults().pad(2f);

        // Constraints go on after the buttons are added, not before. With
        // minCheckCount already at 1, ButtonGroup.add auto-checks the very
        // first button it receives - which here is Pause, silently starting
        // the game with the simulation stopped.
        group.setMinCheckCount(0);
        group.setMaxCheckCount(1);

        for (int speed : SimClock.SPEEDS) {
            TextButton button = new TextButton(labelFor(speed), skin);
            button.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    if (button.isChecked()) {
                        clock.setSpeed(speed);
                    }
                }
            });
            group.add(button);
            add(button).width(58f).height(30f);
        }

        group.setMinCheckCount(1);
        sync();
    }

    private static String labelFor(int speed) {
        return speed == 0 ? "Pause" : speed + "x";
    }

    /** Reflects speed changes made from the keyboard back into the buttons. */
    public void sync() {
        String wanted = labelFor(clock.getSpeed());
        TextButton checked = group.getChecked();
        if (checked != null && checked.getText().toString().equals(wanted)) {
            return;
        }
        for (TextButton button : group.getButtons()) {
            if (button.getText().toString().equals(wanted)) {
                button.setChecked(true);
                return;
            }
        }
    }
}
