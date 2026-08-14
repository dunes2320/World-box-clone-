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

/** Generates small rounded-rect panel textures at runtime (no image assets,
 * no extra dependency) for use as a Lemur nine-patch (TbtQuadBackgroundComponent)
 * background - the difference between "a gray rectangle" and a panel that
 * actually reads as a soft, bordered surface. Every prior HUD pass here
 * was limited to flat-color QuadBackgroundComponent (hard square corners,
 * no border) because the one real upgrade path - Lemur's built-in "glass"
 * style - hard-crashes on this project (it needs a Groovy scripting engine
 * dependency this project doesn't have, see the reverted attempt in the
 * V8 UI pass). Drawing a texture ourselves sidesteps that entirely. */
public final class UiTextures {
  private UiTextures() {}

  public static final int MARGIN = 14;
  public static final int CHIP_MARGIN = 8;
  private static final int SIZE = 48;
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

  private static Texture2D panelTex, buttonActiveTex, chipTex;

  private static com.simsilica.lemur.component.TbtQuadBackgroundComponent nine(
      Texture2D tex, int margin) {
    return com.simsilica.lemur.component.TbtQuadBackgroundComponent.create(
        tex, 1f, margin, margin, margin, margin, 0f, false);
  }

  /** A fresh nine-patch background component for a structural panel
   * (top bar / toolbar / side panel) - soft rounded corners and a faint
   * accent-tinted border over a dark glass fill. Every caller gets its
   * own component instance (a GuiComponent can only attach to one
   * Panel) but they all share the same underlying texture. */
  public static com.simsilica.lemur.component.TbtQuadBackgroundComponent panelBackground() {
    if (panelTex == null) {
      panelTex = roundedRect(SIZE, MARGIN,
          new ColorRGBA(0.09f, 0.105f, 0.14f, 0.86f),
          new ColorRGBA(0.36f, 0.44f, 0.56f, 0.55f),
          2);
    }
    return nine(panelTex, MARGIN);
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

  /** A tiny pill for the top-bar stat readouts (population, nation
   * count, date) - a dashboard-style chip instead of plain inline text
   * mashed together. */
  public static com.simsilica.lemur.component.TbtQuadBackgroundComponent chipBackground() {
    if (chipTex == null) {
      chipTex = roundedRect(CHIP_SIZE, CHIP_MARGIN,
          new ColorRGBA(1f, 1f, 1f, 0.045f),
          new ColorRGBA(1f, 1f, 1f, 0.08f),
          1);
    }
    return nine(chipTex, CHIP_MARGIN);
  }
}
