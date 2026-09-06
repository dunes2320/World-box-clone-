package com.game.render;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.utils.Disposable;
import com.game.sim.Villages;
import com.game.sim.World;

/**
 * Draws the world as one mesh per chunk, rebuilding only the chunks the
 * simulation has flagged dirty. A terraform stroke touching a handful of tiles
 * re-meshes one or two chunks, not the whole map.
 *
 * <p>Chunk size and world size are read from the {@link World} instance rather
 * than compile-time constants, so this renderer serves both a 128 test world
 * and a 512 large playthrough without changes.
 */
public final class TerrainRenderer implements Disposable {

    /**
     * Cap on how many chunks may be re-meshed in a single frame. A brush drag
     * across a chunk boundary dirties a couple at a time so this is rarely
     * reached, but a whole-map event (a fresh world, a large disaster) would
     * otherwise rebuild every chunk in one frame and drop a visible hitch. At
     * 16 chunks per frame a fresh 256-chunk world (512 tiles a side) is fully
     * meshed in about a quarter of a second at 60fps.
     */
    private static final int MAX_REBUILDS_PER_FRAME = 16;

    private final World world;
    private final Villages villages;
    private final ChunkMesh[] chunks;
    private final Renderable[] renderables;
    private final Material material;

    /** Chunks rebuilt this frame - read by the F3 overlay, cleared each frame. */
    private int chunksRebuiltThisFrame;
    /** Chunks actually submitted for drawing this frame, after frustum culling. */
    private int chunksDrawnLastFrame;
    private int chunksCulledLastFrame;

    public TerrainRenderer(World world, Villages villages) {
        this.world = world;
        this.villages = villages;
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
        chunksRebuiltThisFrame = 0;
        for (int i = 0; i < chunks.length && chunksRebuiltThisFrame < MAX_REBUILDS_PER_FRAME; i++) {
            if (!world.isChunkDirty(i)) {
                continue;
            }
            int chunkX = i % world.chunksPerAxis;
            int chunkZ = i / world.chunksPerAxis;
            chunks[i].rebuild(world, villages, chunkX, chunkZ);

            Renderable renderable = renderables[i];
            renderable.meshPart.size = chunks[i].getIndexCount();
            renderable.meshPart.update();

            world.clearChunkDirty(i);
            chunksRebuiltThisFrame++;
        }
    }

    /**
     * Backwards-compatible entry that skips frustum culling. Left for callers
     * that do not have a camera handy - the game loop uses the culling
     * overload below.
     */
    public void render(ModelBatch batch, Environment environment) {
        chunksDrawnLastFrame = 0;
        chunksCulledLastFrame = 0;
        for (int i = 0; i < renderables.length; i++) {
            if (chunks[i].isEmpty()) {
                continue;
            }
            Renderable renderable = renderables[i];
            renderable.environment = environment;
            batch.render(renderable);
            chunksDrawnLastFrame++;
        }
    }

    /**
     * Draws every chunk whose bounding box lies inside the camera frustum.
     *
     * <p>At 512 tiles a side an overhead view sees around a third of the map;
     * a low-angle ground view sees a small handful of chunks. Culling turns
     * that into the same fraction of draw calls, which is more than half the
     * render-side win from the scale-up.
     */
    public void render(ModelBatch batch, Environment environment, Camera camera) {
        chunksDrawnLastFrame = 0;
        chunksCulledLastFrame = 0;
        for (int i = 0; i < renderables.length; i++) {
            ChunkMesh chunk = chunks[i];
            if (chunk.isEmpty()) {
                continue;
            }
            if (!camera.frustum.boundsInFrustum(chunk.getBounds())) {
                chunksCulledLastFrame++;
                continue;
            }
            Renderable renderable = renderables[i];
            renderable.environment = environment;
            batch.render(renderable);
            chunksDrawnLastFrame++;
        }
    }

    /** For the F3 overlay. */
    public int getChunksRebuiltThisFrame() {
        return chunksRebuiltThisFrame;
    }

    public int getChunksDrawnLastFrame() {
        return chunksDrawnLastFrame;
    }

    public int getChunksCulledLastFrame() {
        return chunksCulledLastFrame;
    }

    public int getChunkCount() {
        return chunks.length;
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
