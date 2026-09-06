package com.game.render;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.graphics.Color;
import com.game.sim.SimConfig;
import com.game.sim.TileType;
import com.game.sim.Villages;
import com.game.sim.World;

/**
 * Builds and owns the {@link Mesh} for one chunk of terrain.
 *
 * <p>Tiles are drawn stepped rather than smoothly interpolated: each tile is a
 * flat quad at its own height, plus vertical wall quads dropping to any
 * neighbour that sits lower. That gives the blocky silhouette, and because no
 * vertex is shared between faces, every face can carry a single flat colour and
 * a single hard normal - which is what "flat shading, vertex colours, no
 * textures" actually requires.
 *
 * <p>The staging buffers used during {@link #rebuild} are shared statics
 * rather than per-instance arrays. A 32x32 chunk's worst-case buffer is 573KB
 * of floats; at 256 chunks (a 512-tile world) that would be 150MB of CPU RAM
 * doing nothing between rebuilds. Since {@code TerrainRenderer} rebuilds one
 * chunk at a time on the render thread, one shared buffer is sufficient.
 */
public final class ChunkMesh implements Disposable {

    /** Tiles are one world unit across, so tile (x,z) spans [x, x+1] on each axis. */
    private static final float TILE_SIZE = 1.0f;

    /**
     * Worst case per tile: one top quad plus four wall quads. Walls can be
     * taller than one tile but are still a single quad each, so this bound
     * holds regardless of terrain relief.
     */
    private static final int MAX_QUADS_PER_TILE = 5;
    private static final int TILES_PER_CHUNK = SimConfig.CHUNK_SIZE * SimConfig.CHUNK_SIZE;
    private static final int MAX_QUADS = TILES_PER_CHUNK * MAX_QUADS_PER_TILE;
    private static final int MAX_VERTICES = MAX_QUADS * 4;
    private static final int MAX_INDICES = MAX_QUADS * 6;

    /** position(3) + normal(3) + packed colour(1). */
    private static final int FLOATS_PER_VERTEX = 7;

    // Shared staging arrays - see class javadoc. Not thread safe; every caller
    // must be on the render thread which is where TerrainRenderer runs.
    private static final float[] STAGING_VERTICES = new float[MAX_VERTICES * FLOATS_PER_VERTEX];
    private static final short[] STAGING_INDICES = new short[MAX_INDICES];

    private final Mesh mesh;
    /**
     * Chunk axis-aligned bounding box, refreshed at the end of every rebuild.
     * The renderer tests this against the camera frustum so off-screen chunks
     * cost neither a draw call nor a shader-side vertex pass. Initialised to a
     * degenerate box so a chunk that has never been rebuilt is culled by
     * default rather than always drawn.
     */
    private final com.badlogic.gdx.math.collision.BoundingBox bounds =
        new com.badlogic.gdx.math.collision.BoundingBox();
    private int vertexCount;
    private int indexCount;

    public ChunkMesh() {
        // At 32x32 tiles: 5 quads/tile * 1024 tiles * 4 verts = 20,480 verts,
        // comfortably inside the 65,535 ceiling short indices impose.
        mesh = new Mesh(false, MAX_VERTICES, MAX_INDICES,
            new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
            new VertexAttribute(VertexAttributes.Usage.Normal, 3, "a_normal"),
            new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, "a_color"));
    }

    public Mesh getMesh() {
        return mesh;
    }

    public int getIndexCount() {
        return indexCount;
    }

    public boolean isEmpty() {
        return indexCount == 0;
    }

    /**
     * How far a claimed tile's colour is pulled towards its owner's species
     * colour. Border tiles are pushed much further so the edge of a territory
     * reads as a line rather than the interior simply looking tinted.
     */
    private static final float TERRITORY_TINT = 0.38f;
    private static final float BORDER_TINT = 0.80f;

    /** Regenerates this chunk's geometry from the world's current state. */
    public void rebuild(World world, Villages villages, int chunkX, int chunkZ) {
        vertexCount = 0;
        indexCount = 0;

        int startX = chunkX * SimConfig.CHUNK_SIZE;
        int startZ = chunkZ * SimConfig.CHUNK_SIZE;
        int endX = Math.min(startX + SimConfig.CHUNK_SIZE, world.size);
        int endZ = Math.min(startZ + SimConfig.CHUNK_SIZE, world.size);

        // Track this chunk's actual vertical range for the frustum bounds. A
        // chunk covering only lowland grass sits in a much shorter slab than
        // one holding a snow peak, and a tight box culls more aggressively.
        float minY = SimConfig.SEA_LEVEL;
        float maxY = SimConfig.SEA_LEVEL;
        for (int z = startZ; z < endZ; z++) {
            for (int x = startX; x < endX; x++) {
                byte type = world.typeAt(x, z);
                float height = world.heightAt(x, z);
                float x0 = x * TILE_SIZE;
                float z0 = z * TILE_SIZE;
                float x1 = x0 + TILE_SIZE;
                float z1 = z0 + TILE_SIZE;

                // Water renders as a flat sheet at sea level rather than at the
                // seabed's own height, so lakes and coastline read as water
                // surfaces instead of blue-tinted holes.
                float top = TileType.isWater(type) ? SimConfig.SEA_LEVEL : height;
                if (top < minY) minY = top;
                if (top > maxY) maxY = top;
                float topColor = territoryTintedTop(world, villages, x, z, type);
                float sideColor = TerrainPalette.sidePacked(type);

                addQuad(
                    x0, top, z0,
                    x0, top, z1,
                    x1, top, z1,
                    x1, top, z0,
                    0f, 1f, 0f, topColor);

                // Wall quads drop to whichever neighbour is lower. Sampling
                // past the world border returns the border tile's own height,
                // so the outer rim gets no spurious wall.
                addWallIfLower(world, x, z, top, x0, z0, x0, z1, -1f, 0f, 0f, x - 1, z, sideColor);
                addWallIfLower(world, x, z, top, x1, z1, x1, z0, 1f, 0f, 0f, x + 1, z, sideColor);
                addWallIfLower(world, x, z, top, x1, z0, x0, z0, 0f, 0f, -1f, x, z - 1, sideColor);
                addWallIfLower(world, x, z, top, x0, z1, x1, z1, 0f, 0f, 1f, x, z + 1, sideColor);
            }
        }

        mesh.setVertices(STAGING_VERTICES, 0, vertexCount * FLOATS_PER_VERTEX);
        mesh.setIndices(STAGING_INDICES, 0, indexCount);

        // Bounds include the floor a wall could drop to, so a mountain-tile
        // chunk on the edge of a deep-water trench is not culled the moment
        // the camera looks at its neighbour's cliff face.
        bounds.set(
            new com.badlogic.gdx.math.Vector3(startX, SimConfig.MIN_HEIGHT, startZ),
            new com.badlogic.gdx.math.Vector3(endX, maxY, endZ));
        // A stunted maxY would make an empty chunk cull incorrectly; hold the
        // sea level baseline so the water plane always registers.
        if (maxY > minY) {
            bounds.ext(new com.badlogic.gdx.math.Vector3(startX, minY, startZ));
        }
    }

    /** Axis-aligned bounds of this chunk's geometry, in world space. */
    public com.badlogic.gdx.math.collision.BoundingBox getBounds() {
        return bounds;
    }

    /**
     * A tile's top-face colour with its owning village's species colour blended
     * in. Unclaimed ground is left exactly as the palette painted it.
     */
    private static float territoryTintedTop(World world, Villages villages, int x, int z, byte type) {
        short owner = world.ownerVillage[world.index(x, z)];
        if (owner == World.NO_OWNER || villages == null || !villages.isAlive(owner)) {
            return TerrainPalette.topPacked(type);
        }
        Color base = TerrainPalette.top(type);
        Color tint = UnitRenderer.colorFor(villages.species[owner]);
        float strength = isBorderTile(world, x, z, owner) ? BORDER_TINT : TERRITORY_TINT;
        return Color.toFloatBits(
            base.r + (tint.r - base.r) * strength,
            base.g + (tint.g - base.g) * strength,
            base.b + (tint.b - base.b) * strength,
            1f);
    }

    /**
     * True if any orthogonal neighbour is owned by someone else - including
     * nobody, so a territory's outer edge against wilderness reads as a border
     * too, not just the seam between two rival claims.
     */
    private static boolean isBorderTile(World world, int x, int z, short owner) {
        return differsFrom(world, x - 1, z, owner)
            || differsFrom(world, x + 1, z, owner)
            || differsFrom(world, x, z - 1, owner)
            || differsFrom(world, x, z + 1, owner);
    }

    private static boolean differsFrom(World world, int x, int z, short owner) {
        if (!world.inBounds(x, z)) {
            return false;
        }
        return world.ownerVillage[world.index(x, z)] != owner;
    }

    /**
     * Emits one vertical wall between this tile's surface and a lower
     * neighbour. The wall's two top corners are given in the winding order that
     * leaves the quad facing outwards along the supplied normal.
     */
    private void addWallIfLower(World world, int x, int z, float top,
                                float ax, float az, float bx, float bz,
                                float nx, float ny, float nz,
                                int neighbourX, int neighbourZ, float color) {
        float neighbourTop = surfaceHeight(world, neighbourX, neighbourZ);
        if (neighbourTop >= top - 0.0001f) {
            return;
        }
        addQuad(
            ax, top, az,
            bx, top, bz,
            bx, neighbourTop, bz,
            ax, neighbourTop, az,
            nx, ny, nz, color);
    }

    /**
     * The height a neighbouring tile's visible surface sits at, matching the
     * water-at-sea-level rule used above. Out of bounds clamps to the border
     * tile, which suppresses walls around the outside of the map.
     */
    private static float surfaceHeight(World world, int x, int z) {
        int cx = x < 0 ? 0 : (x >= world.size ? world.size - 1 : x);
        int cz = z < 0 ? 0 : (z >= world.size ? world.size - 1 : z);
        byte type = world.typeAt(cx, cz);
        return TileType.isWater(type) ? SimConfig.SEA_LEVEL : world.heightAt(cx, cz);
    }

    /** Appends one quad as two triangles, with all four vertices sharing a normal and colour. */
    private void addQuad(float x0, float y0, float z0,
                         float x1, float y1, float z1,
                         float x2, float y2, float z2,
                         float x3, float y3, float z3,
                         float nx, float ny, float nz, float color) {
        int base = vertexCount;
        pushVertex(x0, y0, z0, nx, ny, nz, color);
        pushVertex(x1, y1, z1, nx, ny, nz, color);
        pushVertex(x2, y2, z2, nx, ny, nz, color);
        pushVertex(x3, y3, z3, nx, ny, nz, color);

        STAGING_INDICES[indexCount++] = (short) base;
        STAGING_INDICES[indexCount++] = (short) (base + 1);
        STAGING_INDICES[indexCount++] = (short) (base + 2);
        STAGING_INDICES[indexCount++] = (short) base;
        STAGING_INDICES[indexCount++] = (short) (base + 2);
        STAGING_INDICES[indexCount++] = (short) (base + 3);
    }

    private void pushVertex(float x, float y, float z, float nx, float ny, float nz, float color) {
        int i = vertexCount * FLOATS_PER_VERTEX;
        STAGING_VERTICES[i] = x;
        STAGING_VERTICES[i + 1] = y;
        STAGING_VERTICES[i + 2] = z;
        STAGING_VERTICES[i + 3] = nx;
        STAGING_VERTICES[i + 4] = ny;
        STAGING_VERTICES[i + 5] = nz;
        STAGING_VERTICES[i + 6] = color;
        vertexCount++;
    }

    @Override
    public void dispose() {
        mesh.dispose();
    }
}
