package com.game.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.utils.Disposable;
import com.game.sim.SimConfig;
import com.game.sim.Species;
import com.game.sim.TileType;
import com.game.sim.Units;
import com.game.sim.World;

/**
 * Draws every unit into a small fixed set of batched meshes - never one
 * {@code ModelInstance} per unit, which at two thousand units would be two
 * thousand draw calls and two thousand transform updates a frame.
 *
 * <p>Each unit is a body box and a head box, species-coloured. Bottom faces are
 * skipped: units are always viewed from above, so a downward face can never be
 * seen and costs four vertices to say so.
 */
public final class UnitRenderer implements Disposable {

    /** Body and head, five visible faces each, four vertices per face. */
    private static final int VERTICES_PER_UNIT = 2 * 5 * 4;
    private static final int INDICES_PER_UNIT = 2 * 5 * 6;

    /**
     * libGDX writes mesh indices as shorts, so no single mesh can address more
     * than 65,535 vertices. At 40 vertices a unit that is about 1,600 units per
     * mesh; 1,400 leaves comfortable headroom, and the pool simply spans as
     * many meshes as it needs.
     */
    private static final int UNITS_PER_MESH = 1400;
    private static final int FLOATS_PER_VERTEX = 7;

    private static final float BODY_WIDTH = 0.26f;
    private static final float BODY_HEIGHT = 0.42f;
    private static final float HEAD_SIZE = 0.19f;
    /** Lifts units clear of the ground so they do not z-fight the terrain top face. */
    private static final float GROUND_OFFSET = 0.02f;

    /**
     * Species colours, chosen for contrast against terrain rather than for
     * naturalism. The same colour tints a species' territory, and the first
     * set - tan humans, green orcs, silver elves, copper dwarves - was close
     * enough to grass, sand and stone that orc territory was invisible on
     * grassland and human territory vanished on sand. These four hues share no
     * ground with the terrain palette, so a claim reads at any zoom.
     */
    private static final Color[] SPECIES_COLOR = {
        new Color(0.98f, 0.72f, 0.18f, 1f), // human - amber
        new Color(0.88f, 0.22f, 0.26f, 1f), // orc - crimson
        new Color(0.22f, 0.82f, 0.88f, 1f), // elf - cyan
        new Color(0.66f, 0.38f, 0.90f, 1f), // dwarf - violet
    };
    /** Heads are slightly darker than bodies so the two boxes read as separate. */
    private static final float HEAD_SHADE = 0.82f;

    private final Mesh[] meshes;
    private final Renderable[] renderables;
    private final Material material;
    private final float[] vertices = new float[UNITS_PER_MESH * VERTICES_PER_UNIT * FLOATS_PER_VERTEX];
    private final short[] indices = new short[UNITS_PER_MESH * INDICES_PER_UNIT];

    private int vertexCount;
    private int indexCount;
    private int visibleUnits;

    public UnitRenderer() {
        int meshCount = (SimConfig.MAX_UNITS + UNITS_PER_MESH - 1) / UNITS_PER_MESH;
        meshes = new Mesh[meshCount];
        renderables = new Renderable[meshCount];

        material = new Material(ColorAttribute.createDiffuse(1f, 1f, 1f, 1f));

        for (int i = 0; i < meshCount; i++) {
            meshes[i] = new Mesh(false,
                UNITS_PER_MESH * VERTICES_PER_UNIT,
                UNITS_PER_MESH * INDICES_PER_UNIT,
                new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
                new VertexAttribute(VertexAttributes.Usage.Normal, 3, "a_normal"),
                new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, "a_color"));

            Renderable renderable = new Renderable();
            renderable.material = material;
            renderable.worldTransform.idt();
            renderable.meshPart.mesh = meshes[i];
            renderable.meshPart.primitiveType = GL20.GL_TRIANGLES;
            renderable.meshPart.offset = 0;
            renderable.meshPart.size = 0;
            renderables[i] = renderable;
        }
    }

    public int getVisibleUnits() {
        return visibleUnits;
    }

    /**
     * Rebuilds the unit geometry from the pool.
     *
     * <p>Called when the simulation has actually ticked rather than every
     * frame: units move ten times a second, so at 60fps five frames in six
     * would rebuild half a million vertices to put everything back exactly
     * where it already was.
     */
    public void rebuild(World world, Units units) {
        int meshIndex = 0;
        int inThisMesh = 0;
        vertexCount = 0;
        indexCount = 0;
        visibleUnits = 0;

        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (!units.alive[i]) {
                continue;
            }
            appendUnit(world, units, i);
            visibleUnits++;
            inThisMesh++;

            if (inThisMesh == UNITS_PER_MESH) {
                flush(meshIndex++);
                inThisMesh = 0;
                if (meshIndex >= meshes.length) {
                    break;
                }
            }
        }

        if (meshIndex < meshes.length) {
            flush(meshIndex++);
        }
        // Any meshes past the ones just filled hold stale geometry from a
        // larger population; blank them or dead units keep being drawn.
        for (int i = meshIndex; i < meshes.length; i++) {
            renderables[i].meshPart.size = 0;
        }
    }

    private void flush(int meshIndex) {
        renderables[meshIndex].meshPart.offset = 0;
        renderables[meshIndex].meshPart.size = indexCount;
        if (indexCount == 0) {
            // MeshPart.update() computes a bounding box and throws on an empty
            // range, which is exactly the state at startup before anything has
            // been spawned. Nothing to bound, nothing to draw.
            return;
        }
        meshes[meshIndex].setVertices(vertices, 0, vertexCount * FLOATS_PER_VERTEX);
        meshes[meshIndex].setIndices(indices, 0, indexCount);
        renderables[meshIndex].meshPart.update();
        vertexCount = 0;
        indexCount = 0;
    }

    private void appendUnit(World world, Units units, int i) {
        float x = units.x[i];
        float z = units.z[i];
        float ground = groundHeight(world, x, z) + GROUND_OFFSET;

        Color base = SPECIES_COLOR[units.species[i]];
        float bodyColor = base.toFloatBits();
        float headColor = Color.toFloatBits(
            base.r * HEAD_SHADE, base.g * HEAD_SHADE, base.b * HEAD_SHADE, 1f);

        float half = BODY_WIDTH * 0.5f;
        addBox(x - half, ground, z - half,
            x + half, ground + BODY_HEIGHT, z + half, bodyColor);

        float headHalf = HEAD_SIZE * 0.5f;
        float headBottom = ground + BODY_HEIGHT;
        addBox(x - headHalf, headBottom, z - headHalf,
            x + headHalf, headBottom + HEAD_SIZE, z + headHalf, headColor);
    }

    /** The visible surface height under a position, matching how terrain is drawn. */
    private static float groundHeight(World world, float worldX, float worldZ) {
        int tileX = (int) Math.floor(worldX);
        int tileZ = (int) Math.floor(worldZ);
        if (!world.inBounds(tileX, tileZ)) {
            return SimConfig.SEA_LEVEL;
        }
        byte type = world.typeAt(tileX, tileZ);
        return TileType.isWater(type) ? SimConfig.SEA_LEVEL : world.heightAt(tileX, tileZ);
    }

    /** Five faces of an axis-aligned box; the bottom is never visible from above. */
    private void addBox(float x0, float y0, float z0, float x1, float y1, float z1, float color) {
        addQuad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0f, 1f, 0f, color);
        addQuad(x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, -1f, 0f, 0f, color);
        addQuad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1f, 0f, 0f, color);
        addQuad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0f, 0f, -1f, color);
        addQuad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f, color);
    }

    private void addQuad(float ax, float ay, float az,
                         float bx, float by, float bz,
                         float cx, float cy, float cz,
                         float dx, float dy, float dz,
                         float nx, float ny, float nz, float color) {
        int base = vertexCount;
        pushVertex(ax, ay, az, nx, ny, nz, color);
        pushVertex(bx, by, bz, nx, ny, nz, color);
        pushVertex(cx, cy, cz, nx, ny, nz, color);
        pushVertex(dx, dy, dz, nx, ny, nz, color);

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

    public void render(ModelBatch batch, Environment environment) {
        for (Renderable renderable : renderables) {
            if (renderable.meshPart.size == 0) {
                continue;
            }
            renderable.environment = environment;
            batch.render(renderable);
        }
    }

    public static Color colorFor(byte species) {
        return SPECIES_COLOR[species];
    }

    @Override
    public void dispose() {
        for (Mesh mesh : meshes) {
            mesh.dispose();
        }
    }

    static {
        // A silent mismatch here would mean species rendering in the wrong
        // colours, or an index out of bounds the first time a new one spawns.
        if (SPECIES_COLOR.length != Species.COUNT) {
            throw new IllegalStateException("species colour table is out of step with Species.COUNT");
        }
    }
}
