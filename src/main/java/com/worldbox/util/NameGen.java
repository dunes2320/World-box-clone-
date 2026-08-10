package com.worldbox.util;

/** Name generation for individuals (citizens, nation leaders) - kept
 * separate from place names (nations/settlements/currencies use their own
 * pools) so a person never accidentally sounds like a kingdom. Wide enough
 * pools that a session with thousands of people rarely repeats a name. */
public class NameGen {
  private static final String[] GIVEN_START = {
      "Ar", "Bel", "Cor", "Dai", "Ed", "Fen", "Gwe", "Hal", "Il", "Jor",
      "Kai", "Lys", "Mira", "Nes", "Ora", "Pil", "Quin", "Ren", "Sae", "Tavi",
      "Ula", "Vel", "Wren", "Xan", "Yara", "Zeph", "Bri", "Cassa", "Dom", "Eryn",
      "Fio", "Gret", "Hesk", "Ini", "Jov", "Kestre", "Liora", "Maren", "Nix", "Oswin"
  };
  private static final String[] GIVEN_END = {
      "a", "an", "en", "in", "on", "ith", "wyn", "ric", "iel", "ora",
      "us", "ette", "ard", "esh", "ana", "ir", "iss", "old", "eth", "yn"
  };
  private static final String[] SURNAME_START = {
      "Stone", "River", "Black", "White", "Iron", "Gold", "North", "South",
      "Ash", "Thorn", "Wood", "Storm", "Hill", "Vale", "Winter", "Summer",
      "Bright", "Dark", "Swift", "Long", "Marsh", "Cliff", "Moon", "Star",
      "Fair", "Grim", "Elm", "Oak", "Frost", "Cinder"
  };
  private static final String[] SURNAME_END = {
      "wood", "field", "brook", "ford", "gate", "hollow", "wick", "moor",
      "haven", "crest", "burn", "shaw", "dale", "worth", "reach", "well"
  };

  private static String pick(String[] arr) { return arr[(int) (Math.random() * arr.length)]; }
  private static String cap(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }

  public static String givenName() {
    return cap(pick(GIVEN_START) + pick(GIVEN_END));
  }

  public static String surname() {
    return cap(pick(SURNAME_START) + pick(SURNAME_END));
  }

  public static String fullName() {
    return givenName() + " " + surname();
  }
}
