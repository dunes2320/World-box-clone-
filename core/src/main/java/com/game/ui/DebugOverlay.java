package com.game.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.game.render.TerrainRenderer;
import com.game.sim.Simulation;

/**
 * The F3 debug overlay: fps, tick timing, chunk activity and retained memory.
 *
 * <p>Sample-based rather than instantaneous. A raw tick millisecond number
 * jitters too much to read, so the overlay keeps the last {@link #WINDOW}
 * samples in a ring buffer and shows the average and max. That is what makes
 * "does this optimisation help" a question the overlay can answer.
 *
 * <p>The whole thing costs nothing when hidden: {@link #update} early-outs on
 * an invisible root before it does any string formatting.
 */
public final class DebugOverlay extends Table {

    /** Last 60 samples of each metric; 60 covers about a second at 60fps. */
    private static final int WINDOW = 60;

    private final long[] tickNanos = new long[WINDOW];
    private final long[] frameNanos = new long[WINDOW];
    private int sampleCursor;
    private int samplesTaken;

    private final Label fpsLabel;
    private final Label tickLabel;
    private final Label unitsLabel;
    private final Label chunksLabel;
    private final Label drawLabel;
    private final Label memoryLabel;
    private final Label worldLabel;

    public DebugOverlay(Skin skin) {
        setBackground(skin.getDrawable("panel"));
        pad(10f);
        defaults().left().pad(2f);

        add(new Label("DEBUG (F3)", skin, "accent")).colspan(2).left();
        row();

        fpsLabel = addRow(skin, "fps");
        tickLabel = addRow(skin, "tick ms");
        unitsLabel = addRow(skin, "units");
        chunksLabel = addRow(skin, "chunks");
        drawLabel = addRow(skin, "drawn");
        memoryLabel = addRow(skin, "memory");
        worldLabel = addRow(skin, "world");

        setVisible(false);
    }

    private Label addRow(Skin skin, String caption) {
        add(new Label(caption, skin, "dim")).width(70f);
        Label value = new Label("-", skin);
        add(value).width(160f);
        row();
        return value;
    }

    /** Toggles visibility; call from the F3 key handler. */
    public void toggle() {
        setVisible(!isVisible());
    }

    /**
     * Records this frame's timings and, if visible, refreshes the panel.
     *
     * @param frameNs   total frame time in nanoseconds
     * @param lastTickNs sim tick time in nanoseconds; 0 if no tick ran
     */
    public void update(long frameNs, long lastTickNs, Simulation simulation,
                       TerrainRenderer terrain) {
        frameNanos[sampleCursor] = frameNs;
        tickNanos[sampleCursor] = lastTickNs;
        sampleCursor = (sampleCursor + 1) % WINDOW;
        if (samplesTaken < WINDOW) {
            samplesTaken++;
        }

        if (!isVisible()) {
            return;
        }

        double avgFrameMs = averageMillis(frameNanos);
        double maxFrameMs = maxMillis(frameNanos);
        double avgTickMs = averageMillis(tickNanos);
        double maxTickMs = maxMillis(tickNanos);

        fpsLabel.setText(String.format("%.0f  (avg %.1f ms, max %.1f ms)",
            avgFrameMs > 0 ? 1000.0 / avgFrameMs : 0, avgFrameMs, maxFrameMs));
        tickLabel.setText(String.format("avg %.2f, max %.2f", avgTickMs, maxTickMs));
        unitsLabel.setText(String.format("live %d / cap %d / pool %d",
            simulation.getUnits().getLiveCount(),
            simulation.getPopulationCap(),
            simulation.getUnits().capacity));
        chunksLabel.setText(String.format("%d total, rebuilt %d, culled %d",
            terrain.getChunkCount(),
            terrain.getChunksRebuiltThisFrame(),
            terrain.getChunksCulledLastFrame()));
        drawLabel.setText(String.format("%d chunks", terrain.getChunksDrawnLastFrame()));
        Runtime rt = Runtime.getRuntime();
        long usedBytes = rt.totalMemory() - rt.freeMemory();
        memoryLabel.setText(String.format("%d MB used / %d MB heap",
            usedBytes >> 20, rt.totalMemory() >> 20));
        worldLabel.setText(String.format("%dx%d, tick %d",
            simulation.getWorld().size, simulation.getWorld().size,
            simulation.getTickCount()));

        // A single-frame poke that Gdx has not gone idle - reading Gdx.graphics
        // here also keeps the class from being tree-shaken by proguard/r8
        // once packaging cares about that.
        if (Gdx.graphics == null) {
            fpsLabel.setText("(no graphics)");
        }
    }

    private double averageMillis(long[] samples) {
        if (samplesTaken == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < samplesTaken; i++) {
            sum += samples[i];
        }
        return sum / (double) samplesTaken / 1_000_000.0;
    }

    private double maxMillis(long[] samples) {
        long max = 0;
        for (int i = 0; i < samplesTaken; i++) {
            if (samples[i] > max) {
                max = samples[i];
            }
        }
        return max / 1_000_000.0;
    }

    /** Formatted one-liner for {@code --bench} CSV output. */
    public String bench() {
        return String.format("avgFrameMs=%.3f maxFrameMs=%.3f avgTickMs=%.3f maxTickMs=%.3f",
            averageMillis(frameNanos), maxMillis(frameNanos),
            averageMillis(tickNanos), maxMillis(tickNanos));
    }
}
