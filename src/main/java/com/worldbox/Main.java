package com.worldbox;

import com.jme3.system.AppSettings;

/** Launches the WorldBox 3D desktop app. */
public class Main {
  public static void main(String[] args) {
    GameApp app = new GameApp(1280, 800);
    AppSettings settings = new AppSettings(true);
    settings.setResolution(1280, 800);
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
