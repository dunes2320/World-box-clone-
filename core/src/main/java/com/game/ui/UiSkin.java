package com.game.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;

/**
 * Builds the scene2d skin in code rather than loading an atlas.
 *
 * <p>libGDX's stock {@code uiskin} is not bundled in the jar, so using it would
 * mean shipping asset files. Everything the HUD needs is a flat rectangle in
 * some colour, which a single white pixel plus tinting covers completely - so
 * the whole UI stays asset-free, matching the no-textures rule the 3D side
 * already follows.
 */
public final class UiSkin {

    private UiSkin() {
    }

    public static final Color ACCENT = new Color(0.35f, 0.62f, 0.90f, 1f);
    public static final Color PANEL = new Color(0.10f, 0.12f, 0.16f, 0.88f);
    public static final Color TEXT_DIM = new Color(0.62f, 0.66f, 0.72f, 1f);

    private static final Color BUTTON_UP = new Color(0.18f, 0.21f, 0.26f, 0.95f);
    private static final Color BUTTON_OVER = new Color(0.26f, 0.30f, 0.36f, 0.95f);
    private static final Color BUTTON_DOWN = new Color(0.30f, 0.52f, 0.76f, 1f);

    /** The caller owns the returned skin and must dispose it. */
    public static Skin create() {
        Skin skin = new Skin();

        // One white pixel, tinted per widget. Registered on the skin so its
        // texture is disposed along with everything else.
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(Color.WHITE);
        pixmap.fill();
        Texture white = new Texture(pixmap);
        pixmap.dispose();
        skin.add("white-texture", white);
        skin.add("white", new TextureRegionDrawable(new TextureRegion(white)), Drawable.class);

        // libGDX ships a default bitmap font inside its own jar, so this needs
        // no asset file either.
        BitmapFont font = new BitmapFont();
        font.setUseIntegerPositions(true);
        skin.add("default-font", font);

        skin.add("default", new Label.LabelStyle(font, Color.WHITE));
        skin.add("dim", new Label.LabelStyle(font, TEXT_DIM));
        skin.add("accent", new Label.LabelStyle(font, ACCENT));

        skin.add("panel", skin.newDrawable("white", PANEL), Drawable.class);

        TextButton.TextButtonStyle button = new TextButton.TextButtonStyle();
        button.font = font;
        button.up = skin.newDrawable("white", BUTTON_UP);
        button.over = skin.newDrawable("white", BUTTON_OVER);
        button.down = skin.newDrawable("white", BUTTON_DOWN);
        button.checked = skin.newDrawable("white", BUTTON_DOWN);
        button.fontColor = Color.WHITE;
        button.checkedFontColor = Color.WHITE;
        skin.add("default", button);

        Slider.SliderStyle slider = new Slider.SliderStyle();
        Drawable track = skin.newDrawable("white", new Color(0.28f, 0.31f, 0.36f, 1f));
        track.setMinHeight(6f);
        slider.background = track;
        Drawable knob = skin.newDrawable("white", ACCENT);
        // A 1x1 region has no intrinsic size, so the knob needs explicit
        // dimensions or it renders as a single invisible pixel.
        knob.setMinWidth(12f);
        knob.setMinHeight(22f);
        slider.knob = knob;
        skin.add("default-horizontal", slider);

        return skin;
    }
}
