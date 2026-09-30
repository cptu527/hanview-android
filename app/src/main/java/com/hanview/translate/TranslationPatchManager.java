package com.hanview.translate;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
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

        List<OcrBlock> translated =
                new ArrayList<>();

        int verticalCount = 0;

        for (OcrBlock block : blocks) {
            String text =
                    block.translated == null
                            ? ""
                            : block.translated.trim();

            String original =
                    block.original == null
                            ? ""
                            : block.original.trim();

            if (text.isEmpty()
                    || text.equals(original)) {
                continue;
            }

            translated.add(block);

            if (block.verticalSource
                    || block.bounds.height()
                    > block.bounds.width() * 1.8f) {
                verticalCount++;
            }
        }

        if (translated.isEmpty()) {
            return;
        }

        // Manga pages with several vertical paragraphs are unreadable when each
        // Korean paragraph is painted on top of its source. Use one compact
        // translation panel instead, preserving the artwork and the Japanese text.
        if (verticalCount >= 2) {
            showMangaPanel(
                    translated,
                    screenWidth,
                    screenHeight
            );

            if (patches.isEmpty()) {
                showEmergencyPanel(
                        translated,
                        screenWidth,
                        screenHeight
                );
            }
            return;
        }

        showInlinePatches(
                translated,
                screenWidth,
                screenHeight
        );

        if (patches.isEmpty()) {
            showEmergencyPanel(
                    translated,
                    screenWidth,
                    screenHeight
            );
        }
    }

    private void showMangaPanel(
            List<OcrBlock> blocks,
            int screenWidth,
            int screenHeight
    ) {
        List<OcrBlock> ordered =
                new ArrayList<>(blocks);

        ordered.sort(
                (a, b) -> {
                    boolean av =
                            a.verticalSource
                                    || a.bounds.height()
                                    > a.bounds.width() * 1.8f;
                    boolean bv =
                            b.verticalSource
                                    || b.bounds.height()
                                    > b.bounds.width() * 1.8f;

                    if (av && bv) {
                        int byColumn =
                                Integer.compare(
                                        b.bounds.right,
                                        a.bounds.right
                                );

                        if (byColumn != 0) {
                            return byColumn;
                        }
                    }

                    int byTop =
                            Integer.compare(
                                    a.bounds.top,
                                    b.bounds.top
                            );

                    if (byTop != 0) {
                        return byTop;
                    }

                    return Integer.compare(
                            a.bounds.left,
                            b.bounds.left
                    );
                }
        );

        StringBuilder body =
                new StringBuilder();

        int sourceTop =
                Integer.MAX_VALUE;
        int sourceBottom =
                0;

        for (OcrBlock block : ordered) {
            String text =
                    block.translated == null
                            ? ""
                            : block.translated.trim();

            if (text.isEmpty()) {
                continue;
            }

            if (body.length() > 0) {
                body.append("\n\n");
            }

            body.append(text);

            sourceTop =
                    Math.min(
                            sourceTop,
                            block.bounds.top
                    );
            sourceBottom =
                    Math.max(
                            sourceBottom,
                            block.bounds.bottom
                    );
        }

        if (body.length() == 0) {
            return;
        }

        TextView view =
                new TextView(context);

        view.setText(
                body.toString()
        );
        view.setTextColor(
                Color.WHITE
        );
        view.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                14
        );
        view.setTypeface(
                Typeface.DEFAULT,
                Typeface.NORMAL
        );
        view.setIncludeFontPadding(true);
        view.setLineSpacing(
                dp(2),
                1.12f
        );
        view.setGravity(
                Gravity.START
        );
        view.setPadding(
                dp(14),
                dp(12),
                dp(14),
                dp(12)
        );

        GradientDrawable background =
                new GradientDrawable();

        background.setColor(
                Color.argb(
                        225,
                        15,
                        16,
                        18
                )
        );
        background.setCornerRadius(
                dp(10)
        );

        view.setBackground(
                background
        );

        int margin =
                dp(12);

        int width =
                Math.max(
                        dp(220),
                        screenWidth - margin * 2
                );

        int maxHeight =
                Math.max(
                        dp(120),
                        Math.round(
                                screenHeight * 0.38f
                        )
                );

        float fontSp =
                14f;

        int measuredHeight;

        while (true) {
            view.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    fontSp
            );

            view.measure(
                    View.MeasureSpec.makeMeasureSpec(
                            width,
                            View.MeasureSpec.EXACTLY
                    ),
                    View.MeasureSpec.makeMeasureSpec(
                            0,
                            View.MeasureSpec.UNSPECIFIED
                    )
            );

            measuredHeight =
                    view.getMeasuredHeight();

            if (measuredHeight <= maxHeight
                    || fontSp <= 11f) {
                break;
            }

            fontSp -= 0.5f;
        }

        int height =
                Math.min(
                        maxHeight,
                        measuredHeight
                );

        int safeTop =
                dp(92);
        int safeBottom =
                screenHeight - dp(96);

        int preferredBelow =
                sourceBottom + dp(10);

        int y =
                Math.max(
                        safeTop,
                        Math.min(
                                preferredBelow,
                                safeBottom - height
                        )
                );

        int type =
                Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        width,
                        height,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );

        params.gravity =
                Gravity.TOP | Gravity.START;
        params.x =
                margin;
        params.y =
                y;
        params.alpha =
                1f;

        try {
            windowManager.addView(
                    view,
                    params
            );

            patches.add(view);
            patchBounds.add(
                    new Rect(
                            params.x,
                            params.y,
                            params.x + width,
                            params.y + height
                    )
            );
        } catch (Exception error) {
            Log.e(
                    "ViewNyangPatch",
                    "Failed to add manga translation panel",
                    error
            );
        }
    }

    private void showInlinePatches(
            List<OcrBlock> blocks,
            int screenWidth,
            int screenHeight
    ) {
        for (OcrBlock block : blocks) {
            String text =
                    block.translated == null
                            ? ""
                            : block.translated.trim();

            if (text.isEmpty()) {
                continue;
            }

            boolean vertical =
                    block.verticalSource
                            || block.bounds.height()
                            > block.bounds.width() * 1.8f;

            TextView view =
                    new TextView(context);

            view.setText(text);
            view.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    13
            );
            view.setTypeface(
                    Typeface.DEFAULT,
                    Typeface.NORMAL
            );
            view.setIncludeFontPadding(true);
            view.setLineSpacing(
                    dp(1),
                    1.05f
            );
            view.setGravity(
                    vertical
                            ? Gravity.CENTER
                            : Gravity.CENTER_VERTICAL
                            | Gravity.START
            );
            view.setPadding(
                    dp(5),
                    dp(4),
                    dp(5),
                    dp(4)
            );

            int sampled =
                    block.backgroundColor;

            int brightness =
                    (
                            Color.red(sampled) * 299
                                    + Color.green(sampled) * 587
                                    + Color.blue(sampled) * 114
                    ) / 1000;

            boolean light =
                    brightness >= 145;

            view.setTextColor(
                    light
                            ? Color.rgb(
                            20,
                            20,
                            22
                    )
                            : Color.WHITE
            );

            GradientDrawable background =
                    new GradientDrawable();

            background.setColor(
                    Color.argb(
                            232,
                            Color.red(sampled),
                            Color.green(sampled),
                            Color.blue(sampled)
                    )
            );
            background.setCornerRadius(
                    dp(4)
            );

            view.setBackground(
                    background
            );

            int width =
                    Math.min(
                            screenWidth,
                            Math.max(
                                    dp(48),
                                    block.bounds.width()
                                            + dp(
                                            vertical
                                                    ? 24
                                                    : 12
                                    )
                            )
                    );

            float scaledDensity =
                    context
                            .getResources()
                            .getDisplayMetrics()
                            .scaledDensity;

            float sourcePx =
                    vertical
                            && block.sourceGlyphWidthPx > 0
                            ? block.sourceGlyphWidthPx
                            : block.sourceTextSizePx;

            float fontSp =
                    Math.max(
                            12f,
                            Math.min(
                                    16f,
                                    sourcePx
                                            / scaledDensity
                            )
                    );

            view.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    fontSp
            );

            view.measure(
                    View.MeasureSpec.makeMeasureSpec(
                            width,
                            View.MeasureSpec.EXACTLY
                    ),
                    View.MeasureSpec.makeMeasureSpec(
                            0,
                            View.MeasureSpec.UNSPECIFIED
                    )
            );

            int height =
                    Math.max(
                            dp(28),
                            Math.min(
                                    screenHeight,
                                    view.getMeasuredHeight()
                            )
                    );

            int left =
                    Math.max(
                            0,
                            Math.min(
                                    block.bounds.centerX()
                                            - width / 2,
                                    screenWidth - width
                            )
                    );

            int top =
                    Math.max(
                            0,
                            Math.min(
                                    block.bounds.top,
                                    screenHeight - height
                            )
                    );

            Rect placement =
                    new Rect(
                            left,
                            top,
                            left + width,
                            top + height
                    );

            int type =
                    Build.VERSION.SDK_INT
                            >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams params =
                    new WindowManager.LayoutParams(
                            width,
                            height,
                            type,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT
                    );

            params.gravity =
                    Gravity.TOP | Gravity.START;
            params.x =
                    placement.left;
            params.y =
                    placement.top;
            params.alpha =
                    1f;

            try {
                windowManager.addView(
                        view,
                        params
                );

                patches.add(view);
                patchBounds.add(
                        placement
                );
            } catch (Exception error) {
                Log.e(
                        "ViewNyangPatch",
                        "Failed to add inline translation patch",
                        error
                );
            }
        }
    }

    private void showEmergencyPanel(
            List<OcrBlock> blocks,
            int screenWidth,
            int screenHeight
    ) {
        StringBuilder body =
                new StringBuilder();

        for (OcrBlock block : blocks) {
            String text =
                    block.translated == null
                            ? ""
                            : block.translated.trim();

            if (text.isEmpty()) {
                continue;
            }

            if (body.length() > 0) {
                body.append("\n\n");
            }

            body.append(text);
        }

        if (body.length() == 0) {
            return;
        }

        TextView view =
                new TextView(context);

        view.setText(body.toString());
        view.setTextColor(Color.WHITE);
        view.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                14
        );
        view.setGravity(Gravity.START);
        view.setPadding(
                dp(14),
                dp(12),
                dp(14),
                dp(12)
        );
        view.setLineSpacing(
                dp(2),
                1.12f
        );

        GradientDrawable background =
                new GradientDrawable();

        background.setColor(
                Color.argb(
                        238,
                        12,
                        13,
                        15
                )
        );
        background.setCornerRadius(
                dp(10)
        );

        view.setBackground(background);

        int margin =
                dp(12);
        int width =
                Math.max(
                        dp(180),
                        screenWidth - margin * 2
                );
        width =
                Math.min(
                        screenWidth - dp(4),
                        width
                );

        int maxHeight =
                Math.max(
                        dp(120),
                        Math.round(
                                screenHeight * 0.40f
                        )
                );

        view.measure(
                View.MeasureSpec.makeMeasureSpec(
                        width,
                        View.MeasureSpec.EXACTLY
                ),
                View.MeasureSpec.makeMeasureSpec(
                        maxHeight,
                        View.MeasureSpec.AT_MOST
                )
        );

        int height =
                Math.max(
                        dp(72),
                        Math.min(
                                maxHeight,
                                view.getMeasuredHeight()
                        )
                );

        int safeBottom =
                screenHeight - dp(96);
        int y =
                Math.max(
                        dp(92),
                        safeBottom - height
                );

        int type =
                Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        width,
                        height,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );

        params.gravity =
                Gravity.TOP | Gravity.START;
        params.x =
                Math.max(
                        dp(2),
                        (screenWidth - width) / 2
                );
        params.y =
                y;

        try {
            windowManager.addView(
                    view,
                    params
            );
            patches.add(view);
            patchBounds.add(
                    new Rect(
                            params.x,
                            params.y,
                            params.x + width,
                            params.y + height
                    )
            );
        } catch (Exception error) {
            Log.e(
                    "ViewNyangPatch",
                    "Emergency translation panel also failed",
                    error
            );
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
