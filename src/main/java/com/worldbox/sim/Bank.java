package com.worldbox.sim;

/** A nation's central bank: holds reserves, lends to the treasury when it
 * runs dry, and can suffer a bank run if it gets over-leveraged. */
public class Bank implements java.io.Serializable {
  public double reserves = 60;
  public double loans = 0;
  public boolean justCrashed = false;
}
