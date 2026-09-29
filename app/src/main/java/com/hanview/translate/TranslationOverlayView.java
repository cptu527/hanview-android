package com.hanview.translate;

import android.content.Context;
import android.graphics.Canvas;
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
    private final Paint backgroundPaint =
            new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint =
            new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<OcrBlock> blocks =
            new ArrayList<>();
    private final float density;

    public TranslationOverlayView(Context context) {
        super(context);

        density =
                getResources()
                        .getDisplayMetrics()
                        .density;

        textPaint.setTypeface(
                android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT,
                        android.graphics.Typeface.NORMAL
                )
        );
    }

    public synchronized void setBlocks(
            List<OcrBlock> translated
    ) {
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

        final int screenWidth = getWidth();
        final int screenHeight = getHeight();

        for (OcrBlock block : blocks) {
            Rect src = block.bounds;

            String value =
                    block.translated == null
                            ? ""
                            : block.translated.trim();

            String original =
                    block.original == null
                            ? ""
                            : block.original.trim();

            if (value.isEmpty()
                    || value.equals(original)) {
                continue;
            }

            boolean vertical =
                    src.height()
                            > src.width() * 1.55f;

            int padX = dp(2);
            int padY = dp(1);

            int targetWidth;
            int targetHeight;

            if (vertical) {
                // Convert vertical Japanese columns to horizontal Korean without
                // turning every narrow column into a giant floating label.
                targetWidth =
                        Math.min(
                                dp(132),
                                Math.max(
                                        dp(58),
                                        Math.max(
                                                Math.round(
                                                        src.height() * 0.62f
                                                ),
                                                Math.round(
                                                        src.width() * 2.8f
                                                )
                                        )
                                )
                        );

                targetHeight =
                        Math.min(
                                screenHeight,
                                Math.max(
                                        src.height(),
                                        dp(42)
                                )
                        );
            } else {
                targetWidth =
                        Math.max(
                                dp(24),
                                src.width() + padX * 2
                        );

                targetHeight =
                        Math.max(
                                dp(20),
                                src.height() + padY * 2
                        );
            }

            targetWidth =
                    Math.min(
                            targetWidth,
                            screenWidth
                    );

            int left;

            if (vertical) {
                left =
                        src.centerX()
                                - targetWidth / 2;
            } else {
                left =
                        src.left - padX;
            }

            left =
                    clamp(
                            left,
                            0,
                            Math.max(
                                    0,
                                    screenWidth - targetWidth
                            )
                    );

            int top =
                    clamp(
                            src.top - padY,
                            0,
                            Math.max(
                                    0,
                                    screenHeight - targetHeight
                            )
                    );

            targetHeight =
                    Math.min(
                            targetHeight,
                            screenHeight - top
                    );

            int right =
                    Math.min(
                            screenWidth,
                            left + targetWidth
                    );

            int bottom =
                    Math.min(
                            screenHeight,
                            top + targetHeight
                    );

            if (right <= left || bottom <= top) {
                continue;
            }

            int textWidth =
                    Math.max(
                            dp(18),
                            right - left - dp(2)
                    );

            int textHeight =
                    Math.max(
                            dp(12),
                            bottom - top - dp(2)
                    );

            float requestedSize =
                    block.sourceTextSizePx > 0
                            ? block.sourceTextSizePx
                            : Math.max(
                                    dp(10),
                                    Math.min(
                                            dp(19),
                                            vertical
                                                    ? src.width() * 0.95f
                                                    : src.height() * 0.68f
                                    )
                            );

            StaticLayout layout =
                    buildFittedLayout(
                            value,
                            textWidth,
                            textHeight,
                            requestedSize,
                            vertical ? 8 : 4
                    );

            backgroundPaint.setColor(
                    block.backgroundColor
            );

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

            float dx =
                    left
                            + Math.max(
                            0f,
                            ((right - left)
                                    - layout.getWidth()) / 2f
                    );

            float dy =
                    top
                            + Math.max(
                            0f,
                            ((bottom - top)
                                    - layout.getHeight()) / 2f
                    );

            canvas.translate(dx, dy);
            layout.draw(canvas);
            canvas.restore();
        }
    }

    private StaticLayout buildFittedLayout(
            String value,
            int width,
            int height,
            float requestedPx,
            int maxLines
    ) {
        float minSize =
                sp(8);

        float maxSize =
                Math.min(
                        sp(18),
                        Math.max(
                                minSize,
                                requestedPx
                        )
                );

        float size = maxSize;
        StaticLayout layout = null;

        while (size >= minSize) {
            textPaint.setTextSize(size);

            layout =
                    StaticLayout.Builder
                            .obtain(
                                    value,
                                    0,
                                    value.length(),
                                    textPaint,
                                    width
                            )
                            .setAlignment(
                                    Layout.Alignment.ALIGN_CENTER
                            )
                            .setIncludePad(false)
                            .setLineSpacing(0f, 1.0f)
                            .setMaxLines(maxLines)
                            .setEllipsize(
                                    TextUtils.TruncateAt.END
                            )
                            .build();

            if (layout.getHeight() <= height) {
                break;
            }

            size -= sp(0.5f);
        }

        return layout;
    }

    private int clamp(
            int value,
            int min,
            int max
    ) {
        if (max < min) {
            return min;
        }

        return Math.max(
                min,
                Math.min(max, value)
        );
    }

    private int dp(int value) {
        return Math.round(
                value * density
        );
    }

    private float sp(int value) {
        return sp((float) value);
    }

    private float sp(float value) {
        return value
                * getResources()
                .getDisplayMetrics()
                .scaledDensity;
    }
}
