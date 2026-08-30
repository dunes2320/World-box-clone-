package com.worldbox.render;

import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;

import java.awt.image.BufferedImage;

/** Procedurally paints a small, classic-resolution (16px/tile) texture atlas
 * for terrain blocks - grass, dirt, stone, sand, water - entirely at
 * runtime, pixel by pixel, from deterministic hash noise. Nothing here is
 * copied from any existing texture pack: every tile is generated from a
 * base color plus a hand-picked speckle/grain pattern, the same "give each
 * block face a bit of grain" idea Minecraft's own textures use, not any
 * specific artwork. Built once at startup and reused for the life of the
 * app - painting sixteen thousand pixels is cheap next to everything else
 * simpleInitApp already does. */
public final class TerrainTextures {
  private TerrainTextures() {}

  public static final int TILE = 16;
  public static final int GRASS_TOP = 0;
  public static final int GRASS_SIDE = 1;
  public static final int DIRT = 2;
  public static final int STONE = 3;
  public static final int SAND = 4;
  public static final int WATER = 5;
  private static final int TILE_COUNT = 6;

  public static Texture2D buildAtlas() {
    BufferedImage img = new BufferedImage(TILE * TILE_COUNT, TILE, BufferedImage.TYPE_INT_ARGB);
    paintGrassTop(img, GRASS_TOP * TILE);
    paintGrassSide(img, GRASS_SIDE * TILE);
    paintSpeckle(img, DIRT * TILE, 0x7A5B3A, 0x5E4529, 0x8C6C46, 0.55f, 0xA11);
    paintStone(img, STONE * TILE);
    paintSpeckle(img, SAND * TILE, 0xD9C58A, 0xC7B172, 0xE6D6A0, 0.35f, 0xB22);
    paintWater(img, WATER * TILE);
    return toTexture(img);
  }

  /** UV span (u0, u1) for one atlas tile, in the shared horizontal-strip
   * layout every tile uses. */
  public static float u0(int tile) { return (float) tile / TILE_COUNT; }
  public static float u1(int tile) { return (float) (tile + 1) / TILE_COUNT; }

  /** A standalone (non-atlas) bark texture for tree trunks - vertical
   * ridge lines over a speckled brown base, so a log block reads as wood
   * grain rather than the same generic speckle as dirt. Full 0..1 UV, not
   * a tile slice, since trees are their own dedicated Geometry/Material
   * (see EntityRenderer), not part of the terrain atlas. */
  public static Texture2D buildLogTexture() {
    BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
    int base = 0x6B4A2E, dark = 0x543A24, light = 0x7E5A3A;
    for (int y = 0; y < TILE; y++) {
      for (int x = 0; x < TILE; x++) {
        float t = noise01(x, y, 0xD01);
        int c = t < 0.5f ? mix(base, dark, base, t * 2f) : mix(base, base, light, (t - 0.5f) * 2f);
        // vertical bark ridges: every few columns gets a consistently
        // darker strip the whole height of the tile, like real bark grain
        if ((x + (int) (noise01(0, y, 0xD02) * 2)) % 3 == 0) c = mix(c, dark, c, 0.5f);
        img.setRGB(x, y, c);
      }
    }
    return toTexture(img);
  }

  /** A standalone leaf texture - green blobby noise with a scatter of
   * darker "gap" pixels, so canopy blocks read as foliage clumps rather
   * than a flat green box. */
  public static Texture2D buildLeafTexture() {
    BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
    int base = 0x3F8A34, dark = 0x2C651F, light = 0x59A648;
    for (int y = 0; y < TILE; y++) {
      for (int x = 0; x < TILE; x++) {
        float t = noise01(x, y, 0xE01);
        int c = t < 0.5f ? mix(base, dark, base, t * 2f) : mix(base, base, light, (t - 0.5f) * 2f);
        if (noise01(x, y, 0xE02) < 0.1f) c = dark;
        img.setRGB(x, y, c);
      }
    }
    return toTexture(img);
  }

  /** A standalone wooden-plank texture for wood-framed buildings (houses,
   * huts, market stalls, business fronts) - horizontal board rows with a
   * seam line between each and speckled wood grain within a row, so a
   * building reads as built from timber rather than a solid color box. */
  public static Texture2D buildPlankTexture() {
    BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
    int base = 0x9C7444, dark = 0x7C5A34, light = 0xB18A56;
    int rowHeight = 4;
    for (int y = 0; y < TILE; y++) {
      boolean seam = y % rowHeight == 0;
      for (int x = 0; x < TILE; x++) {
        float t = noise01(x, y, 0xF01);
        int c = t < 0.5f ? mix(base, dark, base, t * 2f) : mix(base, base, light, (t - 0.5f) * 2f);
        if (seam) c = mix(c, dark, c, 0.55f);
        img.setRGB(x, y, c);
      }
    }
    return toTexture(img);
  }

  /** A standalone stone-brick texture for grander stone buildings (banks,
   * monuments, statues, military bases) - offset brick rows with a darker
   * mortar grid, distinct from the plain fractured-rock stone tile used
   * for terrain. */
  public static Texture2D buildBrickTexture() {
    BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
    int base = 0x9A9CA2, mortar = 0x616469;
    int brickH = 4, brickW = 8;
    for (int y = 0; y < TILE; y++) {
      int rowOffset = ((y / brickH) % 2 == 0) ? 0 : brickW / 2;
      boolean hMortar = y % brickH == 0;
      for (int x = 0; x < TILE; x++) {
        boolean vMortar = ((x + rowOffset) % brickW) == 0;
        float t = noise01(x, y, 0xF11);
        int c = mix(base, base, 0xACAEB3, t);
        if (hMortar || vMortar) c = mortar;
        img.setRGB(x, y, c);
      }
    }
    return toTexture(img);
  }

  // crisp, blocky pixels up close (MagFilter) but mip-mapped so distant
  // terrain doesn't shimmer/moire - the same combination every blocky
  // voxel game uses, since a texture this small aliases badly without it
  /** A standalone roof-shingle texture - staggered darker shingle rows
   * over a warm red-brown base, independent of the wall material (plank
   * or brick) a building otherwise uses, the same way a real roof is a
   * different material than the walls under it. */
  public static Texture2D buildRoofTexture() {
    BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
    int base = 0x8B3A2E, dark = 0x6E2C22, light = 0xA34A3A;
    int rowHeight = 2;
    for (int y = 0; y < TILE; y++) {
      int rowOffset = ((y / rowHeight) % 2 == 0) ? 0 : 2;
      boolean seam = y % rowHeight == 0;
      for (int x = 0; x < TILE; x++) {
        boolean shingleEdge = ((x + rowOffset) % 4) == 0;
        float t = noise01(x, y, 0xF21);
        int c = t < 0.5f ? mix(base, dark, base, t * 2f) : mix(base, base, light, (t - 0.5f) * 2f);
        if (seam || shingleEdge) c = dark;
        img.setRGB(x, y, c);
      }
    }
    return toTexture(img);
  }

  /** A standalone cobblestone texture for building foundations - irregular
   * grey stone lumps with dark mortar gaps between them, distinct from
   * both the fractured-rock terrain stone tile and the mortared-brick
   * texture, so a foundation course reads as "loose fitted stones" the
   * way a real cobblestone plinth does. */
  public static Texture2D buildCobbleTexture() {
    BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
    int base = 0x7D7F84, dark = 0x55575C, light = 0x93959A;
    for (int y = 0; y < TILE; y++) {
      for (int x = 0; x < TILE; x++) {
        // a coarse per-lump id (4x4 cells) so each "stone" gets one
        // consistent shade across several pixels instead of pixel noise,
        // then a thin dark seam wherever the lump id changes
        int lumpX = x / 4, lumpY = y / 4;
        float t = noise01(lumpX, lumpY, 0xC71);
        int c = t < 0.5f ? mix(base, dark, base, t * 2f) : mix(base, base, light, (t - 0.5f) * 2f);
        boolean seam = (x % 4 == 0) || (y % 4 == 0);
        if (seam && noise01(x, y, 0xC72) < 0.8f) c = dark;
        img.setRGB(x, y, c);
      }
    }
    return toTexture(img);
  }

  /** A standalone quartz-block texture for civic/government trim (entrance
   * columns, cornices) - a pale, faintly warm stone with fine vertical
   * veining, reading as "polished" next to the rough terrain stone and
   * mortared brick tiles. */
  public static Texture2D buildQuartzTexture() {
    BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
    int base = 0xE9E4D8, dark = 0xCFC8B6, light = 0xF7F3EA;
    for (int y = 0; y < TILE; y++) {
      for (int x = 0; x < TILE; x++) {
        float t = noise01(x, y, 0xD71);
        int c = t < 0.5f ? mix(base, dark, base, t * 2f) : mix(base, base, light, (t - 0.5f) * 2f);
        // faint vertical veins every few columns, like real quartz
        if ((x + (int) (noise01(0, y, 0xD72) * 2)) % 5 == 0 && noise01(x, y, 0xD73) < 0.5f) c = mix(c, dark, c, 0.4f);
        img.setRGB(x, y, c);
      }
    }
    return toTexture(img);
  }

  /** A standalone door texture - a darker, richer wood tone than the wall
   * planks around it (so a doorway reads as an actual closed door instead
   * of just another wall panel), with two raised-look inset rectangles
   * (real doors are paneled, not one flat slab) and a small bright
   * doorknob dot. */
  public static Texture2D buildDoorTexture() {
    BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
    int base = 0x5A3A22, dark = 0x432A18, light = 0x6B4A2E, panel = 0x4E301C;
    for (int y = 0; y < TILE; y++) {
      for (int x = 0; x < TILE; x++) {
        float t = noise01(x, y, 0xA31);
        int c = t < 0.5f ? mix(base, dark, base, t * 2f) : mix(base, base, light, (t - 0.5f) * 2f);
        // two inset panels (upper/lower), each with a dark border - the
        // "raised frame" look of a real paneled door
        boolean upperPanel = x >= 3 && x <= 12 && y >= 2 && y <= 6;
        boolean lowerPanel = x >= 3 && x <= 12 && y >= 9 && y <= 13;
        boolean panelBorder = (upperPanel && (x == 3 || x == 12 || y == 2 || y == 6))
            || (lowerPanel && (x == 3 || x == 12 || y == 9 || y == 13));
        if (panelBorder) c = dark;
        else if (upperPanel || lowerPanel) c = panel;
        img.setRGB(x, y, c);
      }
    }
    // doorknob
    img.setRGB(11, 8, 0xD9C25A);
    return toTexture(img);
  }

  /** A standalone window-glass texture - pale translucent blue-white panes
   * split by a dark wooden cross mullion, like a real four-pane window.
   * Meant to be used on a transparent material (see EntityRenderer), so
   * alpha here actually matters, not just RGB. */
  public static Texture2D buildGlassTexture() {
    BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
    int pane = 0xBFE0EE, paneLight = 0xD8EEF6, mullion = 0x5A3A22;
    int alpha = 210;
    for (int y = 0; y < TILE; y++) {
      for (int x = 0; x < TILE; x++) {
        boolean crossbar = x == 7 || x == 8 || y == 7 || y == 8;
        int rgb = crossbar ? mullion : (noise01(x, y, 0xA41) < 0.5f ? pane : paneLight);
        int a = crossbar ? 255 : alpha;
        img.setRGB(x, y, (a << 24) | rgb);
      }
    }
    return toTexture(img);
  }

  /** A tiny procedural sky cubemap - a plain sky-blue top face, a soft
   * blue-to-pale-horizon gradient on the four side faces, and a muted
   * ground tone on the bottom - built once and reused as water's EnvMap
   * (see VoxelChunkRenderer) so the sea reflects a believable sky instead
   * of being lit with no reflection at all. Not a real-time environment
   * capture (this world has no reflective geometry that itself needs to
   * show up in the reflection - a static sky is what a real body of
   * water reflects the vast majority of the time anyway), so a handful of
   * flat/gradient faces is enough to read as "the sky is bouncing off
   * this" without the cost of a live reflection camera pass. */
  public static com.jme3.texture.TextureCubeMap buildSkyCubeMap() {
    int size = 16;
    int skyTop = 0x5B93D6, horizon = 0x9DC0DE, ground = 0x596B57;
    java.util.ArrayList<java.nio.ByteBuffer> faces = new java.util.ArrayList<>();
    faces.add(gradientFace(size, skyTop, horizon)); // +X
    faces.add(gradientFace(size, skyTop, horizon)); // -X
    faces.add(solidFace(size, skyTop));              // +Y (up)
    faces.add(solidFace(size, ground));              // -Y (down)
    faces.add(gradientFace(size, skyTop, horizon)); // +Z
    faces.add(gradientFace(size, skyTop, horizon)); // -Z
    Image img = new Image(Image.Format.RGBA8, size, size, 6, faces, com.jme3.texture.image.ColorSpace.sRGB);
    com.jme3.texture.TextureCubeMap cubeMap = new com.jme3.texture.TextureCubeMap(img);
    cubeMap.setMagFilter(Texture.MagFilter.Bilinear);
    cubeMap.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
    cubeMap.setWrap(Texture.WrapMode.EdgeClamp);
    return cubeMap;
  }

  private static java.nio.ByteBuffer gradientFace(int size, int topRgb, int bottomRgb) {
    java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(size * size * 4);
    for (int y = 0; y < size; y++) {
      float t = y / (float) (size - 1);
      int c = mix(topRgb, topRgb, bottomRgb, t);
      byte r = (byte) ((c >> 16) & 0xFF), g = (byte) ((c >> 8) & 0xFF), b = (byte) (c & 0xFF);
      for (int x = 0; x < size; x++) buf.put(r).put(g).put(b).put((byte) 255);
    }
    buf.flip();
    return buf;
  }

  private static java.nio.ByteBuffer solidFace(int size, int rgb) {
    java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(size * size * 4);
    byte r = (byte) ((rgb >> 16) & 0xFF), g = (byte) ((rgb >> 8) & 0xFF), b = (byte) (rgb & 0xFF);
    for (int i = 0; i < size * size; i++) buf.put(r).put(g).put(b).put((byte) 255);
    buf.flip();
    return buf;
  }

  private static Texture2D toTexture(BufferedImage img) {
    Image jmeImage = new AWTLoader().load(img, false);
    Texture2D tex = new Texture2D(jmeImage);
    tex.setMagFilter(Texture.MagFilter.Nearest);
    tex.setMinFilter(Texture.MinFilter.Trilinear);
    tex.setWrap(Texture.WrapMode.Repeat);
    return tex;
  }

  // --- pixel generation -------------------------------------------------

  private static int hash(int x, int y, int salt) {
    int h = x * 374761393 + y * 668265263 + salt * 2147483647;
    h = (h ^ (h >>> 13)) * 1274126177;
    return h ^ (h >>> 16);
  }

  private static float noise01(int x, int y, int salt) {
    return (hash(x, y, salt) & 0xFFFF) / 65535f;
  }

  private static int mix(int base, int a, int b, float t) {
    int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
    int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
    int r = (int) (ar + (br - ar) * t), g = (int) (ag + (bg - ag) * t), bl = (int) (ab + (bb - ab) * t);
    return 0xFF000000 | (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(bl);
  }

  private static int clampByte(int v) { return Math.max(0, Math.min(255, v)); }

  /** A base color speckled between a darker and lighter variant, plus a
   * sparse scatter of small "grain" flecks - the generic grain most solid
   * blocks (dirt, sand, stone) share, parameterized per block. */
  private static void paintSpeckle(BufferedImage img, int ox, int base, int dark, int light, float grainChance, int salt) {
    for (int y = 0; y < TILE; y++) {
      for (int x = 0; x < TILE; x++) {
        float t = noise01(x, y, salt);
        int c = t < 0.5f ? mix(base, dark, base, t * 2f) : mix(base, base, light, (t - 0.5f) * 2f);
        if (noise01(x, y, salt + 1) < grainChance * 0.18f) c = dark;
        img.setRGB(ox + x, y, c);
      }
    }
  }

  private static void paintGrassTop(BufferedImage img, int ox) {
    int base = 0x4E9C3F, dark = 0x3D7E2F, light = 0x64B454;
    paintSpeckle(img, ox, base, dark, light, 0.4f, 0x910);
    // a handful of single-pixel "blade tuft" flecks in a third, brighter
    // green so the top doesn't read as pure uniform speckle
    for (int y = 0; y < TILE; y++) {
      for (int x = 0; x < TILE; x++) {
        if (noise01(x, y, 0x933) < 0.06f) img.setRGB(ox + x, y, 0xFF77C763);
      }
    }
  }

  private static void paintGrassSide(BufferedImage img, int ox) {
    int dirtBase = 0x7A5B3A, dirtDark = 0x5E4529, dirtLight = 0x8C6C46;
    int grassBase = 0x4E9C3F, grassDark = 0x3D7E2F, grassLight = 0x64B454;
    for (int y = 0; y < TILE; y++) {
      for (int x = 0; x < TILE; x++) {
        // a jagged (not straight) grass/dirt boundary, varying a couple of
        // rows per column so it reads as torn grass rather than a ruler-
        // straight cutoff
        int boundary = 3 + (int) (noise01(x, 99, 0x744) * 3);
        boolean grass = y < boundary;
        int base = grass ? grassBase : dirtBase, dark = grass ? grassDark : dirtDark, light = grass ? grassLight : dirtLight;
        float t = noise01(x, y, 0x755);
        int c = t < 0.5f ? mix(base, dark, base, t * 2f) : mix(base, base, light, (t - 0.5f) * 2f);
        if (grass && y == boundary - 1 && noise01(x, y, 0x766) < 0.4f) c = dirtDark; // a few dirt crumbs right at the seam
        img.setRGB(ox + x, y, c);
      }
    }
  }

  private static void paintStone(BufferedImage img, int ox) {
    int base = 0x8B8F96, dark = 0x6E7278, light = 0x9EA2A8;
    paintSpeckle(img, ox, base, dark, light, 0.5f, 0xA33);
    // occasional darker "crack" pixel pairs and a rare lighter mineral
    // glint, both single pixels so they read as texture, not a pattern
    for (int y = 0; y < TILE; y++) {
      for (int x = 0; x < TILE; x++) {
        float n = noise01(x, y, 0xA44);
        if (n < 0.03f) img.setRGB(ox + x, y, 0xFF4F5257);
        else if (n > 0.985f) img.setRGB(ox + x, y, 0xFFC4C8CE);
      }
    }
  }

  private static void paintWater(BufferedImage img, int ox) {
    int base = 0x2E63A8, dark = 0x1E4A85, light = 0x4B84C9;
    for (int y = 0; y < TILE; y++) {
      for (int x = 0; x < TILE; x++) {
        // soft diagonal bands read as a ripple pattern even static, and
        // combine with the real per-frame vertex-wave animation
        // (updateWaterAnimation) already moving the mesh itself
        float band = (float) (Math.sin((x + y) * 0.7) * 0.5 + 0.5);
        float n = noise01(x, y, 0xC11) * 0.3f + band * 0.7f;
        int c = n < 0.5f ? mix(base, dark, base, n * 2f) : mix(base, base, light, (n - 0.5f) * 2f);
        img.setRGB(ox + x, y, c);
      }
    }
  }
}
