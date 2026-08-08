// Lightweight seeded 2D value noise + fBm. Not simplex-quality, but smooth
// and fast enough for a heightmap/biome mask, with zero external deps.

function hash2(x, y, seed) {
  let h = Math.imul(x, 374761393) + Math.imul(y, 668265263) + Math.imul(seed, 2147483647);
  h = (h ^ (h >>> 13)) * 1274126177;
  h = h ^ (h >>> 16);
  return ((h >>> 0) / 4294967296);
}

function smooth(t) { return t * t * (3 - 2 * t); }

function valueNoise2D(x, y, seed) {
  const x0 = Math.floor(x), y0 = Math.floor(y);
  const x1 = x0 + 1, y1 = y0 + 1;
  const sx = smooth(x - x0), sy = smooth(y - y0);
  const n00 = hash2(x0, y0, seed);
  const n10 = hash2(x1, y0, seed);
  const n01 = hash2(x0, y1, seed);
  const n11 = hash2(x1, y1, seed);
  const ix0 = n00 + (n10 - n00) * sx;
  const ix1 = n01 + (n11 - n01) * sx;
  return ix0 + (ix1 - ix0) * sy;
}

export function makeFbm(seed) {
  return function fbm(x, y, octaves = 4, lacunarity = 2, gain = 0.5) {
    let amp = 1, freq = 1, sum = 0, norm = 0;
    for (let o = 0; o < octaves; o++) {
      sum += amp * valueNoise2D(x * freq, y * freq, seed + o * 101);
      norm += amp;
      amp *= gain;
      freq *= lacunarity;
    }
    return sum / norm; // 0..1
  };
}
