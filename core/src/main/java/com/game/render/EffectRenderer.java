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
import com.game.sim.World;

/**
 * Flames over burning ground.
 *
 * <p>Batched into a couple of meshes for the same reason units are: a map-wide
 * fire can have thousands of tiles alight at once, and one draw call each would
 * be thousands of draw calls a frame at exactly the moment the game is busiest.
 *
 * <p>Drawing the fire rather than tinting the terrain is deliberate. Colouring
 * burning tiles in the terrain mesh would re-mesh whole 16x16 chunks every time
 * the fire front moved a tile, which during a spreading fire is most ticks.
 * Flames are their own geometry, so the terrain is only re-meshed when a tile
 * actually changes - once, when it burns down to grass.
 */
public final class EffectRenderer implements Disposable {

    /** Four sides and a top; the underside of a flame is against the ground. */
    private static final int VERTICES_PER_FLAME = 5 * 4;
    private static final int INDICES_PER_FLAME = 5 * 6;
    private static final int FLOATS_PER_VERTEX = 7;

    /** Same short-index ceiling as UnitRenderer: 65,535 vertices to a mesh. */
    private static final int FLAMES_PER_MESH = 3000;
    /**
     * Enough for a fire covering a heavily wooded map. Past this the fire is
     * still simulated in full - only the flames beyond the cap go undrawn, and
     * a fire that large is a solid sheet of orange anyway.
     */
    private static final int MAX_FLAMES = 6000;

    private static final float FLAME_WIDTH = 0.74f;
    private static final float FLAME_HEIGHT = 1.25f;
    /** Sinks the flame slightly so it never floats above an uneven tile edge. */
    private static final float GROUND_BITE = 0.12f;

    private static final Color FLAME_LOW = new Color(1.0f, 0.40f, 0.06f, 1f);
    private static final Color FLAME_HIGH = new Color(1.0f, 0.92f, 0.34f, 1f);

    private final Mesh[] meshes;
    private final Renderable[] renderables;
    private final Material material;
    private final float[] vertices = new float[FLAMES_PER_MESH * VERTICES_PER_FLAME * FLOATS_PER_VERTEX];
    private final short[] indices = new short[FLAMES_PER_MESH * INDICES_PER_FLAME];

    private int vertexCount;
    private int indexCount;
    private int visibleFlames;
    /** The fire generation this geometry was built from; -1 means never built. */
    private int builtGeneration = -1;

    public EffectRenderer() {
        int meshCount = (MAX_FLAMES + FLAMES_PER_MESH - 1) / FLAMES_PER_MESH;
        meshes = new Mesh[meshCount];
        renderables = new Renderable[meshCount];

        material = new Material(ColorAttribute.createDiffuse(1f, 1f, 1f, 1f));

        for (int i = 0; i < meshCount; i++) {
            meshes[i] = new Mesh(false,
                FLAMES_PER_MESH * VERTICES_PER_FLAME,
                FLAMES_PER_MESH * INDICES_PER_FLAME,
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

    public int getVisibleFlames() {
        return visibleFlames;
    }

    /**
     * Rebuilds the flames if the fire has changed since the last build.
     *
     * @param generation a counter the simulation bumps whenever a tile lights or
     *     goes out - a plain tile count would miss a fire that stayed the same
     *     size because one tile caught as another died
     * @return true if the geometry was actually rebuilt
     */
    public boolean rebuildIfChanged(World world, int generation) {
        if (generation == builtGeneration) {
            return false;
        }
        builtGeneration = generation;
        rebuild(world);
        return true;
    }

    private void rebuild(World world) {
        int meshIndex = 0;
        int inThisMesh = 0;
        vertexCount = 0;
        indexCount = 0;
        visibleFlames = 0;

        for (int z = 0; z < world.size && meshIndex < meshes.length; z++) {
            for (int x = 0; x < world.size; x++) {
                int i = world.index(x, z);
                if (world.burn[i] == 0) {
                    continue;
                }
                appendFlame(x, z, world.height[i], world.burn[i]);
                visibleFlames++;

                if (++inThisMesh == FLAMES_PER_MESH) {
                    flush(meshIndex++);
                    inThisMesh = 0;
                    if (meshIndex >= meshes.length) {
                        break;
                    }
                }
            }
        }

        if (meshIndex < meshes.length) {
            flush(meshIndex++);
        }
        // Blank anything past what was just filled, or a shrinking fire leaves
        // flames burning over ground that went out several ticks ago.
        for (int i = meshIndex; i < meshes.length; i++) {
            renderables[i].meshPart.size = 0;
        }
    }

    private void flush(int meshIndex) {
        renderables[meshIndex].meshPart.offset = 0;
        renderables[meshIndex].meshPart.size = indexCount;
        if (indexCount == 0) {
            // MeshPart.update() bounds the range and throws on an empty one,
            // which is every frame in a world that is not on fire.
            return;
        }
        meshes[meshIndex].setVertices(vertices, 0, vertexCount * FLOATS_PER_VERTEX);
        meshes[meshIndex].setIndices(indices, 0, indexCount);
        renderables[meshIndex].meshPart.update();
        vertexCount = 0;
        indexCount = 0;
    }

    /** One flame, tapering upward and brightening with it. */
    private void appendFlame(int tileX, int tileZ, float groundHeight, int remaining) {
        // Younger fires burn taller. A front therefore reads as a bright ridge
        // with a dying tail behind it, which is the shape of the thing.
        float life = remaining / (float) SimConfig.FIRE_DURATION;
        float height = FLAME_HEIGHT * (0.45f + 0.55f * life);

        float baseY = groundHeight - GROUND_BITE;
        float topY = baseY + height;
        float half = FLAME_WIDTH * 0.5f;
        float tip = half * 0.28f;

        float centreX = tileX + 0.5f;
        float centreZ = tileZ + 0.5f;

        float low = FLAME_LOW.toFloatBits();
        float high = FLAME_HIGH.toFloatBits();

        float x0 = centreX - half;
        float x1 = centreX + half;
        float z0 = centreZ - half;
        float z1 = centreZ + half;
        float tx0 = centreX - tip;
        float tx1 = centreX + tip;
        float tz0 = centreZ - tip;
        float tz1 = centreZ + tip;

        // Four tapering sides, hot at the tip and dark at the base.
        addQuad(x0, baseY, z0, low, x1, baseY, z0, low, tx1, topY, tz0, high, tx0, topY, tz0, high,
            0f, 0.4f, -0.9f);
        addQuad(x1, baseY, z1, low, x0, baseY, z1, low, tx0, topY, tz1, high, tx1, topY, tz1, high,
            0f, 0.4f, 0.9f);
        addQuad(x0, baseY, z1, low, x0, baseY, z0, low, tx0, topY, tz0, high, tx0, topY, tz1, high,
            -0.9f, 0.4f, 0f);
        addQuad(x1, baseY, z0, low, x1, baseY, z1, low, tx1, topY, tz1, high, tx1, topY, tz0, high,
            0.9f, 0.4f, 0f);
        addQuad(tx0, topY, tz0, high, tx1, topY, tz0, high, tx1, topY, tz1, high, tx0, topY, tz1, high,
            0f, 1f, 0f);
    }

    private void addQuad(float ax, float ay, float az, float ac,
                         float bx, float by, float bz, float bc,
                         float cx, float cy, float cz, float cc,
                         float dx, float dy, float dz, float dc,
                         float nx, float ny, float nz) {
        int base = vertexCount;
        pushVertex(ax, ay, az, nx, ny, nz, ac);
        pushVertex(bx, by, bz, nx, ny, nz, bc);
        pushVertex(cx, cy, cz, nx, ny, nz, cc);
        pushVertex(dx, dy, dz, nx, ny, nz, dc);

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

    /**
     * Draws the flames unlit.
     *
     * <p>The scene's {@code Environment} is deliberately not applied. Fire is
     * its own light source, and shading it like rock meant every face turned
     * away from the sun went dark: the first version of this was a scattering
     * of dim orange slivers that read as debris rather than as a burning
     * forest. With no environment the shader takes the vertex colours as they
     * are, so a flame is the same brightness whichever way it faces.
     */
    public void render(ModelBatch batch, Environment environment) {
        for (Renderable renderable : renderables) {
            if (renderable.meshPart.size == 0) {
                continue;
            }
            renderable.environment = null;
            batch.render(renderable);
        }
    }

    @Override
    public void dispose() {
        for (Mesh mesh : meshes) {
            mesh.dispose();
        }
    }
}
