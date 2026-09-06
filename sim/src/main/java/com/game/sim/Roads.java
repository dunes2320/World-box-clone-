package com.game.sim;

/**
 * The road network - one bit per tile, on if the tile carries a road.
 *
 * <p>Kept as a byte array rather than a bitset because per-tile access is on
 * the hot path (pathfinding, rendering, movement cost) and one byte per tile
 * on a 512x512 map is 256 KB - the same order as {@code World.tileType}. A
 * bitset would save 24 bytes per chunk of eight tiles and cost a shift and a
 * mask at every read; that trade never pays.
 *
 * <p>Roads sit on top of the terrain: they do not change walkability, they
 * do not move with terraforming, and they follow the tile grid. Building a
 * road on a tile that later gets drowned by a flood or dug into a crater
 * leaves the road bit intact; it simply becomes an underwater or inaccessible
 * stretch until the ground rises again. Callers that care about road
 * validity should test walkability themselves.
 */
public final class Roads {

    private final byte[] road;
    private int count;

    public Roads(int tileCount) {
        if (tileCount <= 0) {
            throw new IllegalArgumentException("tileCount must be positive, got " + tileCount);
        }
        this.road = new byte[tileCount];
    }

    public boolean isRoad(int tileIndex) {
        return road[tileIndex] != 0;
    }

    /** Places a road, no-op if one is already there. Returns true if newly set. */
    public boolean set(int tileIndex) {
        if (road[tileIndex] == 0) {
            road[tileIndex] = 1;
            count++;
            return true;
        }
        return false;
    }

    /** Removes a road, no-op if none was there. Returns true if newly cleared. */
    public boolean clear(int tileIndex) {
        if (road[tileIndex] != 0) {
            road[tileIndex] = 0;
            count--;
            return true;
        }
        return false;
    }

    /** How many tiles carry a road right now. */
    public int getCount() {
        return count;
    }

    /** For the renderer: raw access to the road bit array. */
    public byte[] getRoadBits() {
        return road;
    }
}
