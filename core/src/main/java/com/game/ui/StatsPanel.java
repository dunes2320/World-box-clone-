package com.game.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.game.render.UnitRenderer;
import com.game.sim.Species;
import com.game.sim.Units;

/** Live population, broken down by species in the same colours they render in. */
public final class StatsPanel extends Table {

    private final Label[] speciesCounts = new Label[Species.COUNT];
    private final Label total;
    private final int[] counts = new int[Species.COUNT];

    public StatsPanel(Skin skin) {
        setBackground(skin.getDrawable("panel"));
        pad(10f);
        defaults().left().pad(2f);

        add(new Label("POPULATION", skin, "accent")).colspan(2).left();
        row();

        for (byte species = 0; species < Species.COUNT; species++) {
            Label name = new Label(Species.name(species), skin);
            // Matching the render colour means the panel reads as a legend for
            // what is actually on screen, not just a table of numbers.
            Color tint = UnitRenderer.colorFor(species);
            name.setColor(tint);
            add(name).width(78f);

            speciesCounts[species] = new Label("0", skin);
            add(speciesCounts[species]).width(56f);
            row();
        }

        add(new Label("Total", skin, "dim")).width(78f);
        total = new Label("0", skin);
        add(total).width(56f);
    }

    public void refresh(Units units) {
        units.countBySpecies(counts);
        for (byte species = 0; species < Species.COUNT; species++) {
            speciesCounts[species].setText(String.valueOf(counts[species]));
        }
        total.setText(String.valueOf(units.getLiveCount()));
    }
}
