package com.worldbox.sim;

/** Records world events into GameState.eventLog for the HUD's log book
 * panel and toast notifications. A bounded deque so a long game doesn't
 * accumulate an unbounded history in memory. */
public final class EventLog {
  private EventLog() {}

  private static final int MAX_ENTRIES = 300;

  public static void log(GameState state, String category, String message) {
    state.eventLog.addLast(new WorldEvent(state.tick, category, message));
    while (state.eventLog.size() > MAX_ENTRIES) state.eventLog.removeFirst();
  }
}
