package com.worldbox.util;

/** Every simulation tick is one day, so the whole world shares a single
 * calendar (12 months of 30 days, a simplified 360-day year - clean
 * month/day math without leap-year bookkeeping) instead of raw tick
 * counts. Ages, founding dates, and the top-bar clock all read off this. */
public class Calendar {
  public static final int DAYS_PER_MONTH = 30;
  public static final int MONTHS_PER_YEAR = 12;
  public static final int DAYS_PER_YEAR = DAYS_PER_MONTH * MONTHS_PER_YEAR;

  private static final String[] MONTH_NAMES = {
      "Frostmoon", "Thawmoon", "Seedmoon", "Bloommoon", "Rainmoon", "Sunmoon",
      "Heatmoon", "Harvestmoon", "Duskmoon", "Leafmoon", "Coldmoon", "Yearsend"
  };

  public static int year(int tick) { return tick / DAYS_PER_YEAR + 1; }
  public static int dayOfYear(int tick) { return tick % DAYS_PER_YEAR; }
  public static int month(int tick) { return dayOfYear(tick) / DAYS_PER_MONTH; }
  public static int dayOfMonth(int tick) { return dayOfYear(tick) % DAYS_PER_MONTH + 1; }
  public static String monthName(int tick) { return MONTH_NAMES[month(tick)]; }

  public static String dateString(int tick) {
    return monthName(tick) + " " + dayOfMonth(tick) + ", Year " + year(tick);
  }

  /** Converts an age measured in ticks (days) to whole years. */
  public static int ageYears(int ageTicks) { return ageTicks / DAYS_PER_YEAR; }
}
