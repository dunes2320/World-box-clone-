package com.worldbox.util;

import java.util.List;

/** Deterministic mulberry32 PRNG so a given seed always produces the same
 * world; also handy as a general-purpose gameplay RNG. Java's int math
 * already wraps to 32 bits, so this is a direct port of the JS version
 * without needing an explicit Math.imul-style helper. */
public class Rng implements java.io.Serializable {
  private int a;

  public Rng(long seed) {
    this.a = (int) seed;
  }

  private double rawDouble() {
    a += 0x6d2b79f5;
    int t = a;
    t = (t ^ (t >>> 15)) * (t | 1);
    t = (t + (t ^ (t >>> 7)) * (t | 61)) ^ t;
    int result = t ^ (t >>> 14);
    return (result & 0xFFFFFFFFL) / 4294967296.0;
  }

  public double next() { return rawDouble(); }
  public double range(double min, double max) { return min + rawDouble() * (max - min); }
  public int intRange(int min, int max) { return (int) Math.floor(min + rawDouble() * (max - min + 1)); }
  public boolean chance(double p) { return rawDouble() < p; }
  public <T> T pick(List<T> list) { return list.get((int) Math.floor(rawDouble() * list.size())); }
  public <T> T pick(T[] arr) { return arr[(int) Math.floor(rawDouble() * arr.length)]; }
}
