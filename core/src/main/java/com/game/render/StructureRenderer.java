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
import com.game.sim.Features;
import com.game.sim.SimConfig;
import com.game.sim.TileType;
import com.game.sim.Villages;
import com.game.sim.World;

/**
 * Draws every placed structure (house, farm, mine...) in a small set of
 * batched meshes.
 *
 * <p>Same design as {@link UnitRenderer}: one mesh per {@code FEATURES_PER_MESH}
 * features so a whole civilization's buildings cost a handful of draw calls,
 * not thousands. Roads are excluded here - they render flat on the ground and
 * belong to {@link RoadRenderer}.
 *
 * <p>Each feature is drawn as a small box (with the top a slightly larger
 * offset from the ground so a house reads at a distance). Colour is the
 * feature's kind palette, tinted toward the owning village's species colour
 * so a settlement's buildings share its national colour.
 */
public final class StructureRenderer implements Disposable {

    /** Five visible faces per box (skip the bottom). */
    private static final int VERTICES_PER_STRUCTURE = 5 * 4;
    private static final int INDICES_PER_STRUCTURE = 5 * 6;
    private static final int FLOATS_PER_VERTEX = 7;
    private static final int FEATURES_PER_MESH = 1200;
    private static final int MAX_STRUCTURES = 24000;

    /** How far a building's own colour is pulled toward its species colour. */
    private static final float SPECIES_TINT = 0.55f;

    private static final Color HOUSE = new Color(0.82f, 0.65f, 0.42f, 1f);
    private static final Color FARM = new Color(0.92f, 0.82f, 0.28f, 1f);
    private static final Color BARRACKS = new Color(0.42f, 0.42f, 0.48f, 1f);
    private static final Color DOCK = new Color(0.62f, 0.42f, 0.30f, 1f);
    private static final Color TEMPLE = new Color(0.94f, 0.92f, 0.86f, 1f);
    private static final Color MARKET = new Color(0.88f, 0.44f, 0.30f, 1f);
    private static final Color WALL = new Color(0.55f, 0.55f, 0.55f, 1f);
    private static final Color MINE = new Color(0.36f, 0.32f, 0.32f, 1f);
    private static final Color LUMBER = new Color(0.52f, 0.36f, 0.22f, 1f);

    private final Mesh[] meshes;
    private final Renderable[] renderables;
    private final Material material;
    private final float[] vertices = new float[FEATURES_PER_MESH * VERTICES_PER_STRUCTURE * FLOATS_PER_VERTEX];
    private final short[] indices = new short[FEATURES_PER_MESH * INDICES_PER_STRUCTURE];
    private int vertexCount;
    private int indexCount;
    private int visibleStructures;
    private int builtStamp = -1;

    public StructureRenderer() {
        int meshCount = (MAX_STRUCTURES + FEATURES_PER_MESH - 1) / FEATURES_PER_MESH;
        meshes = new Mesh[meshCount];
        renderables = new Renderable[meshCount];
        material = new Material(ColorAttribute.createDiffuse(1f, 1f, 1f, 1f));

        for (int i = 0; i < meshCount; i++) {
            meshes[i] = new Mesh(false,
                FEATURES_PER_MESH * VERTICES_PER_STRUCTURE,
                FEATURES_PER_MESH * INDICES_PER_STRUCTURE,
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

    public int getVisibleStructures() {
        return visibleStructures;
    }

    /**
     * Rebuilds the structure geometry from the world.
     *
     * @param featureGeneration a counter the caller bumps whenever a feature
     *     is placed or removed. If it matches the last build this returns
     *     immediately, so a world where no new buildings were placed costs
     *     one comparison.
     * @return true if a real rebuild happened
     */
    public boolean rebuildIfChanged(World world, Villages villages, Features features,
                                    int featureGeneration) {
        if (featureGeneration == builtStamp) {
            return false;
        }
        builtStamp = featureGeneration;
        rebuild(world, villages, features);
        return true;
    }

    private void rebuild(World world, Villages villages, Features features) {
        int meshIndex = 0;
        int inThisMesh = 0;
        vertexCount = 0;
        indexCount = 0;
        visibleStructures = 0;

        int end = features.getHighWater();
        for (int i = 0; i < end && meshIndex < meshes.length; i++) {
            if (!features.isAlive(i) || features.kind[i] == Features.KIND_ROAD) {
                continue;
            }
            appendStructure(world, villages, features, i);
            visibleStructures++;
            if (++inThisMesh == FEATURES_PER_MESH) {
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
        for (int i = meshIndex; i < meshes.length; i++) {
            renderables[i].meshPart.size = 0;
        }
    }

    private void flush(int meshIndex) {
        renderables[meshIndex].meshPart.offset = 0;
        renderables[meshIndex].meshPart.size = indexCount;
        if (indexCount == 0) {
            return;
        }
        meshes[meshIndex].setVertices(vertices, 0, vertexCount * FLOATS_PER_VERTEX);
        meshes[meshIndex].setIndices(indices, 0, indexCount);
        renderables[meshIndex].meshPart.update();
        vertexCount = 0;
        indexCount = 0;
    }

    private void appendStructure(World world, Villages villages, Features features, int i) {
        byte kind = features.kind[i];
        int tileIndex = features.tile[i];
        int tileX = tileIndex % world.size;
        int tileZ = tileIndex / world.size;
        float groundY = groundHeight(world, tileX, tileZ);

        // dx/dz stored as bytes in 1/256ths of a tile; convert to (0..1).
        float dx = ((features.dx[i] & 0xff)) / 256f;
        float dz = ((features.dz[i] & 0xff)) / 256f;
        float cx = tileX + dx;
        float cz = tileZ + dz;

        Color base = kindColor(kind);
        int owner = features.owner[i];
        if (villages != null && owner >= 0 && owner < villages.capacity && villages.isAlive(owner)) {
            base = mix(base, UnitRenderer.colorFor(villages.species[owner]), SPECIES_TINT);
        }
        float bodyColor = base.toFloatBits();
        float roofColor = Color.toFloatBits(base.r * 0.75f, base.g * 0.75f, base.b * 0.75f, 1f);

        Dim d = dimensionsFor(kind);
        float x0 = cx - d.width * 0.5f;
        float x1 = cx + d.width * 0.5f;
        float z0 = cz - d.depth * 0.5f;
        float z1 = cz + d.depth * 0.5f;
        float y0 = groundY + 0.02f;
        float y1 = y0 + d.height;

        // Top
        addQuad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0f, 1f, 0f, roofColor);
        // Sides
        addQuad(x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, -1f, 0f, 0f, bodyColor);
        addQuad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1f, 0f, 0f, bodyColor);
        addQuad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0f, 0f, -1f, bodyColor);
        addQuad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f, bodyColor);
    }

    private static float groundHeight(World world, int tileX, int tileZ) {
        if (!world.inBounds(tileX, tileZ)) {
            return SimConfig.SEA_LEVEL;
        }
        byte type = world.typeAt(tileX, tileZ);
        return TileType.isWater(type) ? SimConfig.SEA_LEVEL : world.heightAt(tileX, tileZ);
    }

    private static Color kindColor(byte kind) {
        switch (kind) {
            case Features.KIND_HOUSE: return HOUSE;
            case Features.KIND_FARM: return FARM;
            case Features.KIND_BARRACKS: return BARRACKS;
            case Features.KIND_DOCK: return DOCK;
            case Features.KIND_TEMPLE: return TEMPLE;
            case Features.KIND_MARKET: return MARKET;
            case Features.KIND_WALL: return WALL;
            case Features.KIND_MINE: return MINE;
            case Features.KIND_LUMBER_CAMP: return LUMBER;
            default: return HOUSE;
        }
    }

    /** Per-kind footprint and height. */
    private static Dim dimensionsFor(byte kind) {
        switch (kind) {
            case Features.KIND_HOUSE: return new Dim(0.55f, 0.55f, 0.55f);
            case Features.KIND_FARM: return new Dim(0.80f, 0.80f, 0.10f);
            case Features.KIND_BARRACKS: return new Dim(0.75f, 0.65f, 0.60f);
            case Features.KIND_DOCK: return new Dim(0.75f, 0.35f, 0.20f);
            case Features.KIND_TEMPLE: return new Dim(0.70f, 0.70f, 0.90f);
            case Features.KIND_MARKET: return new Dim(0.80f, 0.80f, 0.35f);
            case Features.KIND_WALL: return new Dim(0.90f, 0.20f, 0.55f);
            case Features.KIND_MINE: return new Dim(0.55f, 0.55f, 0.45f);
            case Features.KIND_LUMBER_CAMP: return new Dim(0.60f, 0.60f, 0.40f);
            default: return new Dim(0.50f, 0.50f, 0.50f);
        }
    }

    private static Color mix(Color a, Color b, float t) {
        return new Color(
            a.r + (b.r - a.r) * t,
            a.g + (b.g - a.g) * t,
            a.b + (b.b - a.b) * t,
            1f);
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

    @Override
    public void dispose() {
        for (Mesh mesh : meshes) {
            mesh.dispose();
        }
    }

    private static final class Dim {
        final float width;
        final float depth;
        final float height;
        Dim(float width, float depth, float height) {
            this.width = width;
            this.depth = depth;
            this.height = height;
        }
    }
}
