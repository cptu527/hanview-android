package com.hanview.translate;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.List;

public class ViewNyangAccessibilityService extends AccessibilityService {
    private static volatile ViewNyangAccessibilityService instance;

    private WindowManager windowManager;
    private TranslationOverlayView translationOverlay;
    private long lastScrollEventAt = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        ensureOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastScrollEventAt < 70L) return;
            lastScrollEventAt = now;

            clearTranslations();
            OverlayCaptureService.notifyScreenChanged();
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (translationOverlay != null && windowManager != null) {
            try {
                windowManager.removeView(translationOverlay);
            } catch (Exception ignored) {
            }
        }
        translationOverlay = null;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private void ensureOverlay() {
        if (translationOverlay != null || windowManager == null) return;

        translationOverlay = new TranslationOverlayView(this);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.alpha = 1.0f;

        windowManager.addView(translationOverlay, params);
    }

    private void applyBlocks(List<OcrBlock> blocks) {
        ensureOverlay();
        if (translationOverlay == null) return;

        List<OcrBlock> copy = new ArrayList<>(blocks);
        translationOverlay.setBlocks(copy);
    }

    private void clearInternal() {
        if (translationOverlay != null) {
            translationOverlay.clearBlocks();
        }
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static void showTranslations(List<OcrBlock> blocks) {
        ViewNyangAccessibilityService current = instance;
        if (current == null) return;
        current.runOnUiThread(() -> current.applyBlocks(blocks));
    }

    public static void clearTranslations() {
        ViewNyangAccessibilityService current = instance;
        if (current == null) return;
        current.runOnUiThread(current::clearInternal);
    }

    private void runOnUiThread(Runnable runnable) {
        new android.os.Handler(getMainLooper()).post(runnable);
    }

    public static boolean isEnabled(Context context) {
        String expected = new ComponentName(
                context,
                ViewNyangAccessibilityService.class
        ).flattenToString();

        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (enabled == null) return false;

        TextUtils.SimpleStringSplitter splitter =
                new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);

        while (splitter.hasNext()) {
            String component = splitter.next();
            if (expected.equalsIgnoreCase(component)) {
                return true;
            }
        }
        return false;
    }
}
