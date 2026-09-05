package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerraformTest {

    /** A flat world at a known height, with all chunks clean, for predictable assertions. */
    private static World flatWorld(float height, float fertility) {
        World world = new World();
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = height;
            world.fertility[i] = fertility;
            world.tileType[i] = TileType.fromTerrain(height, fertility);
        }
        for (int c = 0; c < world.chunkCount(); c++) {
            world.clearChunkDirty(c);
        }
        return world;
    }

    @Test
    void raiseLiftsTheCentreMostAndTheRimNotAtAll() {
        World world = flatWorld(1.0f, 0.0f);
        Terraform.raise(world, 64, 64, 6, 1.0f);

        float centre = world.heightAt(64, 64);
        float mid = world.heightAt(67, 64);
        float rim = world.heightAt(70, 64);

        assertTrue(centre > mid, "centre should rise more than the midpoint");
        assertTrue(mid > 1.0f, "midpoint should still rise somewhat");
        assertEquals(1.0f, rim, 1e-6f, "the rim must not move, or strokes leave a hard lip");
    }

    @Test
    void lowerIsTheExactInverseOfRaise() {
        World a = flatWorld(3.0f, 0.0f);
        World b = flatWorld(3.0f, 0.0f);
        Terraform.raise(a, 40, 40, 5, 0.8f);
        Terraform.lower(b, 40, 40, 5, 0.8f);

        for (int z = 34; z <= 46; z++) {
            for (int x = 34; x <= 46; x++) {
                float up = a.heightAt(x, z) - 3.0f;
                float down = 3.0f - b.heightAt(x, z);
                assertEquals(up, down, 1e-5f, "raise and lower disagree at (" + x + ", " + z + ")");
            }
        }
    }

    @Test
    void brushNeverTouchesTilesOutsideItsRadius() {
        World world = flatWorld(2.0f, 0.0f);
        int radius = 4;
        Terraform.raise(world, 60, 60, radius, 1.0f);

        for (int z = 0; z < world.size; z++) {
            for (int x = 0; x < world.size; x++) {
                float dx = x - 60;
                float dz = z - 60;
                boolean inside = dx * dx + dz * dz <= (float) radius * radius;
                if (!inside) {
                    assertEquals(2.0f, world.heightAt(x, z), 1e-6f,
                        "tile (" + x + ", " + z + ") outside the brush was modified");
                }
            }
        }
    }

    @Test
    void brushClipsAtTheWorldEdgeWithoutWrappingOrThrowing() {
        World world = flatWorld(1.0f, 0.0f);
        Terraform.raise(world, 0, 0, 8, 1.0f);

        assertTrue(world.heightAt(0, 0) > 1.0f, "the corner itself should still be raised");
        // If the loop had wrapped, the opposite corner would have moved.
        int last = world.size - 1;
        assertEquals(1.0f, world.heightAt(last, last), 1e-6f, "the far corner must be untouched");
    }

    @Test
    void raisingTheSeabedTurnsItIntoRealLand() {
        World world = flatWorld(-1.0f, 0.0f);
        assertEquals(TileType.SHALLOW_WATER, world.typeAt(50, 50));

        // Three strokes at full strength lift the centre from -1 to +2, which
        // is clear of the sand line but well short of the hill line.
        for (int i = 0; i < 3; i++) {
            Terraform.raise(world, 50, 50, 4, 1.0f);
        }

        assertTrue(world.heightAt(50, 50) > SimConfig.SAND_LEVEL);
        assertFalse(TileType.isWater(world.typeAt(50, 50)), "raised seabed should no longer be water");
        assertEquals(TileType.GRASS, world.typeAt(50, 50));
    }

    @Test
    void terraformMarksAffectedChunksDirty() {
        World world = flatWorld(1.0f, 0.0f);
        Terraform.raise(world, 20, 20, 3, 1.0f);
        assertTrue(world.isChunkDirty(world.chunkIndex(1, 1)), "the edited chunk must be scheduled for a rebuild");

        boolean farChunkClean = !world.isChunkDirty(world.chunkIndex(7, 7));
        assertTrue(farChunkClean, "an untouched chunk should not be rebuilt");
    }

    @Test
    void addWaterSinksLandButLeavesExistingWaterAlone() {
        World world = flatWorld(3.0f, 0.0f);
        float deepBefore = -4.0f;
        world.setHeight(10, 10, deepBefore);

        for (int i = 0; i < 20; i++) {
            Terraform.addWater(world, 50, 50, 5, 0.5f);
        }
        assertTrue(TileType.isWater(world.typeAt(50, 50)), "flooded ground should end up as water");

        // A tile far outside the brush, already deep, must not be dragged up.
        assertEquals(deepBefore, world.heightAt(10, 10), 1e-6f);
    }

    @Test
    void addWaterStopsAtItsTargetDepthInsteadOfDiggingForever() {
        World world = flatWorld(2.0f, 0.0f);
        for (int i = 0; i < 200; i++) {
            Terraform.addWater(world, 64, 64, 3, 0.5f);
        }
        float depth = world.heightAt(64, 64);
        assertTrue(depth > SimConfig.MIN_HEIGHT + 1.0f,
            "the water brush should settle at a shallow depth, not excavate to the floor (got " + depth + ")");
    }

    @Test
    void addForestOnlyPlantsOnGrass() {
        World world = flatWorld(2.0f, 0.0f);
        assertEquals(TileType.GRASS, world.typeAt(64, 64));
        world.setHeight(66, 64, 8.0f);
        assertEquals(TileType.MOUNTAIN, world.typeAt(66, 64));

        Terraform.addForest(world, 64, 64, 5);

        assertEquals(TileType.FOREST, world.typeAt(64, 64), "grass should become forest");
        assertEquals(TileType.MOUNTAIN, world.typeAt(66, 64), "mountain must not sprout forest");
    }

    @Test
    void radiusIsClampedToTheConfiguredRange() {
        assertEquals(SimConfig.MIN_BRUSH_RADIUS, Terraform.clampRadius(0));
        assertEquals(SimConfig.MIN_BRUSH_RADIUS, Terraform.clampRadius(-10));
        assertEquals(SimConfig.MAX_BRUSH_RADIUS, Terraform.clampRadius(9999));
        assertEquals(5, Terraform.clampRadius(5));
    }

    @Test
    void heightStaysWithinBoundsUnderRepeatedStrokes() {
        World world = flatWorld(0.0f, 0.0f);
        for (int i = 0; i < 500; i++) {
            Terraform.raise(world, 64, 64, 3, 5.0f);
        }
        assertEquals(SimConfig.MAX_HEIGHT, world.heightAt(64, 64), 1e-6f);

        for (int i = 0; i < 500; i++) {
            Terraform.lower(world, 64, 64, 3, 5.0f);
        }
        assertEquals(SimConfig.MIN_HEIGHT, world.heightAt(64, 64), 1e-6f);
    }
}
