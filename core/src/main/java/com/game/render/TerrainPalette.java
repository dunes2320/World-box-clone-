package com.game.render;

import com.badlogic.gdx.graphics.Color;
import com.game.sim.TileType;

/**
 * Maps tile types to the flat vertex colours the terrain mesh is painted with.
 * There are no textures anywhere in this game - colour plus a hard-edged
 * normal is the whole look.
 */
public final class TerrainPalette {

    private TerrainPalette() {
    }

    /** Top-face colour per tile type, indexed by the {@link TileType} constants. */
    private static final Color[] TOP = {
        new Color(0.10f, 0.24f, 0.48f, 1f), // deep water
        new Color(0.20f, 0.46f, 0.72f, 1f), // shallow water
        new Color(0.85f, 0.78f, 0.55f, 1f), // sand
        new Color(0.35f, 0.62f, 0.28f, 1f), // grass
        new Color(0.18f, 0.42f, 0.20f, 1f), // forest
        new Color(0.44f, 0.50f, 0.30f, 1f), // hill
        new Color(0.46f, 0.44f, 0.42f, 1f), // mountain
        new Color(0.92f, 0.93f, 0.95f, 1f), // snow
    };

    /**
     * Side walls are the top colour darkened. Real directional lighting already
     * shades them by facing, but an extra multiplier on the vertical faces
     * makes the stepped silhouette read clearly even where the sun is behind
     * the camera.
     */
    private static final float SIDE_SHADE = 0.72f;

    public static Color top(byte tileType) {
        return TOP[tileType];
    }

    public static float topPacked(byte tileType) {
        return TOP[tileType].toFloatBits();
    }

    public static float sidePacked(byte tileType) {
        Color c = TOP[tileType];
        return Color.toFloatBits(c.r * SIDE_SHADE, c.g * SIDE_SHADE, c.b * SIDE_SHADE, 1f);
    }
}
