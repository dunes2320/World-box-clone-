package com.game.render;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.utils.Disposable;
import com.game.sim.SimConfig;
import com.game.sim.TileType;
import com.game.sim.World;

/**
 * Builds and owns the {@link Mesh} for one 16x16 chunk of terrain.
 *
 * <p>Tiles are drawn stepped rather than smoothly interpolated: each tile is a
 * flat quad at its own height, plus vertical wall quads dropping to any
 * neighbour that sits lower. That gives the blocky silhouette, and because no
 * vertex is shared between faces, every face can carry a single flat colour and
 * a single hard normal - which is what "flat shading, vertex colours, no
 * textures" actually requires.
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

    private final Mesh mesh;
    private final float[] vertices = new float[MAX_VERTICES * FLOATS_PER_VERTEX];
    private final short[] indices = new short[MAX_INDICES];

    private int vertexCount;
    private int indexCount;

    public ChunkMesh() {
        // 4 quads/tile * 256 tiles * 4 verts = 5120 max, comfortably inside the
        // 65535 ceiling that short indices impose.
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

    /** Regenerates this chunk's geometry from the world's current state. */
    public void rebuild(World world, int chunkX, int chunkZ) {
        vertexCount = 0;
        indexCount = 0;

        int startX = chunkX * SimConfig.CHUNK_SIZE;
        int startZ = chunkZ * SimConfig.CHUNK_SIZE;
        int endX = Math.min(startX + SimConfig.CHUNK_SIZE, world.size);
        int endZ = Math.min(startZ + SimConfig.CHUNK_SIZE, world.size);

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
                float topColor = TerrainPalette.topPacked(type);
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

        mesh.setVertices(vertices, 0, vertexCount * FLOATS_PER_VERTEX);
        mesh.setIndices(indices, 0, indexCount);
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

        indices[indexCount++] = (short) base;
        indices[indexCount++] = (short) (base + 1);
        indices[indexCount++] = (short) (base + 2);
        indices[indexCount++] = (short) base;
        indices[indexCount++] = (short) (base + 2);
        indices[indexCount++] = (short) (base + 3);
    }

    private void pushVertex(float x, float y, float z, float nx, float ny, float nz, float color) {
        int i = vertexCount * FLOATS_PER_VERTEX;
        vertices[i] = x;
        vertices[i + 1] = y;
        vertices[i + 2] = z;
        vertices[i + 3] = nx;
        vertices[i + 4] = ny;
        vertices[i + 5] = nz;
        vertices[i + 6] = color;
        vertexCount++;
    }

    @Override
    public void dispose() {
        mesh.dispose();
    }
}
