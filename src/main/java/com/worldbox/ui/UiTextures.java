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
  private static final int SIZE = 48;

  private static Texture2D roundedRect(ColorRGBA fill, ColorRGBA border, int borderWidth) {
    BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    int arc = MARGIN * 2;
    g.setColor(awt(fill));
    g.fillRoundRect(0, 0, SIZE, SIZE, arc, arc);
    if (borderWidth > 0) {
      g.setStroke(new java.awt.BasicStroke(borderWidth));
      g.setColor(awt(border));
      int half = borderWidth / 2;
      g.drawRoundRect(half, half, SIZE - borderWidth, SIZE - borderWidth, arc, arc);
    }
    g.dispose();

    // jME texture rows run bottom-to-top (matches GL texture coords),
    // BufferedImage rows run top-to-bottom - flip while packing or the
    // panel renders upside down
    ByteBuffer buf = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
    for (int y = SIZE - 1; y >= 0; y--) {
      for (int x = 0; x < SIZE; x++) {
        int argb = img.getRGB(x, y);
        buf.put((byte) ((argb >> 16) & 0xFF));
        buf.put((byte) ((argb >> 8) & 0xFF));
        buf.put((byte) (argb & 0xFF));
        buf.put((byte) ((argb >> 24) & 0xFF));
      }
    }
    buf.flip();
    Image jmeImage = new Image(Image.Format.RGBA8, SIZE, SIZE, buf, ColorSpace.sRGB);
    Texture2D tex = new Texture2D(jmeImage);
    tex.setMagFilter(Texture.MagFilter.Bilinear);
    tex.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
    tex.setWrap(Texture.WrapMode.EdgeClamp);
    return tex;
  }

  private static Color awt(ColorRGBA c) {
    return new Color(clamp(c.r), clamp(c.g), clamp(c.b), clamp(c.a));
  }

  private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

  private static Texture2D panelTex, cardTex;

  /** A fresh nine-patch background component for a structural panel
   * (top bar / toolbar / side panel) - soft rounded corners and a faint
   * accent-tinted border over a dark glass fill. Every caller gets its
   * own component instance (a GuiComponent can only attach to one
   * Panel) but they all share the same underlying texture. */
  public static com.simsilica.lemur.component.TbtQuadBackgroundComponent panelBackground() {
    if (panelTex == null) {
      panelTex = roundedRect(
          new ColorRGBA(0.09f, 0.105f, 0.14f, 0.86f),
          new ColorRGBA(0.36f, 0.44f, 0.56f, 0.55f),
          2);
    }
    return com.simsilica.lemur.component.TbtQuadBackgroundComponent.create(
        panelTex, 1f, MARGIN, MARGIN, MARGIN, MARGIN, 0f, false);
  }

  /** A smaller/lighter variant for nested "card" rows (list rows, stat
   * chips) sitting on top of a panel - just enough contrast lift to read
   * as a distinct surface without competing with the panel itself. */
  public static com.simsilica.lemur.component.TbtQuadBackgroundComponent cardBackground() {
    if (cardTex == null) {
      cardTex = roundedRect(
          new ColorRGBA(1f, 1f, 1f, 0.06f),
          new ColorRGBA(1f, 1f, 1f, 0.09f),
          1);
    }
    return com.simsilica.lemur.component.TbtQuadBackgroundComponent.create(
        cardTex, 1f, MARGIN, MARGIN, MARGIN, MARGIN, 0f, false);
  }
}
