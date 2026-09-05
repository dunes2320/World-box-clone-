package com.game.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.game.GodGame;

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

        int smokeFrames = parseInt(args, "--frames", 0);
        if (smokeFrames > 0) {
            game.enableSmokeTest(smokeFrames, parseString(args, "--screenshot"));
            for (String arg : args) {
                if ("--closeup".equals(arg)) {
                    game.enableCloseUp();
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
