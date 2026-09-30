package com.hanview.translate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class OverlayCaptureService extends Service {
    public static final String ACTION_START = "com.hanview.translate.START";
    public static final String ACTION_STOP = "com.hanview.translate.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "viewnyang_live_translation";
    private static final int NOTIFICATION_ID = 527;
    private static final long LIVE_INTERVAL_MS = 250L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private WindowManager.LayoutParams bubbleParams;
    private TextView bubble;
    private TranslationPatchManager patchManager;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;

    private final List<TextRecognizer> recognizers = new ArrayList<>();
    private TranslationEngine translationEngine;

    private volatile boolean liveEnabled = false;
    private volatile boolean captureRequested = false;
    private volatile boolean processing = false;
    private volatile boolean translationPending = false;
    private final TranslationDisplayGate displayGate = new TranslationDisplayGate();
    private volatile long translationRetryAfterMs = 0L;
    private long lastErrorToastMs = -15000L;
    private volatile boolean cleanCaptureRequested = true;

    private int[] lastMonitorFingerprint = null;
    private final List<Rect> activePatchBounds =
            Collections.synchronizedList(new ArrayList<>());
    private volatile long suspendTranslationUntilMs = 0L;

    private int captureWidth;
    private int captureHeight;
    private int densityDpi;

    private final BroadcastReceiver systemUiReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(
                        Context context,
                        Intent intent
                ) {
                    if (!Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(
                            intent.getAction()
                    )) {
                        return;
                    }

                    // Home/Recents must never keep old translated text floating
                    // above the system overview.
                    suspendTranslationUntilMs =
                            SystemClock.uptimeMillis() + 1200L;
                    displayGate.invalidate();
                    translationPending = false;
                    if (translationEngine != null) translationEngine.cancelPending();
                    captureRequested = false;
                    processing = false;
                    cleanCaptureRequested = true;
                    lastMonitorFingerprint = null;
                    activePatchBounds.clear();

                    mainHandler.post(() -> {
                        if (patchManager != null) {
                            patchManager.clear();
                        }
                    });
                }
            };

    private final Runnable liveLoop = new Runnable() {
        @Override
        public void run() {
            if (!liveEnabled || captureHandler == null) {
                return;
            }

            if (!displayGate.isVisible() || SystemClock.uptimeMillis()
                    < suspendTranslationUntilMs) {
                captureHandler.postDelayed(
                        this,
                        LIVE_INTERVAL_MS
                );
                return;
            }

            if (!processing && !captureRequested) {
                requestFrame();
            }

            captureHandler.postDelayed(this, LIVE_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        patchManager = new TranslationPatchManager(this);

        // Keep OCR memory bounded: use the important recognizers only and
        // run them sequentially instead of processing the same full-screen
        // bitmap with several heavy models at once.
        // 0 Japanese (also handles Latin), 1 Chinese, 2 Latin fallback.
        recognizers.add(
                TextRecognition.getClient(
                        new JapaneseTextRecognizerOptions.Builder().build()
                )
        );
        recognizers.add(
                TextRecognition.getClient(
                        new ChineseTextRecognizerOptions.Builder().build()
                )
        );
        recognizers.add(
                TextRecognition.getClient(
                        TextRecognizerOptions.DEFAULT_OPTIONS
                )
        );

        translationEngine = new TranslationEngine(this);

        captureThread = new HandlerThread("viewnyang-live-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        IntentFilter systemFilter =
                new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                    systemUiReceiver,
                    systemFilter,
                    Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            registerReceiver(
                    systemUiReceiver,
                    systemFilter
            );
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);

        @SuppressWarnings("deprecation")
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);

        if (resultCode == 0 || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startAsForeground();

            if (mediaProjection == null) {
                startProjection(resultCode, resultData);
                liveEnabled = true;
                displayGate.invalidate();
                cleanCaptureRequested = true;
                lastMonitorFingerprint = null;
                showBubble();

                captureHandler.removeCallbacks(liveLoop);
                captureHandler.postDelayed(liveLoop, 120L);
            }

            return START_STICKY;
        } catch (Throwable startupError) {
            mainHandler.post(() ->
                    Toast.makeText(
                            this,
                            "실시간 번역 실행 중 오류가 발생했어요. 다시 시작해 주세요.",
                            Toast.LENGTH_LONG
                    ).show()
            );
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private void startAsForeground() {
        createNotificationChannel();

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(this, CHANNEL_ID)
                        : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("뷰냥 실시간 번역")
                .setContentText("화면의 외국어를 한국어로 바꾸는 중")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "뷰냥 실시간 번역",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("실시간 화면 번역을 실행하는 동안 표시됩니다.");

        getSystemService(NotificationManager.class)
                .createNotificationChannel(channel);
    }

    @SuppressWarnings("deprecation")
    private void startProjection(int resultCode, Intent resultData) {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);

        captureWidth = metrics.widthPixels;
        captureHeight = metrics.heightPixels;
        densityDpi = metrics.densityDpi;

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(
                        Context.MEDIA_PROJECTION_SERVICE
                );

        mediaProjection = manager.getMediaProjection(resultCode, resultData);

        mediaProjection.registerCallback(
                new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        mainHandler.post(() -> stopSelf());
                    }

                    @Override
                    public void onCapturedContentVisibilityChanged(boolean isVisible) {
                        // Single-app sharing can keep producing the old app's pixels after
                        // another app covers it. Pixel differences cannot detect that case.
                        displayGate.setVisible(isVisible);
                        invalidateCapturedPage();
                        mainHandler.post(() -> {
                            if (bubble != null) bubble.setVisibility(isVisible ? View.VISIBLE : View.GONE);
                        });
                    }
                },
                captureHandler
        );

        imageReader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                1
        );

        imageReader.setOnImageAvailableListener(
                reader -> {
                    Image image = null;

                    try {
                        image = reader.acquireLatestImage();

                        if (image == null
                                || !captureRequested
                                || processing
                                || !liveEnabled
                                || !displayGate.isVisible()) {
                            return;
                        }

                        captureRequested = false;
                        processing = true;

                        final int frameGeneration = displayGate.current();
                        final boolean cleanFrame = cleanCaptureRequested;
                        Bitmap bitmap = imageToBitmap(image);

                        if (!cleanFrame) {
                            handleMonitorFrame(
                                    bitmap,
                                    frameGeneration
                            );
                        } else {
                            processBitmap(
                                    bitmap,
                                    frameGeneration
                            );
                        }
                    } catch (Throwable ignored) {
                        captureRequested = false;
                        processing = false;
                        cleanCaptureRequested = true;

                        mainHandler.post(() -> {
                            if (patchManager != null) {
                                patchManager.clear();
                            }
                        });
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                },
                captureHandler
        );

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ViewNyangLiveScreen",
                captureWidth,
                captureHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler
        );
    }

    private void requestFrame() {
        if (!liveEnabled || !displayGate.isVisible() || processing || captureRequested) {
            return;
        }

        // When translations are visible, first grab a monitor frame without
        // hiding anything. Only if the page actually moved do we clear the old
        // text and request one clean OCR frame.
        cleanCaptureRequested = !translationPending
                && SystemClock.uptimeMillis() >= translationRetryAfterMs
                && (patchManager == null || !patchManager.hasPatches());

        captureRequested = true;
    }

    private void handleMonitorFrame(
            Bitmap bitmap,
            int frameGeneration
    ) {
        if (!liveEnabled
                || !displayGate.canDisplay(frameGeneration)) {
            bitmap.recycle();
            processing = false;
            return;
        }

        int[] fingerprint =
                makeFingerprint(
                        bitmap,
                        activePatchBounds
                );

        bitmap.recycle();

        if (lastMonitorFingerprint == null) {
            lastMonitorFingerprint = fingerprint;
            processing = false;
            return;
        }

        float change =
                fingerprintDifference(
                        lastMonitorFingerprint,
                        fingerprint
                );

        lastMonitorFingerprint = fingerprint;

        if (change < 7.5f) {
            processing = false;
            return;
        }

        invalidateCapturedPage();
    }

    private void invalidateCapturedPage() {
        displayGate.invalidate();
        final int newGeneration = displayGate.current();
        translationPending = false;
        translationRetryAfterMs = 0L;
        if (translationEngine != null) translationEngine.cancelPending();
        lastMonitorFingerprint = null;
        activePatchBounds.clear();
        cleanCaptureRequested = true;
        captureRequested = false;
        // Keep capture blocked until the old windows have actually been removed.
        processing = true;
        mainHandler.post(() -> {
            if (patchManager != null) patchManager.clear();
            if (bubble != null) styleBubble(liveEnabled);
            if (captureHandler == null) return;
            captureHandler.postDelayed(() -> {
                if (displayGate.current() != newGeneration) return;
                processing = false;
                if (liveEnabled && displayGate.isVisible()) requestFrame();
            }, 40L);
        });
    }

    private int[] makeFingerprint(
            Bitmap bitmap,
            List<Rect> ignoreBounds
    ) {
        final int columns = 24;
        final int rows = 40;
        int[] out = new int[columns * rows];

        int startY =
                Math.max(
                        0,
                        bitmap.getHeight() / 10
                );

        int usableHeight =
                Math.max(
                        1,
                        bitmap.getHeight()
                                - startY
                                - bitmap.getHeight() / 12
                );

        int index = 0;

        for (int y = 0; y < rows; y++) {
            int py =
                    startY
                            + (int) (
                            (y + 0.5f)
                                    * usableHeight
                                    / rows
                    );

            py = Math.min(
                    bitmap.getHeight() - 1,
                    Math.max(0, py)
            );

            for (int x = 0; x < columns; x++) {
                int px =
                        (int) (
                                (x + 0.5f)
                                        * bitmap.getWidth()
                                        / columns
                        );

                px = Math.min(
                        bitmap.getWidth() - 1,
                        Math.max(0, px)
                );

                if (isInsideIgnoredRegion(
                        px,
                        py,
                        ignoreBounds
                )) {
                    out[index++] = -1;
                    continue;
                }

                int c =
                        bitmap.getPixel(px, py);

                out[index++] =
                        (
                                Color.red(c) * 299
                                        + Color.green(c) * 587
                                        + Color.blue(c) * 114
                        ) / 1000;
            }
        }

        return out;
    }

    private boolean isInsideIgnoredRegion(
            int x,
            int y,
            List<Rect> ignoreBounds
    ) {
        if (ignoreBounds == null
                || ignoreBounds.isEmpty()) {
            return false;
        }

        synchronized (ignoreBounds) {
            for (Rect source : ignoreBounds) {
                Rect expanded =
                        new Rect(
                                source.left - dp(8),
                                source.top - dp(8),
                                source.right + dp(8),
                                source.bottom + dp(8)
                        );

                if (expanded.contains(x, y)) {
                    return true;
                }
            }
        }

        return false;
    }

    private float fingerprintDifference(
            int[] a,
            int[] b
    ) {
        if (a == null
                || b == null
                || a.length != b.length
                || a.length == 0) {
            return 255f;
        }

        long total = 0L;
        int compared = 0;

        for (int i = 0; i < a.length; i++) {
            if (a[i] < 0 || b[i] < 0) {
                continue;
            }

            total += Math.abs(
                    a[i] - b[i]
            );
            compared++;
        }

        if (compared == 0) {
            return 255f;
        }

        return (float) total / compared;
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();

        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * captureWidth;
        int paddedWidth = captureWidth + rowPadding / pixelStride;

        Bitmap padded = Bitmap.createBitmap(
                paddedWidth,
                captureHeight,
                Bitmap.Config.ARGB_8888
        );
        padded.copyPixelsFromBuffer(buffer);

        Bitmap result = Bitmap.createBitmap(
                padded,
                0,
                0,
                captureWidth,
                captureHeight
        );

        if (result != padded) {
            padded.recycle();
        }

        return result;
    }

    private void processBitmap(
            Bitmap bitmap,
            int frameGeneration
    ) {
        mainHandler.post(() -> {
            if (bubble != null
                    && liveEnabled
                    && displayGate.canDisplay(frameGeneration)) {
                bubble.setText("OCR…");
            }
        });

        InputImage input =
                InputImage.fromBitmap(
                        bitmap,
                        0
                );

        List<OcrBlock> candidates =
                new ArrayList<>();

        // Manga path first. The Japanese recognizer also reads Latin, so many
        // pages finish here without loading another OCR model.
        recognizers.get(0)
                .process(input)
                .addOnSuccessListener(text ->
                        mergeCandidates(
                                candidates,
                                extractForeignLines(text)
                        )
                )
                .addOnCompleteListener(task -> {
                    if (!isCurrentGeneration(
                            frameGeneration
                    )) {
                        safeRecycle(bitmap);
                        return;
                    }

                    if (containsKana(candidates)
                            || containsLatinWithoutHan(candidates)) {
                        finishAndRecycle(
                                candidates,
                                bitmap,
                                frameGeneration
                        );
                        return;
                    }

                    runChinesePass(
                            input,
                            bitmap,
                            candidates,
                            frameGeneration
                    );
                });
    }

    private void runChinesePass(
            InputImage input,
            Bitmap bitmap,
            List<OcrBlock> candidates,
            int frameGeneration
    ) {
        recognizers.get(1)
                .process(input)
                .addOnSuccessListener(text ->
                        mergeCandidates(
                                candidates,
                                extractForeignLines(text)
                        )
                )
                .addOnCompleteListener(task -> {
                    if (!isCurrentGeneration(
                            frameGeneration
                    )) {
                        safeRecycle(bitmap);
                        return;
                    }

                    if (!candidates.isEmpty()) {
                        finishAndRecycle(
                                candidates,
                                bitmap,
                                frameGeneration
                        );
                        return;
                    }

                    runLatinFallback(
                            input,
                            bitmap,
                            candidates,
                            frameGeneration
                    );
                });
    }

    private void runLatinFallback(
            InputImage input,
            Bitmap bitmap,
            List<OcrBlock> candidates,
            int frameGeneration
    ) {
        recognizers.get(2)
                .process(input)
                .addOnSuccessListener(text ->
                        mergeCandidates(
                                candidates,
                                extractForeignLines(text)
                        )
                )
                .addOnCompleteListener(task ->
                        finishAndRecycle(
                                candidates,
                                bitmap,
                                frameGeneration
                        )
                );
    }

    private boolean isCurrentGeneration(
            int frameGeneration
    ) {
        return liveEnabled
                && displayGate.canDisplay(frameGeneration);
    }

    private void mergeCandidates(
            List<OcrBlock> target,
            List<OcrBlock> found
    ) {
        for (OcrBlock block : found) {
            addOrReplaceOverlapping(
                    target,
                    block
            );
        }
    }

    private boolean containsKana(
            List<OcrBlock> blocks
    ) {
        for (OcrBlock block : blocks) {
            String value = block.original;
            if (value == null) {
                continue;
            }

            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);

                if (c >= '\u3040'
                        && c <= '\u30FF') {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean containsLatinWithoutHan(
            List<OcrBlock> blocks
    ) {
        boolean latin = false;

        for (OcrBlock block : blocks) {
            String value = block.original;
            if (value == null) {
                continue;
            }

            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);

                if ((c >= '\u3400' && c <= '\u4DBF')
                        || (c >= '\u4E00' && c <= '\u9FFF')) {
                    return false;
                }

                if ((c >= 'A' && c <= 'Z')
                        || (c >= 'a' && c <= 'z')
                        || (c >= '\u00C0' && c <= '\u024F')) {
                    latin = true;
                }
            }
        }

        return latin;
    }

    private void finishAndRecycle(
            List<OcrBlock> candidates,
            Bitmap bitmap,
            int frameGeneration
    ) {
        try {
            finishRecognition(
                    candidates,
                    bitmap,
                    frameGeneration
            );
        } catch (Throwable ignored) {
            processing = false;
            cleanCaptureRequested = true;
            safeRecycle(bitmap);
        }
    }

    private void safeRecycle(
            Bitmap bitmap
    ) {
        if (bitmap != null
                && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private void finishRecognition(
            List<OcrBlock> candidates,
            Bitmap screenshot,
            int frameGeneration
    ) {
        if (!liveEnabled
                || !displayGate.canDisplay(frameGeneration)) {
            if (!screenshot.isRecycled()) {
                screenshot.recycle();
            }
            return;
        }

        List<OcrBlock> blocks =
                new ArrayList<>(candidates);

        blocks.sort(
                Comparator
                        .comparingInt(
                                (OcrBlock b) ->
                                        b.bounds.top
                        )
                        .thenComparingInt(
                                b -> b.bounds.left
                        )
        );

        blocks =
                mergeVerticalColumns(blocks);

        List<OcrBlock> normalized =
                new ArrayList<>();

        for (OcrBlock old : blocks) {
            if (old.original == null
                    || old.original.trim().length() < 2) {
                continue;
            }

            OcrBlock block = new OcrBlock(
                    normalized.size(),
                    old.original,
                    old.bounds
            );

            block.verticalSource =
                    old.verticalSource;
            block.sourceGlyphWidthPx =
                    old.sourceGlyphWidthPx;

            sampleVisualStyle(
                    screenshot,
                    block
            );

            normalized.add(block);

            if (normalized.size() >= 70) {
                break;
            }
        }

        activePatchBounds.clear();

        lastMonitorFingerprint =
                makeFingerprint(
                        screenshot,
                        Collections.emptyList()
                );

        if (!screenshot.isRecycled()) {
            screenshot.recycle();
        }

        if (normalized.isEmpty()) {
            processing = false;
            mainHandler.post(() -> {
                if (patchManager != null && liveEnabled) {
                    patchManager.clear();
                    lastMonitorFingerprint = null;
                    cleanCaptureRequested = true;
                }
                if (bubble != null
                        && liveEnabled
                        && displayGate.canDisplay(frameGeneration)) {
                    bubble.setText("없음");
                }
            });
            return;
        }

        // OCR is complete. Network translation must never hold the capture/monitor lock.
        translationPending = true;
        cleanCaptureRequested = false;
        processing = false;
        mainHandler.post(() -> {
            if (bubble != null && liveEnabled && displayGate.canDisplay(frameGeneration)) bubble.setText("번역…");
        });
        translationEngine.translate(normalized, new TranslationEngine.Callback() {
            @Override public void onSuccess(List<OcrBlock> translated, boolean usedAi) {
                mainHandler.post(() -> {
                    if (!liveEnabled || !displayGate.isVisible() || !displayGate.canDisplay(frameGeneration)) return;
                    boolean visibleResult = false;

                    if (patchManager != null) {
                        patchManager.show(
                                translated,
                                captureWidth,
                                captureHeight
                        );
                        activePatchBounds.clear();
                        activePatchBounds.addAll(
                                patchManager.getPatchBounds()
                        );
                        visibleResult =
                                patchManager.hasPatches();
                        cleanCaptureRequested =
                                !visibleResult;
                    }

                    translationPending = false;

                    if (bubble != null) {
                        bubble.setText(
                                visibleResult
                                        ? (
                                        usedAi
                                                ? "문"
                                                : "한"
                                )
                                        : "!"
                        );
                    }

                    if (!visibleResult) {
                        long now =
                                SystemClock.uptimeMillis();

                        if (now - lastErrorToastMs
                                >= 15000L) {
                            lastErrorToastMs = now;
                            Toast.makeText(
                                    OverlayCaptureService.this,
                                    "번역은 끝났지만 화면 표시가 실패했어요. 자동으로 다시 시도합니다.",
                                    Toast.LENGTH_LONG
                            ).show();
                        }

                        translationRetryAfterMs =
                                now + 1000L;
                    }
                });
            }
            @Override public void onError(String message) {
                mainHandler.post(() -> {
                    if (!liveEnabled || !displayGate.isVisible() || !displayGate.canDisplay(frameGeneration)) return;
                    translationPending = false;
                    long now = SystemClock.uptimeMillis();
                    translationRetryAfterMs = now + 2500L;
                    cleanCaptureRequested = false;
                    if (bubble != null) bubble.setText("!");

                    if (now - lastErrorToastMs >= 15000L) {
                        lastErrorToastMs = now;
                        Toast.makeText(OverlayCaptureService.this, message, Toast.LENGTH_LONG).show();
                    }

                    if (captureHandler != null) {
                        captureHandler.postDelayed(() -> {
                            if (!liveEnabled
                                    || !displayGate.isVisible()
                                    || !displayGate.canDisplay(frameGeneration)
                                    || translationPending) {
                                return;
                            }
                            cleanCaptureRequested = true;
                            requestFrame();
                        }, 2600L);
                    }
                });
            }
        });
    }

    private List<OcrBlock> mergeVerticalColumns(
            List<OcrBlock> source
    ) {
        // ML Kit's Japanese recognizer already returns each vertical text column
        // in reading order within that column. Do NOT glue neighboring columns
        // together here: adjacent manga columns are very often separate sentences
        // or turns of speech, and concatenating them changes the meaning.
        List<OcrBlock> out =
                new ArrayList<>(source);

        out.sort(
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
                                        b.bounds.centerX(),
                                        a.bounds.centerX()
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

        return out;
    }

    private boolean endsWithJapanesePunctuation(
            StringBuilder value
    ) {
        if (value.length() == 0) {
            return false;
        }

        char c =
                value.charAt(
                        value.length() - 1
                );

        return c == '。'
                || c == '！'
                || c == '？'
                || c == '!'
                || c == '?'
                || c == '」'
                || c == '』';
    }

    private Rect unionBounds(
            List<OcrBlock> blocks
    ) {
        Rect out =
                new Rect(
                        blocks.get(0).bounds
                );

        for (int i = 1; i < blocks.size(); i++) {
            out.union(
                    blocks.get(i).bounds
            );
        }

        return out;
    }

    private float verticalOverlapRatio(
            Rect a,
            Rect b
    ) {
        int top =
                Math.max(
                        a.top,
                        b.top
                );
        int bottom =
                Math.min(
                        a.bottom,
                        b.bottom
                );

        int overlap =
                Math.max(
                        0,
                        bottom - top
                );

        int smaller =
                Math.max(
                        1,
                        Math.min(
                                a.height(),
                                b.height()
                        )
                );

        return (float) overlap
                / smaller;
    }

    private int horizontalGap(
            Rect a,
            Rect b
    ) {
        if (a.right < b.left) {
            return b.left - a.right;
        }

        if (b.right < a.left) {
            return a.left - b.right;
        }

        return 0;
    }

    private List<OcrBlock> extractForeignLines(Text result) {
        List<OcrBlock> out = new ArrayList<>();
        int id = 0;

        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String value =
                        line.getText() == null
                                ? ""
                                : line.getText().trim();

                Rect rect = line.getBoundingBox();

                if (rect == null
                        || value.isEmpty()
                        || !hasForeignLetters(value)
                        || isKoreanDominant(value)
                        || isLikelyBrowserOrSystemUi(value, rect)) {
                    continue;
                }

                if (rect.width() < dp(8)
                        || rect.height() < dp(8)) {
                    continue;
                }

                OcrBlock recognized =
                        new OcrBlock(
                                id++,
                                value,
                                rect
                        );

                recognized.verticalSource =
                        rect.height()
                                > rect.width() * 1.8f;

                recognized.sourceGlyphWidthPx =
                        recognized.verticalSource
                                ? rect.width()
                                : rect.height();

                out.add(recognized);

                if (out.size() >= 80) {
                    return out;
                }
            }
        }

        return out;
    }

    private boolean isLikelyBrowserOrSystemUi(
            String value,
            Rect rect
    ) {
        if (captureHeight <= 0) {
            return false;
        }

        boolean hasCjkOrKana = false;

        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);

            if ((ch >= '\u3040' && ch <= '\u30FF')
                    || (ch >= '\u3400' && ch <= '\u4DBF')
                    || (ch >= '\u4E00' && ch <= '\u9FFF')
                    || (ch >= '\u0900' && ch <= '\u097F')) {
                hasCjkOrKana = true;
                break;
            }
        }

        if (hasCjkOrKana) {
            return false;
        }

        // Browser address bars, gallery controls, status/navigation bars and
        // short Latin UI labels should not become translation cards.
        int topChrome =
                Math.round(
                        captureHeight * 0.18f
                );
        int bottomChrome =
                Math.round(
                        captureHeight * 0.90f
                );

        if (rect.bottom <= topChrome
                || rect.top >= bottomChrome) {
            return true;
        }

        String compact =
                value.replaceAll(
                        "[^A-Za-z0-9]",
                        ""
                );

        return compact.length() <= 18
                && rect.top
                < Math.round(
                captureHeight * 0.23f
        );
    }

    private boolean hasForeignLetters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (Character.isLetter(c)
                    && !isHangul(c)) {
                return true;
            }
        }

        return false;
    }

    private boolean isKoreanDominant(String value) {
        int letters = 0;
        int hangul = 0;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (!Character.isLetter(c)) {
                continue;
            }

            letters++;

            if (isHangul(c)) {
                hangul++;
            }
        }

        return letters > 0
                && hangul * 5 >= letters * 3;
    }

    private boolean isHangul(char c) {
        return (c >= '\uAC00' && c <= '\uD7A3')
                || (c >= '\u1100' && c <= '\u11FF')
                || (c >= '\u3130' && c <= '\u318F');
    }

    private void addOrReplaceOverlapping(
            List<OcrBlock> blocks,
            OcrBlock candidate
    ) {
        for (int i = 0; i < blocks.size(); i++) {
            OcrBlock existing = blocks.get(i);

            if (intersectionOverUnion(
                    existing.bounds,
                    candidate.bounds
            ) < 0.56f) {
                continue;
            }

            if (candidateScore(candidate.original)
                    > candidateScore(existing.original)) {
                blocks.set(i, candidate);
            }

            return;
        }

        blocks.add(candidate);
    }

    private float intersectionOverUnion(
            Rect a,
            Rect b
    ) {
        int left = Math.max(a.left, b.left);
        int top = Math.max(a.top, b.top);
        int right = Math.min(a.right, b.right);
        int bottom = Math.min(a.bottom, b.bottom);

        if (right <= left || bottom <= top) {
            return 0f;
        }

        long intersection =
                (long) (right - left)
                        * (bottom - top);

        long union =
                (long) a.width()
                        * a.height()
                        + (long) b.width()
                        * b.height()
                        - intersection;

        return union <= 0
                ? 0f
                : (float) intersection
                        / (float) union;
    }

    private int candidateScore(String value) {
        int score = value == null
                ? 0
                : value.length();

        if (value == null) {
            return score;
        }

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (c >= '\u3040'
                    && c <= '\u30FF') {
                score += 16;
            } else if ((c >= '\u3400'
                    && c <= '\u4DBF')
                    || (c >= '\u4E00'
                    && c <= '\u9FFF')) {
                score += 6;
            }
        }

        return score;
    }

    private void sampleVisualStyle(
            Bitmap screenshot,
            OcrBlock block
    ) {
        Rect r = block.bounds;

        int margin =
                Math.max(
                        dp(3),
                        Math.round(
                                Math.min(
                                        r.width(),
                                        r.height()
                                ) * 0.22f
                        )
                );

        int left =
                Math.max(
                        0,
                        Math.min(
                                screenshot.getWidth() - 1,
                                r.left - margin
                        )
                );
        int top =
                Math.max(
                        0,
                        Math.min(
                                screenshot.getHeight() - 1,
                                r.top - margin
                        )
                );
        int right =
                Math.max(
                        left,
                        Math.min(
                                screenshot.getWidth() - 1,
                                r.right + margin
                        )
                );
        int bottom =
                Math.max(
                        top,
                        Math.min(
                                screenshot.getHeight() - 1,
                                r.bottom + margin
                        )
                );

        java.util.HashMap<Integer, Integer> bins =
                new java.util.HashMap<>();

        int stepX =
                Math.max(
                        1,
                        (right - left) / 12
                );

        int stepY =
                Math.max(
                        1,
                        (bottom - top) / 8
                );

        for (int x = left;
             x <= right;
             x += stepX) {
            addColorSample(
                    screenshot.getPixel(x, top),
                    bins
            );
            addColorSample(
                    screenshot.getPixel(x, bottom),
                    bins
            );
        }

        for (int y = top;
             y <= bottom;
             y += stepY) {
            addColorSample(
                    screenshot.getPixel(left, y),
                    bins
            );
            addColorSample(
                    screenshot.getPixel(right, y),
                    bins
            );
        }

        int bestKey = 0;
        int bestCount = -1;

        for (java.util.Map.Entry<Integer, Integer> entry :
                bins.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                bestKey = entry.getKey();
            }
        }

        int red =
                ((bestKey >> 10) & 31)
                        * 255 / 31;
        int green =
                ((bestKey >> 5) & 31)
                        * 255 / 31;
        int blue =
                (bestKey & 31)
                        * 255 / 31;

        int max =
                Math.max(
                        red,
                        Math.max(
                                green,
                                blue
                        )
                );

        int min =
                Math.min(
                        red,
                        Math.min(
                                green,
                                blue
                        )
                );

        int luminance =
                (red * 299
                        + green * 587
                        + blue * 114) / 1000;

        if (luminance >= 248
                && max - min <= 8) {
            red = 255;
            green = 255;
            blue = 255;
            luminance = 255;
        } else if (luminance <= 8) {
            red = 0;
            green = 0;
            blue = 0;
            luminance = 0;
        }

        block.backgroundColor =
                Color.rgb(
                        red,
                        green,
                        blue
                );

        block.textColor =
                luminance >= 145
                        ? Color.rgb(25, 25, 28)
                        : Color.WHITE;

        block.sourceTextSizePx =
                Math.max(
                        dp(10),
                        Math.min(
                                dp(20),
                                r.height() * 0.70f
                        )
                );

        block.solidBackground = true;
    }

    private void addColorSample(
            int color,
            java.util.HashMap<Integer, Integer> bins
    ) {
        int r5 =
                Color.red(color)
                        * 31 / 255;
        int g5 =
                Color.green(color)
                        * 31 / 255;
        int b5 =
                Color.blue(color)
                        * 31 / 255;

        int key =
                (r5 << 10)
                        | (g5 << 5)
                        | b5;

        Integer current = bins.get(key);

        bins.put(
                key,
                current == null
                        ? 1
                        : current + 1
        );
    }

    private void showBubble() {
        if (bubble != null) {
            return;
        }

        bubble = new TextView(this);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(12);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTypeface(
                android.graphics.Typeface.DEFAULT_BOLD
        );
        bubble.setContentDescription(
                "실시간 번역 켜기 또는 끄기"
        );

        styleBubble(liveEnabled);

        int type =
                Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

        bubbleParams =
                new WindowManager.LayoutParams(
                        dp(50),
                        dp(50),
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                );

        bubbleParams.gravity =
                Gravity.TOP | Gravity.START;
        bubbleParams.x =
                Math.max(
                        dp(8),
                        captureWidth - dp(62)
                );
        bubbleParams.y = dp(270);

        final float[] downX = new float[1];
        final float[] downY = new float[1];
        final int[] startX = new int[1];
        final int[] startY = new int[1];

        bubble.setOnTouchListener(
                (v, event) -> {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX[0] = event.getRawX();
                            downY[0] = event.getRawY();
                            startX[0] = bubbleParams.x;
                            startY[0] = bubbleParams.y;
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            bubbleParams.x =
                                    startX[0]
                                            + Math.round(
                                            event.getRawX()
                                                    - downX[0]
                                    );
                            bubbleParams.y =
                                    startY[0]
                                            + Math.round(
                                            event.getRawY()
                                                    - downY[0]
                                    );

                            windowManager.updateViewLayout(
                                    bubble,
                                    bubbleParams
                            );
                            return true;

                        case MotionEvent.ACTION_UP:
                            float dx =
                                    event.getRawX()
                                            - downX[0];
                            float dy =
                                    event.getRawY()
                                            - downY[0];

                            if (Math.hypot(dx, dy)
                                    < dp(10)) {
                                setLiveEnabled(
                                        !liveEnabled
                                );
                            }
                            return true;

                        default:
                            return false;
                    }
                }
        );

        windowManager.addView(
                bubble,
                bubbleParams
        );
    }

    private void styleBubble(boolean enabled) {
        if (bubble == null) {
            return;
        }

        bubble.setText(
                enabled
                        ? "한"
                        : "OFF"
        );

        GradientDrawable background =
                new GradientDrawable();

        background.setShape(
                GradientDrawable.OVAL
        );

        background.setColor(
                enabled
                        ? Color.rgb(34, 111, 91)
                        : Color.rgb(55, 60, 70)
        );

        background.setStroke(
                dp(1),
                Color.argb(
                        180,
                        255,
                        255,
                        255
                )
        );

        bubble.setBackground(background);
    }

    private void setLiveEnabled(boolean enabled) {
        displayGate.invalidate();
        translationPending = false;
        translationRetryAfterMs = 0L;
        if (translationEngine != null) translationEngine.cancelPending();
        captureRequested = false;
        processing = false;
        cleanCaptureRequested = true;
        lastMonitorFingerprint = null;
        activePatchBounds.clear();
        liveEnabled = enabled;

        if (captureHandler != null) {
            captureHandler.removeCallbacks(liveLoop);
        }

        mainHandler.post(() ->
                styleBubble(enabled)
        );

        if (patchManager != null) {
            mainHandler.post(
                    patchManager::clear
            );
        }

        if (enabled
                && captureHandler != null) {
            captureHandler.post(liveLoop);
        }
    }

    private int dp(int value) {
        return Math.round(
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    @Override
    public void onDestroy() {
        liveEnabled = false;
        displayGate.invalidate();
        translationPending = false;
        translationRetryAfterMs = 0L;
        if (translationEngine != null) translationEngine.cancelPending();
        captureRequested = false;
        processing = false;
        cleanCaptureRequested = true;
        lastMonitorFingerprint = null;
        activePatchBounds.clear();

        try {
            unregisterReceiver(systemUiReceiver);
        } catch (Exception ignored) {
        }

        if (captureHandler != null) {
            captureHandler.removeCallbacks(liveLoop);
        }

        if (patchManager != null) {
            patchManager.clear();
            patchManager = null;
        }

        if (bubble != null) {
            try {
                windowManager.removeView(bubble);
            } catch (Exception ignored) {
            }
            bubble = null;
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        for (TextRecognizer recognizer :
                recognizers) {
            recognizer.close();
        }
        recognizers.clear();

        if (translationEngine != null) {
            translationEngine.close();
            translationEngine = null;
        }

        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
