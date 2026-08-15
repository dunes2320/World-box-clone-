package com.worldbox.ui;

import com.worldbox.sim.GameState;

public interface HudContext {
  GameState getState();
  String getTool();
  void setTool(String id);
  int getBrushSize();
  void setBrushSize(int n);
  GameState.Selection getSelection();
  void setSelection(GameState.Selection sel);
  int getGameSpeed();
  void setGameSpeed(int n);
  void resetWorld();
  float getZoomSensitivity();
  void setZoomSensitivity(float v);
  void quitGame();
}
