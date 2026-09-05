package com.game.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.game.sim.Relations;
import com.game.sim.SimConfig;
import com.game.sim.Species;

/**
 * How the four species are getting on, one row per pair.
 *
 * <p>The point of this panel is that a war should never be a mystery. A pair
 * sitting at "tense" tells you where the next war is coming from; the running
 * death toll tells you why it is not over yet.
 */
public final class RelationsPanel extends Table {

    /** The six unordered pairs, fixed at construction so rows never reshuffle. */
    private final byte[] left;
    private final byte[] right;
    private final Label[] status;

    private static final Color WAR = new Color(0.96f, 0.36f, 0.32f, 1f);
    private static final Color TENSE = new Color(0.96f, 0.72f, 0.30f, 1f);
    private static final Color CALM = new Color(0.62f, 0.82f, 0.58f, 1f);
    private static final Color WARM = new Color(0.52f, 0.78f, 0.94f, 1f);

    public RelationsPanel(Skin skin) {
        int pairs = Species.COUNT * (Species.COUNT - 1) / 2;
        left = new byte[pairs];
        right = new byte[pairs];
        status = new Label[pairs];

        setBackground(skin.getDrawable("panel"));
        pad(10f);
        defaults().left().pad(2f);

        add(new Label("RELATIONS", skin, "accent")).colspan(2).left();
        row();

        int slot = 0;
        for (byte a = 0; a < Species.COUNT; a++) {
            for (byte b = (byte) (a + 1); b < Species.COUNT; b++) {
                left[slot] = a;
                right[slot] = b;

                add(new Label(Species.shortName(a) + " - " + Species.shortName(b), skin, "dim"))
                    .width(78f);
                status[slot] = new Label("-", skin);
                // Wide enough for the longest thing this cell ever says - a
                // wartime row with a four-figure death toll. A narrower cell
                // does not clip the label, it just lets it spill out past the
                // panel background and off the edge of the screen.
                add(status[slot]).width(150f);
                row();
                slot++;
            }
        }
    }

    public void refresh(Relations relations, long tick) {
        for (int i = 0; i < status.length; i++) {
            byte a = left[i];
            byte b = right[i];
            float value = relations.between(a, b);

            if (relations.isAtWar(a, b)) {
                // Seconds rather than ticks: the player is watching a clock, not
                // counting simulation steps.
                long seconds = (tick - relations.warStartTick(a, b)) / SimConfig.TICKS_PER_SECOND;
                status[i].setText("WAR " + seconds + "s  " + relations.warCasualties(a, b) + " dead");
                status[i].setColor(WAR);
            } else {
                status[i].setText(describe(value));
                status[i].setColor(colorFor(value));
            }
        }
    }

    /**
     * Words rather than a number. "Tense" is the band where friction is winning
     * and a war is coming, which is the one thing worth reading off this panel
     * at a glance.
     */
    private static String describe(float value) {
        if (value <= SimConfig.WAR_THRESHOLD + 0.2f) {
            return "hostile";
        }
        if (value <= 0f) {
            return "tense";
        }
        if (value < 0.5f) {
            return "wary";
        }
        return "friendly";
    }

    private static Color colorFor(float value) {
        if (value <= SimConfig.WAR_THRESHOLD + 0.2f) {
            return WAR;
        }
        if (value <= 0f) {
            return TENSE;
        }
        if (value < 0.5f) {
            return CALM;
        }
        return WARM;
    }
}
