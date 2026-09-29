package com.hanview.translate;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class TranslationOverlayView extends View {
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<OcrBlock> blocks = new ArrayList<>();
    private final float density;

    public TranslationOverlayView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        backgroundPaint.setColor(Color.argb(238, 255, 255, 255));
        textPaint.setColor(Color.rgb(20, 24, 32));
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    }

    public void setBlocks(List<OcrBlock> translated) {
        blocks.clear();
        blocks.addAll(translated);
        invalidate();
    }

    public void clearBlocks() {
        blocks.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int screenWidth = getWidth();
        int screenHeight = getHeight();

        for (OcrBlock block : blocks) {
            Rect src = block.bounds;
            String text = block.translated == null ? block.original : block.translated;
            if (text == null || text.trim().isEmpty()) continue;

            int padding = dp(4);
            int minWidth = dp(70);
            int left = clamp(src.left - padding, 0, Math.max(0, screenWidth - minWidth));
            int right = clamp(src.right + padding, left + minWidth, screenWidth);
            int width = Math.max(minWidth, right - left);
            textPaint.setTextSize(Math.max(sp(11), Math.min(sp(18), src.height() * 0.62f)));

            StaticLayout layout = StaticLayout.Builder
                    .obtain(text, 0, text.length(), textPaint, Math.max(dp(40), width - padding * 2))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.02f)
                    .build();

            int desiredHeight = Math.max(src.height() + padding * 2, layout.getHeight() + padding * 2);
            int top = clamp(src.top - padding, 0, Math.max(0, screenHeight - desiredHeight));
            int bottom = Math.min(screenHeight, top + desiredHeight);

            canvas.drawRoundRect(left, top, right, bottom, dp(5), dp(5), backgroundPaint);
            canvas.save();
            canvas.clipRect(left, top, right, bottom);
            canvas.translate(left + padding, top + padding);
            layout.draw(canvas);
            canvas.restore();
        }
    }

    private int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private float sp(int value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
