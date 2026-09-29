package com.hanview.translate;

import android.graphics.Color;
import android.graphics.Rect;

public class OcrBlock {
    public final int id;
    public final String original;
    public final Rect bounds;
    public String translated;

    public int backgroundColor = Color.WHITE;
    public int textColor = Color.rgb(28, 28, 30);
    public float sourceTextSizePx = 0f;
    public boolean solidBackground = true;

    // Preserve source layout information even after multiple vertical OCR columns
    // are merged into one translation block.
    public boolean verticalSource = false;
    public float sourceGlyphWidthPx = 0f;

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
        verticalSource = other.verticalSource;
        sourceGlyphWidthPx = other.sourceGlyphWidthPx;
    }
}
