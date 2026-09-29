package com.hanview.translate;

import android.graphics.Rect;

public class OcrBlock {
    public final int id;
    public final String original;
    public final Rect bounds;
    public String translated;

    public OcrBlock(int id, String original, Rect bounds) {
        this.id = id;
        this.original = original;
        this.bounds = new Rect(bounds);
        this.translated = original;
    }
}
