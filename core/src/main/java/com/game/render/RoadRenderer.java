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
import com.game.sim.Roads;
import com.game.sim.SimConfig;
import com.game.sim.TileType;
import com.game.sim.World;

/**
 * Draws roads as flat tan quads sitting just above the terrain surface.
 *
 * <p>A single mesh: at the phase 7 scale ceiling of 512x512 a whole map of
 * roads would be 256K tiles, and that never happens - dense village
 * networks might use a few thousand road tiles at most. One mesh keeps
 * roads a single draw call.
 *
 * <p>The quad sits at {@code height + 0.03} so it never z-fights the tile
 * top: 0.02 is what the unit and structure floors already use, and a road
 * on the same plane as those either cannot be seen from above or flickers.
 */
public final class RoadRenderer implements Disposable {

    private static final int FLOATS_PER_VERTEX = 7;
    private static final int VERTICES_PER_ROAD = 4;
    private static final int INDICES_PER_ROAD = 6;
    /** Cap on total road tiles rendered. Beyond this the extras are skipped silently. */
    private static final int MAX_ROADS = 8000;
    private static final float ROAD_LIFT = 0.05f;

    /**
     * A dark brown. The first pick was a lighter tan (0.68, 0.56, 0.36),
     * which is essentially the sand palette and made roads through coastal
     * villages invisible against the beach they crossed. A darker earth
     * reads against sand, grass, forest and stone alike.
     */
    private static final Color ROAD = new Color(0.32f, 0.22f, 0.14f, 1f);

    private final Mesh mesh;
    private final Renderable renderable;
    private final Material material;
    private final float[] vertices = new float[MAX_ROADS * VERTICES_PER_ROAD * FLOATS_PER_VERTEX];
    private final short[] indices = new short[MAX_ROADS * INDICES_PER_ROAD];
    private int vertexCount;
    private int indexCount;
    private int visibleRoads;
    private int lastCount = -1;

    public RoadRenderer() {
        mesh = new Mesh(false,
            MAX_ROADS * VERTICES_PER_ROAD,
            MAX_ROADS * INDICES_PER_ROAD,
            new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
            new VertexAttribute(VertexAttributes.Usage.Normal, 3, "a_normal"),
            new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, "a_color"));
        material = new Material(ColorAttribute.createDiffuse(1f, 1f, 1f, 1f));
        renderable = new Renderable();
        renderable.material = material;
        renderable.worldTransform.idt();
        renderable.meshPart.mesh = mesh;
        renderable.meshPart.primitiveType = GL20.GL_TRIANGLES;
        renderable.meshPart.offset = 0;
        renderable.meshPart.size = 0;
    }

    public int getVisibleRoads() {
        return visibleRoads;
    }

    /**
     * Rebuilds the road mesh if the road count changed. Cheap when a village
     * update did not add any roads.
     */
    public boolean rebuildIfChanged(World world, Roads roads) {
        if (roads.getCount() == lastCount) {
            return false;
        }
        lastCount = roads.getCount();
        rebuild(world, roads);
        return true;
    }

    private void rebuild(World world, Roads roads) {
        vertexCount = 0;
        indexCount = 0;
        visibleRoads = 0;
        float colorBits = ROAD.toFloatBits();

        for (int z = 0; z < world.size && visibleRoads < MAX_ROADS; z++) {
            for (int x = 0; x < world.size; x++) {
                int i = world.index(x, z);
                if (!roads.isRoad(i)) {
                    continue;
                }
                byte type = world.tileType[i];
                if (TileType.isWater(type)) {
                    continue;
                }
                float y = world.heightAt(x, z) + ROAD_LIFT;
                float x0 = x + 0.05f;
                float x1 = x + 0.95f;
                float z0 = z + 0.05f;
                float z1 = z + 0.95f;
                addQuad(x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0, colorBits);
                visibleRoads++;
                if (visibleRoads >= MAX_ROADS) {
                    break;
                }
            }
        }

        renderable.meshPart.offset = 0;
        renderable.meshPart.size = indexCount;
        if (indexCount == 0) {
            return;
        }
        mesh.setVertices(vertices, 0, vertexCount * FLOATS_PER_VERTEX);
        mesh.setIndices(indices, 0, indexCount);
        renderable.meshPart.update();
    }

    private void addQuad(float ax, float ay, float az,
                         float bx, float by, float bz,
                         float cx, float cy, float cz,
                         float dx, float dy, float dz,
                         float color) {
        int base = vertexCount;
        pushVertex(ax, ay, az, 0f, 1f, 0f, color);
        pushVertex(bx, by, bz, 0f, 1f, 0f, color);
        pushVertex(cx, cy, cz, 0f, 1f, 0f, color);
        pushVertex(dx, dy, dz, 0f, 1f, 0f, color);
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
        if (renderable.meshPart.size == 0) {
            return;
        }
        renderable.environment = environment;
        batch.render(renderable);
    }

    @Override
    public void dispose() {
        mesh.dispose();
    }
}
