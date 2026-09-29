package com.hanview.translate;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
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
        textPaint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.NORMAL
        ));
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
            String value = block.translated == null
                    ? block.original
                    : block.translated;

            if (value == null || value.trim().isEmpty()) {
                continue;
            }

            // Browser-translation look: erase the original text region with the
            // sampled page/card background and redraw Korean in the same place.
            int horizontalPad = dp(2);
            int verticalPad = dp(1);

            int left = clamp(
                    src.left - horizontalPad,
                    0,
                    screenWidth
            );
            int top = clamp(
                    src.top - verticalPad,
                    0,
                    screenHeight
            );

            int sourceWidth = Math.max(
                    dp(18),
                    src.width() + horizontalPad * 2
            );

            float sourceTextSize = block.sourceTextSizePx > 0
                    ? block.sourceTextSizePx
                    : Math.max(dp(10), src.height() * 0.68f);

            // Korean often needs a little more horizontal space than Chinese/Japanese.
            // Grow only as much as needed so neighbouring UI is not covered.
            textPaint.setTextSize(
                    Math.max(sp(9), Math.min(sp(18), sourceTextSize))
            );
            textPaint.setColor(block.textColor);

            int preferredWidth = Math.min(
                    dp(280),
                    Math.max(
                            sourceWidth,
                            (int) Math.ceil(textPaint.measureText(value)) + dp(4)
                    )
            );

            int right = clamp(
                    left + preferredWidth,
                    left + dp(18),
                    screenWidth
            );

            int textWidth = Math.max(
                    dp(14),
                    right - left
            );

            StaticLayout layout = StaticLayout.Builder
                    .obtain(
                            value,
                            0,
                            value.length(),
                            textPaint,
                            textWidth
                    )
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.0f)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build();

            int requiredHeight = Math.max(
                    src.height() + verticalPad * 2,
                    layout.getHeight() + verticalPad
            );

            int bottom = clamp(
                    top + requiredHeight,
                    top + dp(8),
                    screenHeight
            );

            backgroundPaint.setColor(block.backgroundColor);

            // No round corners, outline or translucent label. This is deliberately
            // a flat replacement patch so white UI stays white, gray cards stay gray, etc.
            canvas.drawRect(
                    left,
                    top,
                    right,
                    bottom,
                    backgroundPaint
            );

            canvas.save();
            canvas.clipRect(
                    left,
                    top,
                    right,
                    bottom
            );
            canvas.translate(
                    left,
                    top + Math.max(0, (bottom - top - layout.getHeight()) / 2f)
            );
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
