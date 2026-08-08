package com.worldbox.render;

import com.jme3.math.ColorRGBA;

@FunctionalInterface
public interface NationColorLookup {
  /** Returns null if the nation id has no color (e.g. unclaimed / -1). */
  ColorRGBA colorFor(int nationId);
}
