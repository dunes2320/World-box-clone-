package com.game.sim;

import java.util.Random;

/**
 * 2D simplex noise, written here rather than pulled in as a dependency.
 *
 * <p>Seeded from a {@link Random} permutation shuffle, so two instances built
 * from the same seed produce bit-identical output forever - which is what
 * lets world generation be replayed from a seed alone.
 */
public final class Noise {

    /** Skew/unskew factors that map the square grid onto a simplex (triangular) grid. */
    private static final float F2 = 0.5f * ((float) Math.sqrt(3.0) - 1.0f);
    private static final float G2 = (3.0f - (float) Math.sqrt(3.0)) / 6.0f;

    /**
     * The 8 gradient directions a lattice corner can pull toward. Using a
     * power-of-two count means the hash can be masked instead of divided.
     */
    private static final float[][] GRAD = {
        {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
    };

    /**
     * Doubled permutation table. Doubling it means index lookups near the top
     * of the table can add without wrapping, which removes a modulo from the
     * inner loop.
     */
    private final int[] perm = new int[512];

    public Noise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            p[i] = i;
        }
        // Fisher-Yates with a seeded Random: deterministic given the seed.
        Random random = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = p[i];
            p[i] = p[j];
            p[j] = swap;
        }
        for (int i = 0; i < 512; i++) {
            perm[i] = p[i & 255];
        }
    }

    /** Raw noise in roughly [-1, 1]. */
    public float noise(float x, float y) {
        // Skew the input point into simplex-space to find which cell it is in.
        float skew = (x + y) * F2;
        int i = fastFloor(x + skew);
        int j = fastFloor(y + skew);

        // Unskew the cell origin back to normal space and take the offset.
        float unskew = (i + j) * G2;
        float x0 = x - (i - unskew);
        float y0 = y - (j - unskew);

        // A skewed square splits into two triangles; work out which one we are
        // in so we know the order to walk the three corners.
        int i1 = x0 > y0 ? 1 : 0;
        int j1 = x0 > y0 ? 0 : 1;

        float x1 = x0 - i1 + G2;
        float y1 = y0 - j1 + G2;
        float x2 = x0 - 1.0f + 2.0f * G2;
        float y2 = y0 - 1.0f + 2.0f * G2;

        int ii = i & 255;
        int jj = j & 255;
        int g0 = perm[ii + perm[jj]] & 7;
        int g1 = perm[ii + i1 + perm[jj + j1]] & 7;
        int g2 = perm[ii + 1 + perm[jj + 1]] & 7;

        float n0 = corner(x0, y0, g0);
        float n1 = corner(x1, y1, g1);
        float n2 = corner(x2, y2, g2);

        // 70 is the standard normalisation constant for 2D simplex; it pulls
        // the summed corner contributions into approximately [-1, 1].
        return 70.0f * (n0 + n1 + n2);
    }

    /**
     * One corner's contribution, faded out by a radially symmetric kernel so
     * corners beyond the simplex's radius of influence contribute nothing.
     */
    private static float corner(float x, float y, int gradIndex) {
        float t = 0.5f - x * x - y * y;
        if (t < 0.0f) {
            return 0.0f;
        }
        t *= t;
        float[] g = GRAD[gradIndex];
        return t * t * (g[0] * x + g[1] * y);
    }

    /**
     * Fractal Brownian motion: several octaves of {@link #noise} summed at
     * doubling frequency and shrinking amplitude. Normalised by the total
     * amplitude so the result stays in roughly [-1, 1] whatever the octave
     * count.
     */
    public float fbm(float x, float y, int octaves, float lacunarity, float gain) {
        float sum = 0.0f;
        float amplitude = 1.0f;
        float frequency = 1.0f;
        float totalAmplitude = 0.0f;
        for (int i = 0; i < octaves; i++) {
            sum += noise(x * frequency, y * frequency) * amplitude;
            totalAmplitude += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return totalAmplitude == 0.0f ? 0.0f : sum / totalAmplitude;
    }

    /** Floor that stays correct for negatives without a branch into Math.floor. */
    private static int fastFloor(float v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
