package com.hanview.translate;

import android.graphics.Color;
import android.graphics.Rect;

public class OcrBlock {
    public final int id;
    public final String original;
    public final Rect bounds;
    public String translated;

    // Visual hints sampled from the real screen so translated text looks native
    // instead of looking like a floating sticker.
    public int backgroundColor = Color.WHITE;
    public int textColor = Color.rgb(28, 28, 30);
    public float sourceTextSizePx = 0f;
    public boolean solidBackground = true;

    public OcrBlock(int id, String original, Rect bounds) {
        this.id = id;
        this.original = original;
        this.bounds = new Rect(bounds);
        this.translated = original;
    }

    public void copyVisualStyleFrom(OcrBlock other) {
        backgroundColor = other.backgroundColor;
        textColor = other.textColor;
        sourceTextSizePx = other.sourceTextSizePx;
        solidBackground = other.solidBackground;
    }
}
