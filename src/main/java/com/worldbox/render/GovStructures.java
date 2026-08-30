package com.worldbox.render;

/** Single source of truth for a settlement's own tiered government marker
 * (hut/town/city - see EntityRenderer.tierIndex) blueprint dimensions,
 * shared with Settlement so it can terraform flat ground under that
 * marker once, at founding, exactly as HouseVariants does for houses. Sized
 * for the LARGEST tier it could ever grow into (see maxFootprintHalfExtent)
 * so a later population-driven tier upgrade never lands on ground that was
 * only ever leveled for the smaller starting hut. */
public final class GovStructures {
  private GovStructures() {}

  private static final Blueprint[] BLUEPRINTS = {
      HouseVariants.blueprintAt(0), // hut: same shape as a cottage house
      Blueprint.building(10, 8, 6, false), // town
      Blueprint.building(10, 10, 8, true), // city
  };

  public static Blueprint blueprintAt(int tier) { return BLUEPRINTS[tier]; }

  public static float maxFootprintHalfExtent() {
    float m = 0;
    for (Blueprint bp : BLUEPRINTS) m = Math.max(m, bp.footprintHalfExtent());
    return m;
  }
}
