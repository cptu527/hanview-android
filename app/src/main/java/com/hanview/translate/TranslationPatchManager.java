package com.hanview.translate;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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
    private final List<Rect> patchBounds = new ArrayList<>();
    private final float density;

    public TranslationPatchManager(Context context) {
        this.context = context.getApplicationContext();
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        density = context.getResources().getDisplayMetrics().density;
    }

    public synchronized boolean hasPatches() { return !patches.isEmpty(); }

    public synchronized List<Rect> getPatchBounds() {
        List<Rect> result = new ArrayList<>();
        for (Rect bounds : patchBounds) result.add(new Rect(bounds));
        return result;
    }

    public synchronized void show(List<OcrBlock> blocks, int screenWidth, int screenHeight) {
        clear();
        if (screenWidth <= 0 || screenHeight <= 0) return;
        for (OcrBlock block : blocks) {
            String text = block.translated == null ? "" : block.translated.trim();
            if (text.isEmpty() || text.equals(block.original == null ? "" : block.original.trim())) continue;
            boolean vertical = block.verticalSource || block.bounds.height() > block.bounds.width() * 1.8f;
            TextView view = new TextView(context);
            view.setText(text);
            // A readable floor, without autosizing or silently dropping the end of a sentence.
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            view.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            view.setIncludeFontPadding(true);
            view.setLineSpacing(dp(1), 1.05f);
            view.setGravity(vertical ? Gravity.CENTER : (Gravity.CENTER_VERTICAL | Gravity.START));
            view.setPadding(dp(5), dp(4), dp(5), dp(4));

            int sampled = block.backgroundColor;
            int brightness = (Color.red(sampled) * 299 + Color.green(sampled) * 587
                    + Color.blue(sampled) * 114) / 1000;
            boolean light = brightness >= 145;
            view.setTextColor(light ? Color.rgb(20, 20, 22) : Color.WHITE);
            GradientDrawable background = new GradientDrawable();
            // Match the sampled paper/bubble colour; opaque fill removes the original glyphs.
            background.setColor(Color.rgb(Color.red(sampled), Color.green(sampled), Color.blue(sampled)));
            background.setCornerRadius(0);
            view.setBackground(background);

            int width = Math.min(screenWidth, Math.max(dp(vertical ? 56 : 36),
                    block.bounds.width() + dp(8)));
            float scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
            float sourcePx = vertical && block.sourceGlyphWidthPx > 0
                    ? block.sourceGlyphWidthPx : block.sourceTextSizePx;
            float fontSp = Math.max(13f, Math.min(17f, sourcePx / scaledDensity));
            int sourceHeight = Math.max(dp(24), block.bounds.height() + dp(4));
            // Fit inside the source paragraph first, preserving its centre and reading order.
            // Stop at 12sp; grow the paragraph instead of reducing text to unreadable sizes.
            while (true) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp);
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                if (view.getMeasuredHeight() <= sourceHeight || fontSp <= 12f) break;
                fontSp = Math.max(12f, fontSp - 0.5f);
            }
            int height = Math.max(sourceHeight, view.getMeasuredHeight());
            if (height > screenHeight) {
                width = screenWidth;
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                height = view.getMeasuredHeight();
            }
            Rect placement = PatchPlacement.find(block.bounds.left - dp(4), block.bounds.top - dp(2), width,
                    height, screenWidth, screenHeight, dp(2), patchBounds);
            // Never stack text windows on top of one another.
            if (placement == null) continue;
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = placement.left;
            params.y = placement.top;
            params.alpha = 1f;
            try {
                windowManager.addView(view, params);
                patches.add(view);
                patchBounds.add(placement);
            } catch (Exception ignored) { }
        }
    }

    public synchronized void clear() {
        for (View view : patches) {
            try { windowManager.removeView(view); } catch (Exception ignored) { }
        }
        patches.clear();
        patchBounds.clear();
    }

    private int dp(int value) { return Math.round(value * density); }
}
