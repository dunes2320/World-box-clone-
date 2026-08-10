package com.worldbox;

import com.jme3.system.AppSettings;

/** Launches the WorldBox 3D desktop app. */
public class Main {
  public static void main(String[] args) {
    int width = 1280, height = 800;
    boolean fullscreen = true;
    try {
      java.awt.DisplayMode dm = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
          .getDefaultScreenDevice().getDisplayMode();
      if (dm.getWidth() > 0 && dm.getHeight() > 0) {
        width = dm.getWidth();
        height = dm.getHeight();
      }
    } catch (Throwable t) {
      // no real display available to query (e.g. truly headless) - fall
      // back to a normal windowed default rather than failing to launch
      fullscreen = false;
    }

    GameApp app = new GameApp(width, height);
    AppSettings settings = new AppSettings(true);
    settings.setResolution(width, height);
    settings.setFullscreen(fullscreen);
    settings.setTitle("World Box 3D");
    settings.setResizable(false);
    settings.setSamples(4);
    settings.setAudioRenderer(null);
    app.setSettings(settings);
    app.setShowSettings(false);
    app.setPauseOnLostFocus(false);
    app.start();
  }
}
