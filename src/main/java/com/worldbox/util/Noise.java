package com.worldbox.util;

/** Lightweight seeded 2D value noise + fBm. Not simplex-quality, but smooth
 * and fast enough for a heightmap/biome mask, with zero external deps. */
public class Noise {
  private final int seed;

  public Noise(int seed) {
    this.seed = seed;
  }

  private static double hash2(int x, int y, int seed) {
    int h = x * 374761393 + y * 668265263 + seed * 2147483647;
    h = (h ^ (h >>> 13)) * 1274126177;
    h = h ^ (h >>> 16);
    return (h & 0xFFFFFFFFL) / 4294967296.0;
  }

  private static double smooth(double t) { return t * t * (3 - 2 * t); }

  private double valueNoise2D(double x, double y, int seedOffset) {
    int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
    int x1 = x0 + 1, y1 = y0 + 1;
    double sx = smooth(x - x0), sy = smooth(y - y0);
    double n00 = hash2(x0, y0, seedOffset);
    double n10 = hash2(x1, y0, seedOffset);
    double n01 = hash2(x0, y1, seedOffset);
    double n11 = hash2(x1, y1, seedOffset);
    double ix0 = n00 + (n10 - n00) * sx;
    double ix1 = n01 + (n11 - n01) * sx;
    return ix0 + (ix1 - ix0) * sy;
  }

  public double fbm(double x, double y, int octaves, double lacunarity, double gain) {
    double amp = 1, freq = 1, sum = 0, norm = 0;
    for (int o = 0; o < octaves; o++) {
      sum += amp * valueNoise2D(x * freq, y * freq, seed + o * 101);
      norm += amp;
      amp *= gain;
      freq *= lacunarity;
    }
    return sum / norm; // 0..1
  }

  public double fbm(double x, double y) { return fbm(x, y, 4, 2, 0.5); }
}
