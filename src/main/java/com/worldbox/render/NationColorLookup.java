package com.worldbox.render;

import com.jme3.math.ColorRGBA;

public interface NationColorLookup {
  /** Returns null if the nation id has no color (e.g. unclaimed / -1). */
  ColorRGBA colorFor(int nationId);

  /** The territory border's own (complementary) color - distinct from the
   * interior fill. Defaults to the interior color for lookups that don't
   * care about the distinction (e.g. tinting a human/house). */
  default ColorRGBA borderColorFor(int nationId) { return colorFor(nationId); }
}
