package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NoiseTest {

    @Test
    void sameSeedProducesIdenticalOutput() {
        Noise a = new Noise(12345L);
        Noise b = new Noise(12345L);
        for (int i = 0; i < 500; i++) {
            float x = i * 0.37f;
            float y = i * -0.21f;
            assertEquals(a.noise(x, y), b.noise(x, y), 0.0f,
                "same seed must give bit-identical noise at (" + x + ", " + y + ")");
        }
    }

    @Test
    void differentSeedsProduceDifferentOutput() {
        Noise a = new Noise(1L);
        Noise b = new Noise(2L);
        int differences = 0;
        for (int i = 0; i < 200; i++) {
            float x = i * 0.41f;
            float y = i * 0.29f;
            if (a.noise(x, y) != b.noise(x, y)) {
                differences++;
            }
        }
        assertTrue(differences > 150,
            "different seeds should disagree nearly everywhere, but only differed " + differences + "/200 times");
    }

    @Test
    void outputStaysInExpectedRange() {
        Noise noise = new Noise(99L);
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int z = 0; z < 200; z++) {
            for (int x = 0; x < 200; x++) {
                float v = noise.noise(x * 0.13f, z * 0.13f);
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        assertTrue(min >= -1.05f && max <= 1.05f,
            "simplex output should sit in roughly [-1, 1], got [" + min + ", " + max + "]");
        // A field that never gets anywhere near the extremes would mean the
        // gradient/normalisation constants are wrong, not just conservative.
        assertTrue(max > 0.4f && min < -0.4f,
            "noise should actually use its range, got [" + min + ", " + max + "]");
    }

    @Test
    void noiseIsContinuous() {
        // Neighbouring samples must not jump: a discontinuity here would show
        // up as visible seams across the terrain.
        Noise noise = new Noise(7L);
        float previous = noise.noise(0.0f, 0.0f);
        for (int i = 1; i < 1000; i++) {
            float v = noise.noise(i * 0.01f, 0.0f);
            assertTrue(Math.abs(v - previous) < 0.25f,
                "noise jumped by " + Math.abs(v - previous) + " between adjacent samples at step " + i);
            previous = v;
        }
    }

    @Test
    void fbmStaysNormalised() {
        Noise noise = new Noise(4242L);
        for (int i = 0; i < 400; i++) {
            float v = noise.fbm(i * 0.05f, i * 0.03f, 5, 2.0f, 0.5f);
            assertTrue(v >= -1.05f && v <= 1.05f, "fbm escaped [-1, 1] with " + v);
        }
    }

    @Test
    void fbmWithZeroOctavesDoesNotDivideByZero() {
        Noise noise = new Noise(1L);
        assertNotEquals(Float.NaN, noise.fbm(1.0f, 1.0f, 0, 2.0f, 0.5f));
        assertEquals(0.0f, noise.fbm(1.0f, 1.0f, 0, 2.0f, 0.5f), 0.0f);
    }
}
