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

        int screenWidth = getWidth();
        int screenHeight = getHeight();

        for (OcrBlock block : blocks) {
            Rect src = block.bounds;

            String value =
                    block.translated == null
                            ? ""
                            : block.translated.trim();

            if (value.isEmpty()
                    || value.equals(
                    block.original == null
                            ? ""
                            : block.original.trim()
            )) {
                continue;
            }

            // Keep the replacement tied to the original text bounds.
            // The old implementation widened every label to fit Korean,
            // which made translations drift over neighbouring cards while scrolling.
            boolean vertical =
                    src.height()
                            > src.width() * 1.8f;

            int horizontalPad = dp(2);
            int verticalPad = dp(1);

            int left =
                    clamp(
                            src.left - horizontalPad,
                            0,
                            Math.max(0, screenWidth - dp(18))
                    );

            int top =
                    clamp(
                            src.top - verticalPad,
                            0,
                            Math.max(0, screenHeight - dp(8))
                    );

            int targetWidth;

            if (vertical) {
                // Japanese manga/web text is often vertical. Render the Korean
                // translation as a compact horizontal paragraph next to the same region.
                targetWidth =
                        Math.min(
                                dp(150),
                                Math.max(
                                        dp(72),
                                        src.height()
                                )
                        );

                if (left + targetWidth > screenWidth) {
                    left =
                            Math.max(
                                    0,
                                    screenWidth - targetWidth
                            );
                }
            } else {
                targetWidth =
                        Math.min(
                                screenWidth - left,
                                Math.max(
                                        dp(24),
                                        src.width()
                                                + horizontalPad * 2
                                )
                        );
            }

            float sourceTextSize =
                    block.sourceTextSizePx > 0
                            ? block.sourceTextSizePx
                            : Math.max(
                            dp(10),
                            src.height() * 0.65f
                    );

            textPaint.setTextSize(
                    Math.max(
                            sp(9),
                            Math.min(
                                    sp(17),
                                    sourceTextSize
                            )
                    )
            );
            textPaint.setColor(
                    block.textColor
            );

            int textWidth =
                    Math.max(
                            dp(18),
                            targetWidth
                    );

            StaticLayout layout =
                    StaticLayout.Builder
                            .obtain(
                                    value,
                                    0,
                                    value.length(),
                                    textPaint,
                                    textWidth
                            )
                            .setAlignment(
                                    Layout.Alignment.ALIGN_NORMAL
                            )
                            .setIncludePad(false)
                            .setLineSpacing(0f, 1.0f)
                            .setMaxLines(
                                    vertical ? 6 : 2
                            )
                            .setEllipsize(
                                    TextUtils.TruncateAt.END
                            )
                            .build();

            int targetHeight;

            if (vertical) {
                targetHeight =
                        Math.min(
                                screenHeight - top,
                                Math.max(
                                        src.height(),
                                        layout.getHeight()
                                                + dp(2)
                                )
                        );
            } else {
                targetHeight =
                        Math.min(
                                screenHeight - top,
                                Math.max(
                                        src.height()
                                                + verticalPad * 2,
                                        layout.getHeight()
                                                + dp(2)
                                )
                        );
            }

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

            canvas.translate(
                    left,
                    top + Math.max(
                            0f,
                            (bottom - top
                                    - layout.getHeight()) / 2f
                    )
            );

            layout.draw(canvas);
            canvas.restore();
        }
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
        return value
                * getResources()
                .getDisplayMetrics()
                .scaledDensity;
    }
}
