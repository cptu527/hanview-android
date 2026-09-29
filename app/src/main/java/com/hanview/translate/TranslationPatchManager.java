package com.hanview.translate;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
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

    public TranslationPatchManager(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.density = context.getResources().getDisplayMetrics().density;
    }

    public synchronized void show(List<OcrBlock> blocks, int screenWidth, int screenHeight) {
        clear();

        for (OcrBlock block : blocks) {
            if (block.translated == null) continue;

            String translated = block.translated.trim();
            String original = block.original == null ? "" : block.original.trim();

            if (translated.isEmpty() || translated.equals(original)) {
                continue;
            }

            TextView view = new TextView(context);
            view.setText(translated);
            view.setTextColor(block.textColor);
            view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    Math.max(dp(10), Math.min(dp(18),
                            block.sourceTextSizePx > 0
                                    ? block.sourceTextSizePx
                                    : block.bounds.height() * 0.68f)));
            view.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            view.setMaxLines(block.bounds.height() > block.bounds.width() * 1.8f ? 5 : 2);
            view.setEllipsize(android.text.TextUtils.TruncateAt.END);
            view.setPadding(dp(2), 0, dp(2), 0);

            GradientDrawable background = new GradientDrawable();
            background.setColor(block.backgroundColor);
            background.setCornerRadius(0f);
            view.setBackground(background);

            int left = Math.max(0, block.bounds.left - dp(1));
            int top = Math.max(0, block.bounds.top - dp(1));

            boolean vertical = block.bounds.height() > block.bounds.width() * 1.8f;

            int width;
            int height;

            if (vertical) {
                width = Math.min(dp(150), Math.max(dp(72), block.bounds.height()));
                height = Math.min(screenHeight - top,
                        Math.max(block.bounds.height(), dp(84)));
            } else {
                float measured = view.getPaint().measureText(translated) + dp(8);
                int preferred = (int) Math.ceil(measured);
                width = Math.min(screenWidth - left,
                        Math.max(block.bounds.width() + dp(4),
                                Math.min(preferred, Math.max(block.bounds.width() * 2, dp(220)))));
                height = Math.min(screenHeight - top,
                        Math.max(block.bounds.height() + dp(2), dp(24)));
            }

            if (width <= 0 || height <= 0) {
                continue;
            }

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    width,
                    height,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );

            params.gravity = Gravity.TOP | Gravity.START;
            params.x = left;
            params.y = top;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.alpha = 0.79f;
            }

            try {
                windowManager.addView(view, params);
                patches.add(view);
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized void setVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.INVISIBLE;

        for (View view : patches) {
            view.setVisibility(visibility);
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
        return Math.round(value * density);
    }
}
