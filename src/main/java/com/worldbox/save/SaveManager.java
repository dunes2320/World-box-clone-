package com.worldbox.save;

import com.worldbox.sim.Army;
import com.worldbox.sim.Building;
import com.worldbox.sim.Business;
import com.worldbox.sim.Cloud;
import com.worldbox.sim.GameState;
import com.worldbox.sim.Human;
import com.worldbox.sim.Nation;
import com.worldbox.sim.Settlement;
import com.worldbox.util.Calendar;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Whole-world save/load: the entire sim-layer object graph (GameState and
 * everything it reaches - grid, voxels, humans, nations, settlements,
 * armies, businesses, diplomacy, weather, event log) is plain,
 * self-contained data with nothing rendering-related mixed in, so ordinary
 * Java serialization round-trips it without needing a bespoke file format.
 * A fixed number of numbered slots stand in for "multiple save files"
 * without needing any text-entry widget the HUD doesn't have. */
public class SaveManager {
  public static final int SLOT_COUNT = 5;

  private static File saveDir() {
    File dir = new File(System.getProperty("user.home"), ".worldbox/saves");
    dir.mkdirs();
    return dir;
  }

  private static File dataFile(int slot) { return new File(saveDir(), "slot" + slot + ".save"); }
  private static File metaFile(int slot) { return new File(saveDir(), "slot" + slot + ".meta"); }

  public static class SlotInfo {
    public final int slot;
    public final boolean occupied;
    public final String summary;
    public SlotInfo(int slot, boolean occupied, String summary) {
      this.slot = slot; this.occupied = occupied; this.summary = summary;
    }
  }

  /** Fast listing for the settings panel - reads the small text sidecar
   * instead of deserializing all five full save files just to show a
   * label on each slot button. */
  public static SlotInfo[] listSlots() {
    SlotInfo[] out = new SlotInfo[SLOT_COUNT];
    for (int i = 0; i < SLOT_COUNT; i++) {
      int slot = i + 1;
      File meta = metaFile(slot);
      if (meta.exists()) {
        String summary;
        try {
          summary = new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
          summary = "(unreadable)";
        }
        out[i] = new SlotInfo(slot, true, summary);
      } else {
        out[i] = new SlotInfo(slot, false, "Empty");
      }
    }
    return out;
  }

  public static void save(GameState state, int slot) throws IOException {
    File tmp = File.createTempFile("worldbox", ".save", saveDir());
    try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(tmp))) {
      out.writeObject(state);
    }
    // write-to-temp-then-rename so a crash mid-write never corrupts a
    // slot that already held a good save
    Files.move(tmp.toPath(), dataFile(slot).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

    int aliveNations = 0;
    for (Nation n : state.nations.values()) if (n.alive) aliveNations++;
    String summary = Calendar.dateString(state.tick) + " - " + aliveNations + " nations - "
        + state.humans.size() + " people";
    summary += "\n" + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
    Files.write(metaFile(slot).toPath(), summary.getBytes(StandardCharsets.UTF_8));
  }

  public static GameState load(int slot) throws IOException, ClassNotFoundException {
    GameState state;
    try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(dataFile(slot)))) {
      state = (GameState) in.readObject();
    }
    restoreIdCounters(state);
    return state;
  }

  public static void delete(int slot) {
    dataFile(slot).delete();
    metaFile(slot).delete();
  }

  /** Every id-generating class hands out ids from its own running static
   * counter - fine for a single continuous session, but loading a save
   * drops a whole batch of already-used ids into a JVM whose counters
   * don't know about them. Bump every counter past the highest id the
   * loaded world actually contains so nothing freshly created afterward
   * (a new wanderer, a new business, a newly founded nation) can collide
   * with something that was just loaded. */
  private static void restoreIdCounters(GameState state) {
    int maxHuman = 0;
    for (Human h : state.humans) maxHuman = Math.max(maxHuman, h.id);
    Human.restoreNextId(maxHuman);

    int maxSettlement = 0;
    for (int id : state.settlements.keySet()) maxSettlement = Math.max(maxSettlement, id);
    Settlement.restoreNextId(maxSettlement);

    int maxNation = 0;
    for (int id : state.nations.keySet()) maxNation = Math.max(maxNation, id);
    Nation.restoreNextId(maxNation);
    Nation.restoreColorCursor(state.nations.size());

    int maxArmy = 0;
    for (int id : state.armies.keySet()) maxArmy = Math.max(maxArmy, id);
    Army.restoreNextId(maxArmy);

    int maxBusiness = 0;
    for (int id : state.businesses.keySet()) maxBusiness = Math.max(maxBusiness, id);
    Business.restoreNextId(maxBusiness);

    int maxCloud = 0;
    for (Cloud c : state.clouds) maxCloud = Math.max(maxCloud, c.id);
    Cloud.restoreNextId(maxCloud);

    int maxBuilding = 0;
    for (int id : state.buildings.keySet()) maxBuilding = Math.max(maxBuilding, id);
    Building.restoreNextId(maxBuilding);
  }
}
