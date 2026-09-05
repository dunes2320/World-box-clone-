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
import com.game.sim.TileType;
import com.game.sim.Villages;
import com.game.sim.World;

/**
 * A marker at each village centre: a squat hall with a tall post, so a
 * settlement is findable at a glance rather than being just a patch of tint.
 *
 * <p>Villages are few and change rarely, so this is one small mesh rebuilt only
 * when the village set actually changes.
 */
public final class VillageRenderer implements Disposable {

    private static final int BOXES_PER_VILLAGE = 2;
    private static final int VERTICES_PER_BOX = 5 * 4;
    private static final int INDICES_PER_BOX = 5 * 6;
    private static final int FLOATS_PER_VERTEX = 7;

    private static final float HALL_WIDTH = 1.5f;
    private static final float HALL_HEIGHT = 0.9f;
    private static final float POST_WIDTH = 0.24f;
    private static final float POST_HEIGHT = 2.1f;

    private final Mesh mesh;
    private final Renderable renderable;
    private final Material material;
    private final float[] vertices;
    private final short[] indices;

    private int vertexCount;
    private int indexCount;
    private int visibleVillages;

    public VillageRenderer() {
        int maxBoxes = SimConfig.MAX_VILLAGES * BOXES_PER_VILLAGE;
        vertices = new float[maxBoxes * VERTICES_PER_BOX * FLOATS_PER_VERTEX];
        indices = new short[maxBoxes * INDICES_PER_BOX];

        mesh = new Mesh(false, maxBoxes * VERTICES_PER_BOX, maxBoxes * INDICES_PER_BOX,
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

    public int getVisibleVillages() {
        return visibleVillages;
    }

    public void rebuild(World world, Villages villages) {
        vertexCount = 0;
        indexCount = 0;
        visibleVillages = 0;

        int end = villages.getHighWater();
        for (int v = 0; v < end; v++) {
            if (!villages.alive[v]) {
                continue;
            }
            float centreX = villages.x[v] + 0.5f;
            float centreZ = villages.z[v] + 0.5f;
            float ground = groundHeight(world, centreX, centreZ);

            Color species = UnitRenderer.colorFor(villages.species[v]);
            // The hall is darkened and the post left bright, so the marker
            // reads as a built thing rather than an oversized unit.
            float hallColor = Color.toFloatBits(species.r * 0.55f, species.g * 0.55f, species.b * 0.55f, 1f);
            float postColor = species.toFloatBits();

            float half = HALL_WIDTH * 0.5f;
            addBox(centreX - half, ground, centreZ - half,
                centreX + half, ground + HALL_HEIGHT, centreZ + half, hallColor);

            float postHalf = POST_WIDTH * 0.5f;
            addBox(centreX - postHalf, ground, centreZ - postHalf,
                centreX + postHalf, ground + POST_HEIGHT, centreZ + postHalf, postColor);

            visibleVillages++;
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

    private static float groundHeight(World world, float worldX, float worldZ) {
        int tileX = (int) Math.floor(worldX);
        int tileZ = (int) Math.floor(worldZ);
        if (!world.inBounds(tileX, tileZ)) {
            return SimConfig.SEA_LEVEL;
        }
        byte type = world.typeAt(tileX, tileZ);
        return TileType.isWater(type) ? SimConfig.SEA_LEVEL : world.heightAt(tileX, tileZ);
    }

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
