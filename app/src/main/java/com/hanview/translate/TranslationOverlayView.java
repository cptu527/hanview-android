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

        backgroundPaint.setColor(Color.argb(224, 255, 255, 255));
        textPaint.setColor(Color.rgb(24, 28, 35));
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    public synchronized void setBlocks(List<OcrBlock> translated) {
        blocks.clear();
        blocks.addAll(translated);
        postInvalidateOnAnimation();
    }

    public synchronized void clearBlocks() {
        blocks.clear();
        postInvalidateOnAnimation();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int screenWidth = getWidth();
        int screenHeight = getHeight();

        for (OcrBlock block : blocks) {
            Rect src = block.bounds;
            String value = block.translated == null ? block.original : block.translated;
            if (value == null || value.trim().isEmpty()) continue;

            int padding = dp(3);
            int left = clamp(src.left - padding, 0, Math.max(0, screenWidth - dp(44)));
            int maxRight = Math.min(screenWidth, left + Math.max(src.width() + dp(20), dp(70)));

            float sourceHeight = Math.max(dp(12), src.height());
            float textSize = Math.max(sp(10), Math.min(sp(15), sourceHeight * 0.52f));
            textPaint.setTextSize(textSize);

            float measured = textPaint.measureText(value);
            int targetWidth = Math.max(src.width() + padding * 2,
                    Math.min((int) measured + padding * 2, dp(240)));
            int right = clamp(left + targetWidth, left + dp(44), screenWidth);
            right = Math.max(right, maxRight);

            int textWidth = Math.max(dp(36), right - left - padding * 2);
            StaticLayout layout = StaticLayout.Builder
                    .obtain(value, 0, value.length(), textPaint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.0f)
                    .setMaxLines(2)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build();

            int desiredHeight = Math.max(src.height() + padding * 2, layout.getHeight() + padding * 2);
            int top = clamp(src.top - padding, 0, Math.max(0, screenHeight - desiredHeight));
            int bottom = Math.min(screenHeight, top + desiredHeight);

            canvas.drawRoundRect(left, top, right, bottom, dp(4), dp(4), backgroundPaint);

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
