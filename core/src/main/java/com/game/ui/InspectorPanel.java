package com.game.ui;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.game.sim.TileType;
import com.game.sim.Units;
import com.game.sim.World;

/**
 * Details of the tile the player last clicked. Hidden until something is
 * actually selected, so an untouched screen stays clear.
 */
public final class InspectorPanel extends Table {

    private final Label coords;
    private final Label type;
    private final Label height;
    private final Label owner;
    private final Label units;

    private boolean hasSelection;
    private int selectedX = -1;
    private int selectedZ = -1;

    public InspectorPanel(Skin skin) {
        setBackground(skin.getDrawable("panel"));
        pad(10f);
        defaults().left().pad(2f);

        add(new Label("TILE", skin, "accent")).colspan(2).left();
        row();

        coords = addRow(skin, "Position");
        type = addRow(skin, "Terrain");
        height = addRow(skin, "Elevation");
        owner = addRow(skin, "Owner");
        units = addRow(skin, "Units");

        setVisible(false);
    }

    private Label addRow(Skin skin, String caption) {
        add(new Label(caption, skin, "dim")).width(78f);
        Label value = new Label("-", skin);
        add(value).width(112f);
        row();
        return value;
    }

    public boolean hasSelection() {
        return hasSelection;
    }

    public int getSelectedX() {
        return selectedX;
    }

    public int getSelectedZ() {
        return selectedZ;
    }

    public void select(int x, int z) {
        hasSelection = true;
        selectedX = x;
        selectedZ = z;
        setVisible(true);
    }

    public void clear() {
        hasSelection = false;
        selectedX = -1;
        selectedZ = -1;
        setVisible(false);
    }

    /**
     * Refreshes the displayed values from the world every frame, so a tile the
     * player is actively terraforming updates live rather than showing a
     * snapshot from whenever it was clicked.
     */
    public void refresh(World world, Units units) {
        if (!hasSelection || !world.inBounds(selectedX, selectedZ)) {
            return;
        }
        int index = world.index(selectedX, selectedZ);

        coords.setText(selectedX + ", " + selectedZ);
        type.setText(TileType.name(world.tileType[index]));
        height.setText(String.format("%.2f", world.height[index]));

        short village = world.ownerVillage[index];
        owner.setText(village == World.NO_OWNER ? "Unclaimed" : "Village " + village);

        this.units.setText(String.valueOf(countUnitsOnTile(units)));
    }

    /**
     * Units standing on the selected tile. A linear scan of the pool rather
     * than a spatial lookup: it runs once a frame for a single tile, and the
     * density grid's cells are far too coarse to answer a per-tile question.
     */
    private int countUnitsOnTile(Units units) {
        int count = 0;
        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (units.alive[i]
                && (int) Math.floor(units.x[i]) == selectedX
                && (int) Math.floor(units.z[i]) == selectedZ) {
                count++;
            }
        }
        return count;
    }
}
