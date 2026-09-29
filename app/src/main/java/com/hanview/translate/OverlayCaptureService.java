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
    private static final long LIVE_INTERVAL_MS = 420L;

    private static volatile OverlayCaptureService instance;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private WindowManager.LayoutParams bubbleParams;
    private TextView bubble;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private HandlerThread captureThread;
    private Handler captureHandler;

    // 0 Latin, 1 Chinese, 2 Japanese, 3 Devanagari
    private final List<TextRecognizer> recognizers =
            new ArrayList<>();

    private TranslationEngine translationEngine;

    private volatile boolean captureRequested = false;
    private volatile boolean processing = false;
    private volatile boolean liveEnabled = false;
    private volatile int liveGeneration = 0;
    private volatile long latestFrameId = 0L;

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
                captureRequested = true;
            }

            captureHandler.postDelayed(
                    this,
                    LIVE_INTERVAL_MS
            );
        }
    };

    private final Runnable immediateRefresh = () -> {
        if (!liveEnabled || captureHandler == null) {
            return;
        }

        // Invalidate all older OCR/translation callbacks immediately.
        latestFrameId++;

        if (!processing) {
            captureRequested = true;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        windowManager =
                (WindowManager) getSystemService(WINDOW_SERVICE);

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

        translationEngine =
                new TranslationEngine(this);

        captureThread =
                new HandlerThread("viewnyang-live-capture");
        captureThread.start();
        captureHandler =
                new Handler(captureThread.getLooper());
    }

    public static void notifyScreenChanged() {
        OverlayCaptureService current = instance;
        if (current == null
                || current.captureHandler == null
                || !current.liveEnabled) {
            return;
        }

        current.captureHandler.removeCallbacks(
                current.immediateRefresh
        );
        current.captureHandler.postDelayed(
                current.immediateRefresh,
                55L
        );
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

        if (!ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        if (!ViewNyangAccessibilityService.isRunning()) {
            Toast.makeText(
                    this,
                    "뷰냥 접근성 번역 권한을 먼저 켜주세요.",
                    Toast.LENGTH_LONG
            ).show();
            stopSelf();
            return START_NOT_STICKY;
        }

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
            startProjection(
                    resultCode,
                    resultData
            );

            liveEnabled = true;
            liveGeneration++;
            latestFrameId++;

            showBubble();
            ViewNyangAccessibilityService.clearTranslations();

            captureHandler.removeCallbacks(liveLoop);
            captureHandler.postDelayed(
                    liveLoop,
                    90L
            );
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
                Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.O
                        ? new Notification.Builder(
                                this,
                                CHANNEL_ID
                        )
                        : new Notification.Builder(this);

        Notification notification =
                builder
                        .setContentTitle(
                                "뷰냥 실시간 번역"
                        )
                        .setContentText(
                                "화면의 외국어를 한국어로 바꾸는 중"
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
                < Build.VERSION_CODES.O) {
            return;
        }

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
                        image =
                                reader.acquireLatestImage();

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
                        final long frameId =
                                ++latestFrameId;

                        Bitmap bitmap =
                                imageToBitmap(image);

                        processBitmap(
                                bitmap,
                                generation,
                                frameId
                        );
                    } catch (Exception ignored) {
                        captureRequested = false;
                        processing = false;
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

    private Bitmap imageToBitmap(
            Image image
    ) {
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
            int generation,
            long frameId
    ) {
        InputImage input =
                InputImage.fromBitmap(
                        bitmap,
                        0
                );

        int[] primaryIndexes =
                new int[]{0, 1, 2};

        AtomicInteger remaining =
                new AtomicInteger(
                        primaryIndexes.length
                );

        List<OcrBlock> candidates =
                Collections.synchronizedList(
                        new ArrayList<>()
                );

        for (int index : primaryIndexes) {
            TextRecognizer recognizer =
                    recognizers.get(index);

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
                        if (remaining.decrementAndGet() != 0) {
                            return;
                        }

                        if (!candidates.isEmpty()) {
                            finishRecognition(
                                    candidates,
                                    generation,
                                    frameId,
                                    bitmap
                            );
                        } else {
                            runDevanagariFallback(
                                    input,
                                    bitmap,
                                    generation,
                                    frameId
                            );
                            return;
                        }

                        bitmap.recycle();
                    });
        }
    }

    private void runDevanagariFallback(
            InputImage input,
            Bitmap bitmap,
            int generation,
            long frameId
    ) {
        TextRecognizer recognizer =
                recognizers.get(3);

        recognizer.process(input)
                .addOnSuccessListener(text ->
                        finishRecognition(
                                extractForeignLines(text),
                                generation,
                                frameId,
                                bitmap
                        )
                )
                .addOnCompleteListener(task -> {
                    if (!bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                });
    }

    private void finishRecognition(
            List<OcrBlock> candidates,
            int generation,
            long frameId,
            Bitmap screenshot
    ) {
        if (!isCurrent(
                generation,
                frameId
        )) {
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

        List<OcrBlock> normalized =
                new ArrayList<>();

        for (OcrBlock old : blocks) {
            if (old.original == null
                    || old.original.trim().isEmpty()) {
                continue;
            }

            OcrBlock block =
                    new OcrBlock(
                            normalized.size(),
                            old.original,
                            old.bounds
                    );

            sampleVisualStyle(
                    screenshot,
                    block
            );

            normalized.add(block);

            if (normalized.size() >= 70) {
                break;
            }
        }

        // OCR is complete. Let the next screen frame start immediately while
        // translation for this frame happens asynchronously.
        processing = false;

        if (normalized.isEmpty()) {
            if (isCurrent(
                    generation,
                    frameId
            )) {
                ViewNyangAccessibilityService
                        .clearTranslations();
            }
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
                        if (!isCurrent(
                                generation,
                                frameId
                        )) {
                            return;
                        }

                        ViewNyangAccessibilityService
                                .showTranslations(
                                        translated
                                );
                    }

                    @Override
                    public void onError(
                            String message
                    ) {
                        // Keep live mode quiet. The next frame retries.
                    }
                }
        );
    }

    private boolean isCurrent(
            int generation,
            long frameId
    ) {
        return liveEnabled
                && generation == liveGeneration
                && frameId == latestFrameId;
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

                if (rect.width() < dp(8)
                        || rect.height() < dp(8)) {
                    continue;
                }

                out.add(
                        new OcrBlock(
                                id++,
                                value,
                                rect
                        )
                );

                if (out.size() >= 80) {
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

            if (Character.isLetter(c)
                    && !isHangul(c)) {
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
            OcrBlock existing =
                    blocks.get(i);

            if (intersectionOverUnion(
                    existing.bounds,
                    candidate.bounds
            ) < 0.56f) {
                continue;
            }

            if (candidateScore(candidate.original)
                    > candidateScore(existing.original)) {
                blocks.set(
                        i,
                        candidate
                );
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

        if (right <= left
                || bottom <= top) {
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

    private int candidateScore(
            String value
    ) {
        int score =
                value == null
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
                        r.left - margin
                );
        int top =
                Math.max(
                        0,
                        r.top - margin
                );
        int right =
                Math.min(
                        screenshot.getWidth() - 1,
                        r.right + margin
                );
        int bottom =
                Math.min(
                        screenshot.getHeight() - 1,
                        r.bottom + margin
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
                    screenshot.getPixel(
                            x,
                            top
                    ),
                    bins
            );
            addColorSample(
                    screenshot.getPixel(
                            x,
                            bottom
                    ),
                    bins
            );
        }

        for (int y = top;
             y <= bottom;
             y += stepY) {
            addColorSample(
                    screenshot.getPixel(
                            left,
                            y
                    ),
                    bins
            );
            addColorSample(
                    screenshot.getPixel(
                            right,
                            y
                    ),
                    bins
            );
        }

        int bestKey = 0;
        int bestCount = -1;

        for (java.util.Map.Entry<Integer, Integer> entry :
                bins.entrySet()) {
            if (entry.getValue()
                    > bestCount) {
                bestCount =
                        entry.getValue();
                bestKey =
                        entry.getKey();
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

        // Snap neutral UI backgrounds to clean white/black/gray so the result
        // looks like browser translation rather than a tinted sticker.
        if (luminance >= 225
                && max - min <= 36) {
            red = 255;
            green = 255;
            blue = 255;
            luminance = 255;
        } else if (luminance <= 28) {
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
                        ? Color.rgb(
                                25,
                                25,
                                28
                        )
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

        Integer current =
                bins.get(key);

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
        bubble.setTextColor(
                Color.WHITE
        );
        bubble.setTextSize(12);
        bubble.setGravity(
                Gravity.CENTER
        );
        bubble.setTypeface(
                android.graphics.Typeface
                        .DEFAULT_BOLD
        );
        bubble.setContentDescription(
                "실시간 번역 켜기 또는 끄기"
        );

        styleBubble(liveEnabled);

        int type =
                Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams
                                .TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams
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
                Gravity.TOP
                        | Gravity.START;

        bubbleParams.x =
                Math.max(
                        dp(8),
                        captureWidth
                                - dp(62)
                );

        bubbleParams.y =
                dp(270);

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

                            if (Math.hypot(
                                    dx,
                                    dy
                            ) < dp(10)) {
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

    private void styleBubble(
            boolean enabled
    ) {
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
                        ? Color.rgb(
                                34,
                                111,
                                91
                        )
                        : Color.rgb(
                                55,
                                60,
                                70
                        )
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

        bubble.setBackground(
                background
        );
    }

    private void setLiveEnabled(
            boolean enabled
    ) {
        if (captureHandler == null) {
            return;
        }

        liveGeneration++;
        latestFrameId++;
        captureRequested = false;
        processing = false;
        liveEnabled = enabled;

        captureHandler.removeCallbacks(
                liveLoop
        );
        captureHandler.removeCallbacks(
                immediateRefresh
        );

        mainHandler.post(
                () -> styleBubble(enabled)
        );

        ViewNyangAccessibilityService
                .clearTranslations();

        if (enabled) {
            captureHandler.post(
                    liveLoop
            );
        }
    }

    private int dp(
            int value
    ) {
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
        latestFrameId++;
        captureRequested = false;
        processing = false;

        if (instance == this) {
            instance = null;
        }

        ViewNyangAccessibilityService
                .clearTranslations();

        if (captureHandler != null) {
            captureHandler.removeCallbacks(
                    liveLoop
            );
            captureHandler.removeCallbacks(
                    immediateRefresh
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
    public IBinder onBind(
            Intent intent
    ) {
        return null;
    }
}
