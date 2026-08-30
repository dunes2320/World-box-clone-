package com.worldbox.render;

/** The single source of truth for what house/mansion shapes exist and what
 * each one's actual Blueprint dimensions are. EntityRenderer (which builds
 * the meshes) and Settlement (which has to know exactly how much ground to
 * terraform flat under one - see Blueprint.footprintHalfExtent, which
 * covers the free-standing chimney too, not just the wall footprint) used
 * to each keep their own hand-maintained copy of this in sync via a
 * "must match" comment; the two copies disagreeing (the chimney's real
 * offset was never reflected in Settlement's separate footprint-size map
 * at all) is exactly what left chimneys standing on unleveled ground,
 * reading as small detached pillars next to an otherwise flush house. One
 * shared table means there is only one place left to update, so the two
 * users can no longer drift apart. */
public final class HouseVariants {
  private HouseVariants() {}

  public static final String[] NAMES = {"cottage", "cabin", "tall_house", "towered_house", "mansion"};
  public static final int COUNT = NAMES.length;

  private static final Blueprint[] BLUEPRINTS = {
      Blueprint.building(6, 6, 4, false), // cottage: the old plain house
      Blueprint.building(8, 6, 4, false), // cabin: long and low
      Blueprint.building(6, 6, 7, false), // tall_house: two-story
      Blueprint.building(8, 8, 4, true), // towered_house: small corner towers
      Blueprint.building(10, 10, 6, false), // mansion
  };

  /** Every variant except "mansion" - mansions are picked separately, only
   * for a wealthy nation, at a fixed low chance (see Settlement). */
  public static final String[] ORDINARY_NAMES;
  static {
    ORDINARY_NAMES = new String[COUNT - 1];
    int w = 0;
    for (String n : NAMES) if (!"mansion".equals(n)) ORDINARY_NAMES[w++] = n;
  }

  public static int indexOf(String type) {
    for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(type)) return i;
    return 0;
  }

  public static Blueprint blueprintFor(String type) { return BLUEPRINTS[indexOf(type)]; }

  public static Blueprint blueprintAt(int index) { return BLUEPRINTS[index]; }
}
