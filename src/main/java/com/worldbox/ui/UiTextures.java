package com.worldbox.ui;

import com.jme3.math.ColorRGBA;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/** Generates small rounded-rect chip textures at runtime (no image assets,
 * no extra dependency) for use as a Lemur nine-patch (TbtQuadBackgroundComponent)
 * background on small, fixed-size icon buttons - the difference between "a
 * gray square" and a control that reads as a soft, bordered chip. The big
 * structural panels (top bar/toolbar/side panel) use a plain flat
 * QuadBackgroundComponent instead (see panelBackground()) - nine-patch
 * backgrounds wide enough to span one of those panels reliably paint over
 * their own text/icon children (confirmed by direct A/B testing), so
 * nine-patch is reserved for genuinely small elements where it's proven
 * safe. Lemur's built-in "glass" style would give rounded panels without
 * any of this, but it hard-crashes on this project (needs a Groovy
 * scripting engine dependency this project doesn't have, see the reverted
 * attempt in the V8 UI pass) - drawing textures ourselves sidesteps that. */
public final class UiTextures {
  private UiTextures() {}

  public static final int CHIP_MARGIN = 8;
  private static final int CHIP_SIZE = 24;

  /** Packs a BufferedImage into a jME Texture2D - shared by every runtime-
   * drawn texture in this project (rounded panels here, icon glyphs in
   * IconTextures). */
  static Texture2D toTexture(BufferedImage img) {
    int size = img.getWidth();
    // jME texture rows run bottom-to-top (matches GL texture coords),
    // BufferedImage rows run top-to-bottom - flip while packing or the
    // image renders upside down
    ByteBuffer buf = BufferUtils.createByteBuffer(size * img.getHeight() * 4);
    for (int y = img.getHeight() - 1; y >= 0; y--) {
      for (int x = 0; x < size; x++) {
        int argb = img.getRGB(x, y);
        buf.put((byte) ((argb >> 16) & 0xFF));
        buf.put((byte) ((argb >> 8) & 0xFF));
        buf.put((byte) (argb & 0xFF));
        buf.put((byte) ((argb >> 24) & 0xFF));
      }
    }
    buf.flip();
    Image jmeImage = new Image(Image.Format.RGBA8, size, img.getHeight(), buf, ColorSpace.sRGB);
    Texture2D tex = new Texture2D(jmeImage);
    tex.setMagFilter(Texture.MagFilter.Bilinear);
    tex.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
    tex.setWrap(Texture.WrapMode.EdgeClamp);
    return tex;
  }

  static BufferedImage newCanvas(int size) {
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.dispose();
    return img;
  }

  private static Texture2D roundedRect(int size, int margin, ColorRGBA fill, ColorRGBA border, int borderWidth) {
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    int arc = margin * 2;
    g.setColor(awt(fill));
    g.fillRoundRect(0, 0, size, size, arc, arc);
    if (borderWidth > 0) {
      g.setStroke(new java.awt.BasicStroke(borderWidth));
      g.setColor(awt(border));
      int half = borderWidth / 2;
      g.drawRoundRect(half, half, size - borderWidth, size - borderWidth, arc, arc);
    }
    g.dispose();
    return toTexture(img);
  }

  static Color awt(ColorRGBA c) {
    return new Color(clamp(c.r), clamp(c.g), clamp(c.b), clamp(c.a));
  }

  private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

  private static Texture2D buttonActiveTex, iconSlotTex;

  private static com.simsilica.lemur.component.TbtQuadBackgroundComponent nine(
      Texture2D tex, int margin) {
    return com.simsilica.lemur.component.TbtQuadBackgroundComponent.create(
        tex, 1f, margin, margin, margin, margin, 0f, false);
  }

  /** A flat, near-opaque panel background for the big structural panels
   * (top bar / toolbar / side panel / toast). This is deliberately NOT the
   * nine-patch rounded/bordered texture used for small chips below - a wide
   * TbtQuadBackgroundComponent (proven by direct A/B testing: same colors,
   * same alpha, only the component type changed) reliably paints OVER its
   * own text/icon children once it gets wide enough, which is exactly what
   * made the top bar's title and stat text unreadable. A plain
   * QuadBackgroundComponent doesn't have that bug at any width, at the cost
   * of square corners on the big panels only - small chips keep the rounded
   * nine-patch look since that's proven safe at chip width. */
  public static com.simsilica.lemur.component.QuadBackgroundComponent panelBackground() {
    return new com.simsilica.lemur.component.QuadBackgroundComponent(
        new ColorRGBA(0.07f, 0.08f, 0.11f, 0.97f));
  }

  /** The active/selected variant - an accent-tinted chip, so "this is the
   * current tool/tab" is a real background state instead of only a
   * change in text color. */
  public static com.simsilica.lemur.component.TbtQuadBackgroundComponent activeButtonBackground() {
    if (buttonActiveTex == null) {
      buttonActiveTex = roundedRect(CHIP_SIZE, CHIP_MARGIN,
          new ColorRGBA(0.31f, 0.64f, 1f, 0.28f),
          new ColorRGBA(0.45f, 0.72f, 1f, 0.75f),
          1);
    }
    return nine(buttonActiveTex, CHIP_MARGIN);
  }

  /** A pill for the top-bar stat readout (population, nation count, date).
   * Flat, not nine-patch, for the same reason as panelBackground() above -
   * this chip's width grows with its text (year counters, population) and
   * can get wide enough to hit the same paint-over-its-own-text bug. */
  public static com.simsilica.lemur.component.QuadBackgroundComponent chipBackground() {
    return new com.simsilica.lemur.component.QuadBackgroundComponent(
        new ColorRGBA(1f, 1f, 1f, 0.09f));
  }

  /** A permanent (not just active-state) soft dark slot behind every icon
   * button - icon-only buttons with no background at all were the actual
   * cause of "can't see the icons," since a near-white glyph only reads
   * against a guaranteed-dark backdrop, not whatever's rendered underneath
   * it. Distinct from activeButtonBackground() (accent blue) so selection
   * still stands out. */
  public static com.simsilica.lemur.component.TbtQuadBackgroundComponent iconSlotBackground() {
    if (iconSlotTex == null) {
      iconSlotTex = roundedRect(CHIP_SIZE, CHIP_MARGIN,
          new ColorRGBA(0f, 0f, 0f, 0.3f),
          new ColorRGBA(1f, 1f, 1f, 0.1f),
          1);
    }
    return nine(iconSlotTex, CHIP_MARGIN);
  }
}
