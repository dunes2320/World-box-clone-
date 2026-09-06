package com.game.ui;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.game.sim.Features;
import com.game.sim.TileType;
import com.game.sim.Species;
import com.game.sim.Units;
import com.game.sim.Villages;
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

    // The village section is a second block, hidden until the selected tile
    // is actually owned by a live village. Keeps the panel compact for
    // unclaimed ground and only pays screen space where there is something
    // to say.
    private final Label villageHeader;
    private final Label pop;
    private final Label food;
    private final Label wood;
    private final Label stone;
    private final Label gold;
    private final Label jobs;

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

        villageHeader = new Label("VILLAGE", skin, "accent");
        add(villageHeader).colspan(2).left().padTop(6f);
        row();
        pop = addRow(skin, "Residents");
        food = addRow(skin, "Food");
        wood = addRow(skin, "Wood");
        stone = addRow(skin, "Stone");
        gold = addRow(skin, "Gold");
        jobs = addRow(skin, "Jobs");

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
    public void refresh(World world, Units units, Villages villages, Features features) {
        if (!hasSelection || !world.inBounds(selectedX, selectedZ)) {
            return;
        }
        int index = world.index(selectedX, selectedZ);

        coords.setText(selectedX + ", " + selectedZ);
        type.setText(TileType.name(world.tileType[index]));
        height.setText(String.format("%.2f", world.height[index]));

        short village = world.ownerVillage[index];
        boolean showVillage = village != World.NO_OWNER && villages.isAlive(village);
        if (!showVillage) {
            owner.setText("Unclaimed");
        } else {
            // Name it by who lives there rather than by index - "Orcs #3" says
            // something about the world, "Village 3" says nothing.
            owner.setText(Species.name(villages.species[village]) + " #" + village);
        }

        this.units.setText(String.valueOf(countUnitsOnTile(units)));

        setVillageVisible(showVillage);
        if (showVillage) {
            pop.setText(String.valueOf(villages.population[village]));
            food.setText(String.valueOf(villages.food[village]));
            wood.setText(String.valueOf(villages.wood[village]));
            stone.setText(String.valueOf(villages.stone[village]));
            gold.setText(String.valueOf(villages.gold[village]));
            jobs.setText(jobsSummary(features, village));
        }
    }

    private void setVillageVisible(boolean visible) {
        villageHeader.setVisible(visible);
        pop.setVisible(visible);
        food.setVisible(visible);
        wood.setVisible(visible);
        stone.setVisible(visible);
        gold.setVisible(visible);
        jobs.setVisible(visible);
    }

    /**
     * Compact one-line breakdown of what this village has built. Farms,
     * lumber, mines, markets, docks - the buildings that turn population
     * into resources, so a glance says whether it is a farming hamlet or a
     * trading town.
     */
    private static String jobsSummary(Features features, int v) {
        int farms = 0, lumber = 0, mines = 0, markets = 0, docks = 0;
        int end = features.getHighWater();
        for (int i = 0; i < end; i++) {
            if (!features.isAlive(i) || features.owner[i] != (short) v) {
                continue;
            }
            switch (features.kind[i]) {
                case Features.KIND_FARM: farms++; break;
                case Features.KIND_LUMBER_CAMP: lumber++; break;
                case Features.KIND_MINE: mines++; break;
                case Features.KIND_MARKET: markets++; break;
                case Features.KIND_DOCK: docks++; break;
                default: break;
            }
        }
        StringBuilder sb = new StringBuilder();
        appendIf(sb, "F", farms);
        appendIf(sb, "L", lumber);
        appendIf(sb, "M", mines);
        appendIf(sb, "K", markets);
        appendIf(sb, "D", docks);
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static void appendIf(StringBuilder sb, String label, int count) {
        if (count > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(label).append(count);
        }
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
