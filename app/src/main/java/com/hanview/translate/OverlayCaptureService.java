package com.hanview.translate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

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
    public static final String ACTION_START =
            "com.hanview.translate.START";
    public static final String ACTION_STOP =
            "com.hanview.translate.STOP";
    public static final String EXTRA_RESULT_CODE =
            "result_code";
    public static final String EXTRA_RESULT_DATA =
            "result_data";

    private static final String CHANNEL_ID =
            "viewnyang_live_translation";
    private static final int NOTIFICATION_ID = 527;

    private static final long LIVE_INTERVAL_MS = 300L;
    private static final long OVERLAY_HIDE_BEFORE_CAPTURE_MS = 20L;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private WindowManager.LayoutParams bubbleParams;
    private TextView bubble;
    private TranslationOverlayView translationOverlay;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;

    private final List<TextRecognizer> recognizers =
            new ArrayList<>();

    private TranslationEngine translationEngine;

    private volatile boolean captureRequested = false;
    private volatile boolean processing = false;
    private volatile boolean liveEnabled = false;
    private volatile int liveGeneration = 0;

    private int captureWidth;
    private int captureHeight;
    private int densityDpi;

    private final Runnable liveLoop = new Runnable() {
        @Override
        public void run() {
            if (!liveEnabled || captureHandler == null) {
                return;
            }

            if (!processing && !captureRequested) {
                prepareLiveCapture();
            }

            captureHandler.postDelayed(
                    this,
                    LIVE_INTERVAL_MS
            );
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        windowManager =
                (WindowManager) getSystemService(WINDOW_SERVICE);

        // Multiple bundled recognizers let ViewNyang cover the common scripts
        // seen in shopping, social, travel and community apps.
        recognizers.add(
                TextRecognition.getClient(
                        TextRecognizerOptions.DEFAULT_OPTIONS
                )
        );
        recognizers.add(
                TextRecognition.getClient(
                        new ChineseTextRecognizerOptions.Builder().build()
                )
        );
        recognizers.add(
                TextRecognition.getClient(
                        new JapaneseTextRecognizerOptions.Builder().build()
                )
        );
        recognizers.add(
                TextRecognition.getClient(
                        new DevanagariTextRecognizerOptions.Builder().build()
                )
        );

        translationEngine = new TranslationEngine(this);

        captureThread =
                new HandlerThread("viewnyang-live-capture");
        captureThread.start();
        captureHandler =
                new Handler(captureThread.getLooper());
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(intent.getAction())) {
            int resultCode =
                    intent.getIntExtra(
                            EXTRA_RESULT_CODE,
                            0
                    );

            @SuppressWarnings("deprecation")
            Intent resultData =
                    intent.getParcelableExtra(
                            EXTRA_RESULT_DATA
                    );

            if (resultCode == 0 || resultData == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            startAsForeground();

            if (mediaProjection == null) {
                startProjection(resultCode, resultData);

                // Start live mode immediately. The floating button must never begin as OFF
                // after the user already chose "실시간 번역 시작".
                liveEnabled = true;
                liveGeneration++;

                showTranslationOverlay();
                showBubble();

                captureHandler.postDelayed(
                        liveLoop,
                        80L
                );
            }
        }

        return START_STICKY;
    }

    private void startAsForeground() {
        createNotificationChannel();

        Intent open =
                new Intent(this, MainActivity.class);

        PendingIntent pending =
                PendingIntent.getActivity(
                        this,
                        0,
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        Notification.Builder builder =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(
                        this,
                        CHANNEL_ID
                )
                        : new Notification.Builder(this);

        Notification notification =
                builder
                        .setContentTitle("뷰냥 실시간 번역")
                        .setContentText(
                                "화면의 외국어를 한국어로 따라 번역 중"
                        )
                        .setSmallIcon(
                                android.R.drawable.ic_menu_view
                        )
                        .setOngoing(true)
                        .setContentIntent(pending)
                        .build();

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo
                            .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(
                    NOTIFICATION_ID,
                    notification
            );
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "뷰냥 실시간 번역",
                            NotificationManager.IMPORTANCE_LOW
                    );

            channel.setDescription(
                    "실시간 화면 번역을 실행하는 동안 표시됩니다."
            );

            getSystemService(
                    NotificationManager.class
            ).createNotificationChannel(channel);
        }
    }

    @SuppressWarnings("deprecation")
    private void startProjection(
            int resultCode,
            Intent resultData
    ) {
        DisplayMetrics metrics =
                new DisplayMetrics();

        windowManager
                .getDefaultDisplay()
                .getRealMetrics(metrics);

        captureWidth = metrics.widthPixels;
        captureHeight = metrics.heightPixels;
        densityDpi = metrics.densityDpi;

        MediaProjectionManager manager =
                (MediaProjectionManager)
                        getSystemService(
                                Context.MEDIA_PROJECTION_SERVICE
                        );

        mediaProjection =
                manager.getMediaProjection(
                        resultCode,
                        resultData
                );

        mediaProjection.registerCallback(
                new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        mainHandler.post(
                                () -> stopSelf()
                        );
                    }
                },
                captureHandler
        );

        imageReader =
                ImageReader.newInstance(
                        captureWidth,
                        captureHeight,
                        PixelFormat.RGBA_8888,
                        2
                );

        imageReader.setOnImageAvailableListener(
                reader -> {
                    Image image = null;

                    try {
                        image = reader.acquireLatestImage();

                        if (image == null
                                || !captureRequested
                                || processing
                                || !liveEnabled) {
                            return;
                        }

                        captureRequested = false;
                        processing = true;

                        final int generation =
                                liveGeneration;

                        Bitmap bitmap =
                                imageToBitmap(image);

                        mainHandler.post(() -> {
                            if (translationOverlay != null
                                    && liveEnabled) {
                                translationOverlay
                                        .setVisibility(
                                                View.VISIBLE
                                        );
                            }
                        });

                        processBitmap(
                                bitmap,
                                generation
                        );
                    } catch (Exception ignored) {
                        captureRequested = false;
                        processing = false;

                        mainHandler.post(() -> {
                            if (translationOverlay != null
                                    && liveEnabled) {
                                translationOverlay
                                        .setVisibility(
                                                View.VISIBLE
                                        );
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

        virtualDisplay =
                mediaProjection.createVirtualDisplay(
                        "ViewNyangLiveScreen",
                        captureWidth,
                        captureHeight,
                        densityDpi,
                        DisplayManager
                                .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.getSurface(),
                        null,
                        captureHandler
                );
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane =
                image.getPlanes()[0];

        ByteBuffer buffer =
                plane.getBuffer();

        int pixelStride =
                plane.getPixelStride();

        int rowStride =
                plane.getRowStride();

        int rowPadding =
                rowStride
                        - pixelStride * captureWidth;

        int paddedWidth =
                captureWidth
                        + rowPadding / pixelStride;

        Bitmap padded =
                Bitmap.createBitmap(
                        paddedWidth,
                        captureHeight,
                        Bitmap.Config.ARGB_8888
                );

        padded.copyPixelsFromBuffer(buffer);

        Bitmap result =
                Bitmap.createBitmap(
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
            int generation
    ) {
        InputImage input =
                InputImage.fromBitmap(
                        bitmap,
                        0
                );

        // Taobao and similar shopping apps are overwhelmingly Chinese.
        // Run the Chinese recognizer first instead of waiting for four OCR engines.
        TextRecognizer chineseRecognizer = recognizers.get(1);

        chineseRecognizer.process(input)
                .addOnSuccessListener(text -> {
                    List<OcrBlock> chinese =
                            extractForeignLines(text);

                    if (!chinese.isEmpty()) {
                        finishRecognition(
                                chinese,
                                generation,
                                bitmap
                        );
                        bitmap.recycle();
                    } else {
                        runFallbackRecognizers(
                                input,
                                bitmap,
                                generation
                        );
                    }
                })
                .addOnFailureListener(e ->
                        runFallbackRecognizers(
                                input,
                                bitmap,
                                generation
                        )
                );
    }

    private void runFallbackRecognizers(
            InputImage input,
            Bitmap bitmap,
            int generation
    ) {
        AtomicInteger remaining =
                new AtomicInteger(
                        Math.max(0, recognizers.size() - 1)
                );

        List<OcrBlock> candidates =
                Collections.synchronizedList(
                        new ArrayList<>()
                );

        if (remaining.get() == 0) {
            finishRecognition(
                    candidates,
                    generation,
                    bitmap
            );
            bitmap.recycle();
            return;
        }

        for (int i = 0; i < recognizers.size(); i++) {
            if (i == 1) {
                continue;
            }

            TextRecognizer recognizer = recognizers.get(i);

            recognizer.process(input)
                    .addOnSuccessListener(text -> {
                        List<OcrBlock> found =
                                extractForeignLines(text);

                        synchronized (candidates) {
                            for (OcrBlock block : found) {
                                addOrReplaceOverlapping(
                                        candidates,
                                        block
                                );
                            }
                        }
                    })
                    .addOnCompleteListener(task -> {
                        if (remaining.decrementAndGet() == 0) {
                            finishRecognition(
                                    candidates,
                                    generation,
                                    bitmap
                            );
                            bitmap.recycle();
                        }
                    });
        }
    }

    private void finishRecognition(
            List<OcrBlock> candidates,
            int generation,
            Bitmap screenshot
    ) {
        if (!isCurrentGeneration(generation)) {
            processing = false;
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

        // Re-number after merging recognizers.
        List<OcrBlock> normalized =
                new ArrayList<>();

        for (int i = 0; i < blocks.size(); i++) {
            OcrBlock old = blocks.get(i);

            OcrBlock normalizedBlock =
                    new OcrBlock(
                            i,
                            old.original,
                            old.bounds
                    );

            normalizedBlock.copyVisualStyleFrom(old);
            normalized.add(normalizedBlock);
        }

        for (OcrBlock block : normalized) {
            sampleVisualStyle(
                    screenshot,
                    block
            );
        }

        if (normalized.isEmpty()) {
            processing = false;

            mainHandler.post(() -> {
                if (translationOverlay != null
                        && isCurrentGeneration(generation)) {
                    translationOverlay.clearBlocks();
                }
            });

            return;
        }

        translationEngine.translate(
                normalized,
                new TranslationEngine.Callback() {
                    @Override
                    public void onSuccess(
                            List<OcrBlock> translated,
                            boolean usedAi
                    ) {
                        processing = false;

                        if (!isCurrentGeneration(
                                generation
                        )) {
                            return;
                        }

                        mainHandler.post(() -> {
                            if (translationOverlay != null
                                    && isCurrentGeneration(
                                    generation
                            )) {
                                translationOverlay
                                        .setBlocks(
                                                translated
                                        );
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        processing = false;
                    }
                }
        );
    }

    private List<OcrBlock> extractForeignLines(
            Text result
    ) {
        List<OcrBlock> out =
                new ArrayList<>();

        int id = 0;

        for (Text.TextBlock block :
                result.getTextBlocks()) {
            for (Text.Line line :
                    block.getLines()) {
                String value =
                        line.getText() == null
                                ? ""
                                : line.getText().trim();

                Rect rect =
                        line.getBoundingBox();

                if (rect == null
                        || value.isEmpty()
                        || !hasForeignLetters(value)
                        || isKoreanDominant(value)) {
                    continue;
                }

                if (rect.width() < dp(14)
                        || rect.height() < dp(9)) {
                    continue;
                }

                out.add(
                        new OcrBlock(
                                id++,
                                value,
                                rect
                        )
                );

                if (out.size() >= 70) {
                    return out;
                }
            }
        }

        return out;
    }

    private boolean hasForeignLetters(
            String value
    ) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (!Character.isLetter(c)) {
                continue;
            }

            if (!isHangul(c)) {
                return true;
            }
        }

        return false;
    }

    private boolean isKoreanDominant(
            String value
    ) {
        int letters = 0;
        int hangul = 0;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (Character.isLetter(c)) {
                letters++;

                if (isHangul(c)) {
                    hangul++;
                }
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
            OcrBlock existing =
                    blocks.get(i);

            if (intersectionOverUnion(
                    existing.bounds,
                    candidate.bounds
            ) < 0.58f) {
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
        int left =
                Math.max(a.left, b.left);

        int top =
                Math.max(a.top, b.top);

        int right =
                Math.min(a.right, b.right);

        int bottom =
                Math.min(a.bottom, b.bottom);

        if (right <= left || bottom <= top) {
            return 0f;
        }

        long intersection =
                (long) (right - left)
                        * (bottom - top);

        long union =
                (long) a.width() * a.height()
                        + (long) b.width() * b.height()
                        - intersection;

        if (union <= 0) {
            return 0f;
        }

        return (float) intersection
                / (float) union;
    }

    private int candidateScore(String value) {
        int score = value.length();

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (c >= '\u3040' && c <= '\u30FF') {
                score += 12;
            } else if ((c >= '\u3400' && c <= '\u4DBF')
                    || (c >= '\u4E00' && c <= '\u9FFF')) {
                score += 6;
            } else if (c >= '\u0900' && c <= '\u097F') {
                score += 8;
            }
        }

        return score;
    }

    private void sampleVisualStyle(
            Bitmap screenshot,
            OcrBlock block
    ) {
        Rect r = block.bounds;

        int margin = Math.max(2, dp(2));
        int left = Math.max(0, r.left - margin);
        int top = Math.max(0, r.top - margin);
        int right = Math.min(screenshot.getWidth() - 1, r.right + margin);
        int bottom = Math.min(screenshot.getHeight() - 1, r.bottom + margin);

        int[] colors = new int[64];
        int count = 0;

        // Sample a ring around the OCR bounds. This mostly sees the real card/page
        // background rather than the glyph pixels themselves.
        for (int x = left; x <= right && count < colors.length; x += Math.max(1, (right - left) / 12 + 1)) {
            colors[count++] = screenshot.getPixel(x, top);
            if (count < colors.length) {
                colors[count++] = screenshot.getPixel(x, bottom);
            }
        }

        for (int y = top; y <= bottom && count < colors.length; y += Math.max(1, (bottom - top) / 8 + 1)) {
            colors[count++] = screenshot.getPixel(left, y);
            if (count < colors.length) {
                colors[count++] = screenshot.getPixel(right, y);
            }
        }

        if (count == 0) {
            block.backgroundColor = Color.WHITE;
            block.textColor = Color.rgb(28, 28, 30);
            block.sourceTextSizePx = Math.max(dp(10), r.height() * 0.68f);
            block.solidBackground = true;
            return;
        }

        int[] rs = new int[count];
        int[] gs = new int[count];
        int[] bs = new int[count];

        for (int i = 0; i < count; i++) {
            rs[i] = Color.red(colors[i]);
            gs[i] = Color.green(colors[i]);
            bs[i] = Color.blue(colors[i]);
        }

        java.util.Arrays.sort(rs);
        java.util.Arrays.sort(gs);
        java.util.Arrays.sort(bs);

        int mid = count / 2;
        int red = rs[mid];
        int green = gs[mid];
        int blue = bs[mid];

        block.backgroundColor = Color.rgb(red, green, blue);

        int minLum = 255;
        int maxLum = 0;

        for (int i = 0; i < count; i++) {
            int lum = (rs[i] * 299 + gs[i] * 587 + bs[i] * 114) / 1000;
            minLum = Math.min(minLum, lum);
            maxLum = Math.max(maxLum, lum);
        }

        int backgroundLum =
                (red * 299 + green * 587 + blue * 114) / 1000;

        block.solidBackground =
                (maxLum - minLum) < 48;

        block.textColor =
                backgroundLum >= 145
                        ? Color.rgb(28, 28, 30)
                        : Color.WHITE;

        block.sourceTextSizePx =
                Math.max(
                        dp(10),
                        Math.min(
                                dp(19),
                                r.height() * 0.70f
                        )
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
                        ? WindowManager
                        .LayoutParams
                        .TYPE_APPLICATION_OVERLAY
                        : WindowManager
                        .LayoutParams
                        .TYPE_PHONE;

        bubbleParams =
                new WindowManager.LayoutParams(
                        dp(50),
                        dp(50),
                        type,
                        WindowManager.LayoutParams
                                .FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams
                                .FLAG_LAYOUT_NO_LIMITS,
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

        final float[] downX =
                new float[1];

        final float[] downY =
                new float[1];

        final int[] startX =
                new int[1];

        final int[] startY =
                new int[1];

        bubble.setOnTouchListener(
                (v, event) -> {
                    switch (
                            event.getActionMasked()
                    ) {
                        case MotionEvent.ACTION_DOWN:
                            downX[0] =
                                    event.getRawX();
                            downY[0] =
                                    event.getRawY();
                            startX[0] =
                                    bubbleParams.x;
                            startY[0] =
                                    bubbleParams.y;
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

                            windowManager
                                    .updateViewLayout(
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
                enabled ? "한" : "OFF"
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

    private void showTranslationOverlay() {
        if (translationOverlay != null) {
            return;
        }

        translationOverlay =
                new TranslationOverlayView(this);

        int type =
                Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.O
                        ? WindowManager
                        .LayoutParams
                        .TYPE_APPLICATION_OVERLAY
                        : WindowManager
                        .LayoutParams
                        .TYPE_PHONE;

        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams
                                .MATCH_PARENT,
                        WindowManager.LayoutParams
                                .MATCH_PARENT,
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

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.S) {
            params.alpha = 0.78f;
        }

        params.gravity =
                Gravity.TOP | Gravity.START;

        windowManager.addView(
                translationOverlay,
                params
        );
    }

    private void setLiveEnabled(
            boolean enabled
    ) {
        if (captureHandler == null) {
            return;
        }

        liveGeneration++;
        captureRequested = false;
        liveEnabled = enabled;

        captureHandler.removeCallbacks(
                liveLoop
        );

        mainHandler.post(() -> {
            styleBubble(enabled);

            if (translationOverlay != null) {
                translationOverlay.setVisibility(
                        View.VISIBLE
                );

                if (!enabled) {
                    translationOverlay
                            .clearBlocks();
                }
            }
        });

        if (enabled) {
            captureHandler.post(liveLoop);
        }
    }

    private void prepareLiveCapture() {
        if (!liveEnabled
                || processing
                || captureRequested) {
            return;
        }

        mainHandler.post(() -> {
            if (translationOverlay != null
                    && liveEnabled) {
                translationOverlay.setVisibility(
                        View.INVISIBLE
                );
            }
        });

        captureHandler.postDelayed(
                () -> {
                    if (!liveEnabled
                            || processing) {
                        mainHandler.post(() -> {
                            if (translationOverlay
                                    != null
                                    && liveEnabled) {
                                translationOverlay
                                        .setVisibility(
                                                View.VISIBLE
                                        );
                            }
                        });

                        return;
                    }

                    captureRequested = true;
                },
                OVERLAY_HIDE_BEFORE_CAPTURE_MS
        );
    }

    private boolean isCurrentGeneration(
            int generation
    ) {
        return liveEnabled
                && generation
                == liveGeneration;
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
        liveGeneration++;
        captureRequested = false;
        processing = false;

        if (captureHandler != null) {
            captureHandler.removeCallbacks(
                    liveLoop
            );
        }

        if (bubble != null) {
            try {
                windowManager.removeView(
                        bubble
                );
            } catch (Exception ignored) {
            }

            bubble = null;
        }

        if (translationOverlay != null) {
            try {
                windowManager.removeView(
                        translationOverlay
                );
            } catch (Exception ignored) {
            }

            translationOverlay = null;
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

        for (TextRecognizer recognizer : recognizers) {
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
