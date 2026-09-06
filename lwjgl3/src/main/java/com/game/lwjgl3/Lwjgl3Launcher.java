package com.game.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.game.GodGame;
import com.game.sim.SimConfig;

/** Desktop entry point. */
public final class Lwjgl3Launcher {

    private Lwjgl3Launcher() {
    }

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("God Sim");
        config.setWindowedMode(1280, 800);
        config.useVsync(true);
        // Matching the monitor's refresh rate keeps the render loop honest;
        // the simulation runs on its own fixed clock regardless (see SimClock).
        config.setForegroundFPS(0);
        config.setBackBufferConfig(8, 8, 8, 8, 16, 0, 0);
        // There is no sound in this game, and initialising OpenAL on a machine
        // with no audio device (CI, most containers) prints a wall of ALSA
        // errors for a subsystem nothing uses.
        config.disableAudio(true);

        GodGame game = new GodGame(parseSeed(args));
        game.setWorldSize(parseWorldSize(args));

        int smokeFrames = parseInt(args, "--frames", 0);
        if (smokeFrames > 0) {
            game.enableSmokeTest(smokeFrames, parseString(args, "--screenshot"));
            for (String arg : args) {
                if ("--closeup".equals(arg)) {
                    game.enableCloseUp();
                }
                if ("--war".equals(arg)) {
                    game.enableForcedWar();
                }
                if ("--disasters".equals(arg)) {
                    game.enableForcedDisasters();
                }
                if ("--firestorm".equals(arg)) {
                    game.enableFirestorm();
                }
            }
            int stress = parseInt(args, "--stress", 0);
            if (stress > 0) {
                game.setStressUnits(stress);
            }
            int fastForward = parseInt(args, "--ticks", 0);
            if (fastForward > 0) {
                game.setFastForwardTicks(fastForward);
            }
        }

        new Lwjgl3Application(game, config);
    }

    /**
     * Optional {@code --seed <value>} argument. Handy for returning to a world
     * you liked, and for reproducing a bug on the exact terrain that caused it.
     */
    /**
     * Optional {@code --size <n>} argument. Accepts the {@code small}, {@code medium}
     * and {@code large} presets, plus any positive multiple of {@link SimConfig#CHUNK_SIZE}
     * so unusual sizes stay reachable for benchmarking.
     */
    private static int parseWorldSize(String[] args) {
        String value = parseString(args, "--size");
        if (value == null) {
            return SimConfig.DEFAULT_WORLD_SIZE;
        }
        switch (value.toLowerCase()) {
            case "small": return SimConfig.WORLD_SIZE_SMALL;
            case "medium": return SimConfig.WORLD_SIZE_MEDIUM;
            case "large": return SimConfig.WORLD_SIZE_LARGE;
            default: /* fall through to numeric */ break;
        }
        try {
            int size = Integer.parseInt(value);
            if (size <= 0 || size % SimConfig.CHUNK_SIZE != 0) {
                System.err.println("--size must be a positive multiple of "
                    + SimConfig.CHUNK_SIZE + ", got " + size + "; using default");
                return SimConfig.DEFAULT_WORLD_SIZE;
            }
            return size;
        } catch (NumberFormatException e) {
            System.err.println("Ignoring unparseable --size value: " + value);
            return SimConfig.DEFAULT_WORLD_SIZE;
        }
    }

    private static long parseSeed(String[] args) {
        String value = parseString(args, "--seed");
        if (value != null) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                System.err.println("Ignoring unparseable --seed value: " + value);
            }
        }
        return System.nanoTime();
    }

    private static String parseString(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static int parseInt(String[] args, String flag, int fallback) {
        String value = parseString(args, flag);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            System.err.println("Ignoring unparseable " + flag + " value: " + value);
            return fallback;
        }
    }
}
