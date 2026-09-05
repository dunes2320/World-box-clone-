package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldTest {

    private static World cleanWorld() {
        World world = new World();
        for (int c = 0; c < world.chunkCount(); c++) {
            world.clearChunkDirty(c);
        }
        return world;
    }

    @Test
    void rejectsSizesThatDoNotTileIntoChunks() {
        assertThrows(IllegalArgumentException.class, () -> new World(100));
        assertThrows(IllegalArgumentException.class, () -> new World(0));
        assertThrows(IllegalArgumentException.class, () -> new World(-16));
    }

    @Test
    void startsFullyDirtySoTheFirstFrameMeshesEverything() {
        World world = new World();
        for (int c = 0; c < world.chunkCount(); c++) {
            assertTrue(world.isChunkDirty(c), "chunk " + c + " should start dirty");
        }
    }

    @Test
    void indexIsRowMajorAndUnique() {
        World world = new World();
        boolean[] seen = new boolean[world.tileCount];
        for (int z = 0; z < world.size; z++) {
            for (int x = 0; x < world.size; x++) {
                int i = world.index(x, z);
                assertFalse(seen[i], "index collision at (" + x + ", " + z + ")");
                seen[i] = true;
            }
        }
    }

    @Test
    void markDirtyFlagsOnlyTheOwningChunkInTheInterior() {
        World world = cleanWorld();
        // (20, 20) sits inside chunk (1, 1) and touches no seam.
        world.markDirty(20, 20);
        int owning = world.chunkIndex(1, 1);
        for (int c = 0; c < world.chunkCount(); c++) {
            assertEquals(c == owning, world.isChunkDirty(c), "unexpected dirty state for chunk " + c);
        }
    }

    @Test
    void markDirtyAlsoFlagsNeighboursAcrossASeam() {
        World world = cleanWorld();
        // x=16 is the first column of chunk (1, *), so its west wall geometry
        // belongs to chunk (0, *) - both must rebuild or a crack appears.
        world.markDirty(16, 20);
        assertTrue(world.isChunkDirty(world.chunkIndex(1, 1)), "owning chunk must be dirty");
        assertTrue(world.isChunkDirty(world.chunkIndex(0, 1)), "chunk across the seam must be dirty too");
    }

    @Test
    void markDirtyClampsAtTheWorldBorderInsteadOfEscaping() {
        World world = cleanWorld();
        world.markDirty(0, 0);
        assertTrue(world.isChunkDirty(world.chunkIndex(0, 0)));
        // Nothing to the west or north exists; this must not have thrown or
        // wrapped around to the far side of the map.
        int lastChunk = world.chunksPerAxis - 1;
        assertFalse(world.isChunkDirty(world.chunkIndex(lastChunk, lastChunk)));
    }

    @Test
    void markDirtyIgnoresOutOfBoundsTiles() {
        World world = cleanWorld();
        world.markDirty(-5, 3);
        world.markDirty(world.size + 2, 3);
        for (int c = 0; c < world.chunkCount(); c++) {
            assertFalse(world.isChunkDirty(c), "out-of-bounds edits must not dirty anything");
        }
    }

    @Test
    void setHeightClampsReclassifiesAndDirties() {
        World world = cleanWorld();
        world.fertility[world.index(5, 5)] = 0.0f;

        world.setHeight(5, 5, 2.0f);
        assertEquals(2.0f, world.heightAt(5, 5), 0.0f);
        assertEquals(TileType.GRASS, world.typeAt(5, 5), "lowland with low fertility should be grass");
        assertTrue(world.isChunkDirty(world.chunkIndex(0, 0)));

        world.setHeight(5, 5, 999.0f);
        assertEquals(SimConfig.MAX_HEIGHT, world.heightAt(5, 5), 0.0f, "height must clamp to the configured ceiling");
        assertEquals(TileType.SNOW, world.typeAt(5, 5));

        world.setHeight(5, 5, -999.0f);
        assertEquals(SimConfig.MIN_HEIGHT, world.heightAt(5, 5), 0.0f, "height must clamp to the configured floor");
        assertEquals(TileType.DEEP_WATER, world.typeAt(5, 5));
    }

    @Test
    void setHeightWithNoActualChangeDoesNotDirtyAChunk() {
        World world = new World();
        float existing = world.heightAt(9, 9);
        for (int c = 0; c < world.chunkCount(); c++) {
            world.clearChunkDirty(c);
        }
        world.setHeight(9, 9, existing);
        for (int c = 0; c < world.chunkCount(); c++) {
            assertFalse(world.isChunkDirty(c), "a no-op edit should not schedule a mesh rebuild");
        }
    }

    @Test
    void heightClampedSamplesPastTheBorderWithoutThrowing() {
        World world = new World();
        assertEquals(world.heightAt(0, 0), world.heightClamped(-4, -4), 0.0f);
        int last = world.size - 1;
        assertEquals(world.heightAt(last, last), world.heightClamped(world.size + 3, world.size + 3), 0.0f);
    }
}
