package com.worldbox.sim;

import com.worldbox.util.NameGen;

/** Every nation is run by a person, not an abstraction, and that person's
 * personality meaningfully shapes how the nation behaves: an ambitious
 * leader pushes expansion and war, a greedy one drains the treasury and
 * underpays workers, a wise one keeps things stable, a foolish one runs
 * it into the ground with reckless money-printing. A new leader is chosen
 * at founding and again at every succession or coup, so a nation's
 * character can genuinely shift over its lifetime. */
public class Leader {
  public final String name;
  public final Personality personality;
  public final String title;

  public Leader(String government) {
    this.name = NameGen.fullName();
    this.personality = Personality.random();
    this.title = randomTitle(government);
  }

  private static String randomTitle(String government) {
    if (government == null) return "Leader";
    switch (government) {
      case Government.MONARCHY: return Math.random() < 0.5 ? "King" : "Queen";
      case Government.DEMOCRACY: return "President";
      case Government.AUTOCRACY: return "Supreme Leader";
      case Government.OLIGARCHY: return "Chairman";
      default: return "Leader";
    }
  }
}
