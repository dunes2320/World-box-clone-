package com.worldbox.sim;

/** A small set of traits that drive individual behavior variance. For an
 * ordinary citizen this colors how they spend their day (a lazy person
 * leans on leisure, an industrious one keeps working); for a nation's
 * leader the same traits are read at a national scale - ambition drives
 * expansion and war, greed drives corruption, wisdom drives stability.
 * A single shared model so a leader really is just an unusually
 * consequential individual, not a separate system. */
public class Personality implements java.io.Serializable {
  public final double industriousness; // 0..1: work ethic / how much of the day is spent working
  public final double ambition;        // 0..1: risk-taking, expansionism, willingness to fight
  public final double greed;           // 0..1: self-interest vs public good
  public final double sociability;     // 0..1: how much time is spent around others
  public final double wisdom;          // 0..1: competence / good judgement

  public Personality(double industriousness, double ambition, double greed, double sociability, double wisdom) {
    this.industriousness = industriousness;
    this.ambition = ambition;
    this.greed = greed;
    this.sociability = sociability;
    this.wisdom = wisdom;
  }

  public static Personality random() {
    return new Personality(Math.random(), Math.random(), Math.random(), Math.random(), Math.random());
  }

  /** A short label for the dominant trait, for UI display. */
  public String archetype() {
    double max = Math.max(industriousness, Math.max(ambition, Math.max(greed, Math.max(sociability, wisdom))));
    if (max == industriousness) return "Industrious";
    if (max == ambition) return "Ambitious";
    if (max == greed) return "Greedy";
    if (max == sociability) return "Sociable";
    return "Wise";
  }
}
