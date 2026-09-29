package com.hanview.translate;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class TranslationPatchManager {
    private final Context context;
    private final WindowManager windowManager;
    private final List<View> patches = new ArrayList<>();
    private final float density;
    private final float scaledDensity;

    public TranslationPatchManager(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.density =
                context.getResources().getDisplayMetrics().density;
        this.scaledDensity =
                context.getResources().getDisplayMetrics().scaledDensity;
    }

    public synchronized boolean hasPatches() {
        return !patches.isEmpty();
    }

    public synchronized void show(
            List<OcrBlock> blocks,
            int screenWidth,
            int screenHeight
    ) {
        clear();

        for (OcrBlock block : blocks) {
            if (block.translated == null) {
                continue;
            }

            String translated = block.translated.trim();
            String original =
                    block.original == null
                            ? ""
                            : block.original.trim();

            if (translated.isEmpty()
                    || translated.equals(original)) {
                continue;
            }

            boolean vertical =
                    block.verticalSource
                            || block.bounds.height()
                            > block.bounds.width() * 1.8f;

            // A vertical OCR box can be very tall even when each source glyph is tiny.
            // Use the narrow dimension for vertical text and the line height for normal text.
            float sourceTextPx =
                    vertical
                            ? (
                            block.sourceGlyphWidthPx > 0
                                    ? block.sourceGlyphWidthPx * 0.82f
                                    : Math.min(
                                            block.bounds.width(),
                                            block.bounds.height()
                                    ) * 0.82f
                    )
                            : (
                            block.sourceTextSizePx > 0
                                    ? block.sourceTextSizePx
                                    : block.bounds.height() * 0.68f
                    );

            sourceTextPx =
                    Math.max(
                            dp(8),
                            Math.min(
                                    dp(18),
                                    sourceTextPx
                            )
                    );

            TextView view =
                    new TextView(context);

            view.setText(translated);
            view.setTextColor(block.textColor);
            view.setGravity(
                    Gravity.CENTER_VERTICAL
                            | Gravity.START
            );
            view.setTypeface(
                    Typeface.DEFAULT,
                    Typeface.NORMAL
            );
            view.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    sourceTextPx
            );
            view.setIncludeFontPadding(false);
            view.setLineSpacing(0f, 1.0f);
            view.setPadding(
                    dp(2),
                    0,
                    dp(2),
                    0
            );

            int left =
                    Math.max(
                            0,
                            block.bounds.left - dp(1)
                    );
            int top =
                    Math.max(
                            0,
                            block.bounds.top - dp(1)
                    );

            int width;
            int height;

            if (vertical) {
                width =
                        Math.min(
                                screenWidth - left,
                                Math.max(
                                        block.bounds.width() + dp(4),
                                        dp(70)
                                )
                        );

                height =
                        Math.min(
                                screenHeight - top,
                                Math.max(
                                        block.bounds.height() + dp(2),
                                        dp(36)
                                )
                        );

                view.setMaxLines(8);
            } else {
                width =
                        Math.min(
                                screenWidth - left,
                                Math.max(
                                        block.bounds.width() + dp(4),
                                        dp(24)
                                )
                        );

                height =
                        Math.min(
                                screenHeight - top,
                                Math.max(
                                        block.bounds.height() + dp(2),
                                        dp(18)
                                )
                        );

                view.setMaxLines(2);
            }

            if (width <= 0 || height <= 0) {
                continue;
            }

            // Auto-shrink Korean only when it would overflow the source-sized box.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int maxSp =
                        Math.max(
                                8,
                                Math.round(
                                        sourceTextPx
                                                / scaledDensity
                                )
                        );

                view.setAutoSizeTextTypeUniformWithConfiguration(
                        6,
                        maxSp,
                        1,
                        TypedValue.COMPLEX_UNIT_SP
                );
            }

            view.setEllipsize(
                    android.text.TextUtils.TruncateAt.END
            );

            GradientDrawable background =
                    new GradientDrawable();
            background.setColor(
                    block.backgroundColor
            );
            background.setCornerRadius(0f);
            view.setBackground(background);

            int type =
                    Build.VERSION.SDK_INT
                            >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams
                                    .TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams
                                    .TYPE_PHONE;

            WindowManager.LayoutParams params =
                    new WindowManager.LayoutParams(
                            width,
                            height,
                            type,
                            WindowManager.LayoutParams
                                    .FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams
                                    .FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams
                                    .FLAG_LAYOUT_IN_SCREEN
                                    | WindowManager.LayoutParams
                                    .FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT
                    );

            params.gravity =
                    Gravity.TOP | Gravity.START;
            params.x = left;
            params.y = top;

            // Keep text patches fully opaque so the original glyphs do not ghost through.
            // Patches are small, non-touchable windows rather than one full-screen blocker.
            params.alpha = 1.0f;

            try {
                windowManager.addView(
                        view,
                        params
                );
                patches.add(view);
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized void clear() {
        for (View view : patches) {
            try {
                windowManager.removeView(view);
            } catch (Exception ignored) {
            }
        }

        patches.clear();
    }

    private int dp(int value) {
        return Math.round(
                value * density
        );
    }
}
