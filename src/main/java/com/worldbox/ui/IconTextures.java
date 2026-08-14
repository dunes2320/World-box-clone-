package com.worldbox.ui;

import com.jme3.math.ColorRGBA;
import com.jme3.texture.Texture2D;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/** Small runtime-drawn glyph icons for the god-tool dock - simple flat
 * shapes (no external art, no new dependency), same draw-to-BufferedImage-
 * then-pack-into-a-Texture2D technique as UiTextures' rounded panels.
 * Every glyph is deliberately plain geometry (circles/triangles/lines)
 * rather than anything detailed - at dock-icon size detail would just be
 * noise, and a WorldBox-style tool dock reads by silhouette, not detail. */
public final class IconTextures {
  private IconTextures() {}

  private static final int SIZE = 40;
  private static final Map<String, Texture2D> CACHE = new HashMap<>();
  private static final ColorRGBA GLYPH = new ColorRGBA(0.92f, 0.94f, 0.97f, 1f);

  public static Texture2D icon(String toolId) {
    return CACHE.computeIfAbsent(toolId, IconTextures::draw);
  }

  private static Texture2D draw(String id) {
    BufferedImage img = UiTextures.newCanvas(SIZE);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(UiTextures.awt(GLYPH));
    g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    float c = SIZE / 2f;
    switch (id) {
      case "select":
        g.fillPolygon(new int[]{12, 12, 18, 22, 26}, new int[]{9, 30, 24, 30, 26}, 5);
        break;
      case "water":
        g.fill(teardrop(c, 9, c, 30, 9));
        break;
      case "sand":
        for (float[] p : new float[][]{{13, 16}, {24, 13}, {19, 25}, {28, 24}})
          g.fill(new Ellipse2D.Float(p[0] - 2.5f, p[1] - 2.5f, 5, 5));
        break;
      case "grass":
        for (int i = -1; i <= 1; i++) g.drawLine((int) (c + i * 6), 28, (int) (c + i * 8), 12);
        break;
      case "dirt": {
        Path2D p = new Path2D.Float();
        p.moveTo(10, 26); p.lineTo(14, 13); p.lineTo(22, 10); p.lineTo(30, 18); p.lineTo(26, 29); p.closePath();
        g.fill(p);
        break;
      }
      case "stone": {
        Path2D p = new Path2D.Float();
        p.moveTo(9, 27); p.lineTo(11, 15); p.lineTo(20, 9); p.lineTo(30, 16); p.lineTo(28, 27); p.closePath();
        g.fill(p);
        break;
      }
      case "dig":
        g.drawLine((int) c, 9, (int) c, 24);
        g.fillPolygon(new int[]{10, 30, 20}, new int[]{22, 22, 32}, 3);
        break;
      case "build":
        g.fillPolygon(new int[]{10, 20, 30}, new int[]{18, 8, 18}, 3);
        g.fillRect(13, 18, 14, 12);
        break;
      case "forest":
        g.fillPolygon(new int[]{20, 9, 31}, new int[]{7, 22, 22}, 3);
        g.fillPolygon(new int[]{20, 11, 29}, new int[]{15, 28, 28}, 3);
        g.fillRect((int) c - 2, 28, 4, 6);
        break;
      case "human":
        g.fill(new Ellipse2D.Float(c - 5, 7, 10, 10));
        g.fillPolygon(new int[]{12, 28, 24, 16}, new int[]{33, 33, 18, 18}, 4);
        break;
      case "foundNation":
        g.drawLine(12, 33, 12, 8);
        g.fillPolygon(new int[]{12, 30, 12}, new int[]{8, 14, 20}, 3);
        break;
      case "monster": {
        Path2D p = spikyBlob(c, c, 13, 9);
        g.fill(p);
        break;
      }
      case "zombie":
        g.fill(new Ellipse2D.Float(c - 10, c - 10, 20, 20));
        g.setColor(UiTextures.awt(new ColorRGBA(0.12f, 0.05f, 0.08f, 1f)));
        g.fill(new Ellipse2D.Float(c - 6, c - 3, 4, 4));
        g.fill(new Ellipse2D.Float(c + 2, c - 3, 4, 4));
        break;
      case "fire":
        g.fill(flame(c));
        break;
      case "extinguish":
        g.fill(teardrop(c, 9, c, 29, 8));
        g.setStroke(new BasicStroke(3f));
        g.drawLine(9, 29, 31, 9);
        break;
      case "storm":
        cloud(g, c);
        g.fillPolygon(new int[]{(int) c - 1, (int) c + 4, (int) c - 2, (int) c + 3},
            new int[]{22, 22, 29, 29}, 4);
        break;
      case "meteor":
        g.fill(new Ellipse2D.Float(c - 6, c - 6, 12, 12));
        g.setStroke(new BasicStroke(2f));
        for (int i = 0; i < 3; i++) g.drawLine((int) (c - 10 - i * 4), (int) (c + 10 + i * 2), (int) (c - 4 - i * 3), (int) (c + 4 + i));
        break;
      case "nuke":
        for (int i = 0; i < 6; i++) {
          double a = i * Math.PI / 3;
          Path2D spike = new Path2D.Float();
          spike.moveTo(c, c);
          spike.lineTo((float) (c + Math.cos(a - 0.22) * 15), (float) (c + Math.sin(a - 0.22) * 15));
          spike.lineTo((float) (c + Math.cos(a + 0.22) * 15), (float) (c + Math.sin(a + 0.22) * 15));
          spike.closePath();
          g.fill(spike);
        }
        g.fill(new Ellipse2D.Float(c - 3, c - 3, 6, 6));
        break;
      case "earthquake": {
        Path2D p = new Path2D.Float();
        p.moveTo(7, c);
        int[] dy = {6, -6, 6, -6, 0};
        float x = 7;
        for (int d : dy) { x += 6.5f; p.lineTo(x, c + d); }
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);
        break;
      }
      case "tornado":
        for (int i = 0; i < 4; i++) {
          float w = 16 - i * 3.2f;
          g.drawOval((int) (c - w / 2), 8 + i * 6, (int) w, 7);
        }
        break;
      case "blessing":
        star(g, c, c, 13, 6, 4);
        break;
      case "reset": {
        Path2D arc = new Path2D.Float();
        arc.append(new java.awt.geom.Arc2D.Float(7, 7, 26, 26, 20, 300, java.awt.geom.Arc2D.OPEN), false);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(arc);
        g.fillPolygon(new int[]{29, 34, 25}, new int[]{9, 15, 16}, 3);
        break;
      }
      case "pause":
        g.fillRect(13, 10, 5, 20);
        g.fillRect(22, 10, 5, 20);
        break;
      case "play":
        g.fillPolygon(new int[]{13, 13, 29}, new int[]{9, 31, 20}, 3);
        break;
      case "menu_nations":
        g.fillPolygon(new int[]{9, 14, 20, 26, 31, 27, 13}, new int[]{16, 9, 15, 9, 16, 26, 26}, 7);
        break;
      case "menu_market":
        g.drawOval(9, 9, 22, 22);
        g.drawLine(20, 14, 20, 26);
        g.drawLine(16, 17, 24, 17);
        g.drawLine(16, 23, 24, 23);
        break;
      case "menu_log":
        g.fillRoundRect(11, 8, 18, 24, 3, 3);
        g.setColor(UiTextures.awt(new ColorRGBA(0.1f, 0.11f, 0.15f, 1f)));
        g.drawLine(14, 14, 26, 14);
        g.drawLine(14, 19, 26, 19);
        g.drawLine(14, 24, 22, 24);
        break;
      case "menu_settings":
        g.drawOval((int) c - 5, (int) c - 5, 10, 10);
        for (int i = 0; i < 6; i++) {
          double a = i * Math.PI / 3;
          g.drawLine((int) (c + Math.cos(a) * 8), (int) (c + Math.sin(a) * 8),
              (int) (c + Math.cos(a) * 15), (int) (c + Math.sin(a) * 15));
        }
        break;
      case "tab_terrain":
        g.fillPolygon(new int[]{7, 17, 25, 33}, new int[]{28, 12, 22, 28}, 4);
        break;
      case "tab_civ":
        g.drawLine(12, 32, 12, 9);
        g.fillPolygon(new int[]{12, 29, 12}, new int[]{9, 14, 19}, 3);
        break;
      case "tab_creatures":
        g.fill(new Ellipse2D.Float(c - 8, c - 4, 16, 13));
        for (float[] p : new float[][]{{c - 9, c - 8}, {c - 3, c - 12}, {c + 3, c - 12}, {c + 9, c - 8}})
          g.fill(new Ellipse2D.Float(p[0] - 3, p[1] - 3, 6, 6));
        break;
      case "tab_disasters":
        g.fill(flame(c));
        break;
      case "tab_powers":
        star(g, c, c, 13, 6, 4);
        break;
      default:
        g.fill(new Ellipse2D.Float(c - 8, c - 8, 16, 16));
    }
    g.dispose();
    return UiTextures.toTexture(img);
  }

  private static Path2D teardrop(float x, float topY, float cx, float bottomY, float r) {
    Path2D p = new Path2D.Float();
    p.moveTo(x, topY);
    p.curveTo(x - r, topY + r * 1.6, cx - r, bottomY - r, cx, bottomY);
    p.curveTo(cx + r, bottomY - r, x + r, topY + r * 1.6, x, topY);
    p.closePath();
    return p;
  }

  private static Path2D flame(float c) {
    Path2D p = new Path2D.Float();
    p.moveTo(c, 8);
    p.curveTo(c + 10, 16, c + 9, 22, c + 4, 24);
    p.curveTo(c + 8, 20, c + 5, 17, c + 2, 19);
    p.curveTo(c - 2, 22, c - 1, 27, c + 3, 31);
    p.curveTo(c - 8, 29, c - 10, 20, c - 5, 13);
    p.curveTo(c - 3, 16, c - 1, 14, c, 8);
    p.closePath();
    return p;
  }

  private static void cloud(Graphics2D g, float c) {
    g.fill(new Ellipse2D.Float(c - 12, c - 4, 13, 11));
    g.fill(new Ellipse2D.Float(c - 3, c - 9, 15, 15));
    g.fill(new Ellipse2D.Float(c + 6, c - 3, 12, 10));
  }

  private static Path2D spikyBlob(float cx, float cy, float outer, float inner) {
    Path2D p = new Path2D.Float();
    int spikes = 8;
    for (int i = 0; i < spikes * 2; i++) {
      double a = Math.PI * i / spikes;
      float r = (i % 2 == 0) ? outer : inner;
      float x = (float) (cx + Math.cos(a) * r), y = (float) (cy + Math.sin(a) * r);
      if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
    }
    p.closePath();
    return p;
  }

  private static void star(Graphics2D g, float cx, float cy, float outer, float inner, int points) {
    Path2D p = new Path2D.Float();
    for (int i = 0; i < points * 2; i++) {
      double a = Math.PI * i / points - Math.PI / 2;
      float r = (i % 2 == 0) ? outer : inner;
      float x = (float) (cx + Math.cos(a) * r), y = (float) (cy + Math.sin(a) * r);
      if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
    }
    p.closePath();
    g.fill(p);
  }
}
