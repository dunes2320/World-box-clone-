package com.worldbox.sim;

/** Temporary diagnostic counters for tracking down a population-collapse
 * regression - not part of the game itself, just instrumentation to see
 * which death cause actually dominates in a soak test. */
public final class DeathStats {
  private DeathStats() {}
  public static int oldAge, starve, homeless, revolt, burn, jobLossHomeless, monster, war;

  public static void reset() { oldAge = starve = homeless = revolt = burn = jobLossHomeless = monster = war = 0; }

  public static String summary() {
    return String.format("oldAge=%d starve=%d homeless=%d revolt=%d burn=%d jobLossHomeless=%d monster=%d war=%d",
        oldAge, starve, homeless, revolt, burn, jobLossHomeless, monster, war);
  }
}
