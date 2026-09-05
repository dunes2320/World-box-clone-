package com.game.render;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.utils.Disposable;
import com.game.sim.World;

/**
 * Draws the world as one mesh per 16x16 chunk, rebuilding only the chunks the
 * simulation has flagged dirty. A terraform stroke touching a handful of tiles
 * re-meshes one or two chunks, not the whole 128x128 map.
 */
public final class TerrainRenderer implements Disposable {

    /**
     * Cap on how many chunks may be re-meshed in a single frame. A brush drag
     * across a chunk boundary dirties a couple at a time so this is rarely
     * reached, but a whole-map event (a fresh world, a large disaster) would
     * otherwise rebuild all 64 in one frame and drop a visible hitch.
     */
    private static final int MAX_REBUILDS_PER_FRAME = 8;

    private final World world;
    private final ChunkMesh[] chunks;
    private final Renderable[] renderables;
    private final Material material;

    public TerrainRenderer(World world) {
        this.world = world;
        this.chunks = new ChunkMesh[world.chunkCount()];
        this.renderables = new Renderable[world.chunkCount()];

        // Vertex colours carry the actual look; the material's diffuse is left
        // white so it multiplies through unchanged. Backface culling is off
        // because a wall quad's winding depends on which neighbour was lower.
        material = new Material(
            ColorAttribute.createDiffuse(1f, 1f, 1f, 1f),
            IntAttribute.createCullFace(GL20.GL_NONE));

        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = new ChunkMesh();
            Renderable renderable = new Renderable();
            renderable.material = material;
            renderable.worldTransform.idt();
            renderable.meshPart.mesh = chunks[i].getMesh();
            renderable.meshPart.primitiveType = GL20.GL_TRIANGLES;
            renderable.meshPart.offset = 0;
            renderable.meshPart.size = 0;
            renderables[i] = renderable;
        }
    }

    /**
     * Re-meshes dirty chunks, up to this frame's budget. Chunks left over stay
     * flagged and are picked up next frame.
     */
    public void update() {
        int rebuilt = 0;
        for (int i = 0; i < chunks.length && rebuilt < MAX_REBUILDS_PER_FRAME; i++) {
            if (!world.isChunkDirty(i)) {
                continue;
            }
            int chunkX = i % world.chunksPerAxis;
            int chunkZ = i / world.chunksPerAxis;
            chunks[i].rebuild(world, chunkX, chunkZ);

            Renderable renderable = renderables[i];
            renderable.meshPart.size = chunks[i].getIndexCount();
            renderable.meshPart.update();

            world.clearChunkDirty(i);
            rebuilt++;
        }
    }

    public void render(ModelBatch batch, Environment environment) {
        for (int i = 0; i < renderables.length; i++) {
            if (chunks[i].isEmpty()) {
                continue;
            }
            Renderable renderable = renderables[i];
            renderable.environment = environment;
            batch.render(renderable);
        }
    }

    /** True once every chunk has been meshed at least once. */
    public boolean isFullyBuilt() {
        for (int i = 0; i < chunks.length; i++) {
            if (world.isChunkDirty(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void dispose() {
        for (ChunkMesh chunk : chunks) {
            chunk.dispose();
        }
    }
}
