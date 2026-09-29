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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class OverlayCaptureService extends Service {
    public static final String ACTION_START = "com.hanview.translate.START";
    public static final String ACTION_STOP = "com.hanview.translate.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "hanview_capture";
    private static final int NOTIFICATION_ID = 527;

    // Fast enough to feel attached to scrolling, while still leaving room for OCR on-device.
    private static final long LIVE_INTERVAL_MS = 450L;
    private static final long OVERLAY_HIDE_BEFORE_CAPTURE_MS = 65L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private WindowManager.LayoutParams bubbleParams;
    private TextView bubble;
    private TranslationOverlayView translationOverlay;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;

    private TextRecognizer recognizer;
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
            if (!liveEnabled || captureHandler == null) return;

            if (!processing && !captureRequested) {
                prepareLiveCapture();
            }

            captureHandler.postDelayed(this, LIVE_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        translationEngine = new TranslationEngine(this);

        captureThread = new HandlerThread("hanview-live-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(intent.getAction())) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);

            @SuppressWarnings("deprecation")
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);

            if (resultCode == 0 || resultData == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            startAsForeground();

            if (mediaProjection == null) {
                startProjection(resultCode, resultData);
                showTranslationOverlay();
                showBubble();

                // MainActivity moves to the background immediately. Give the previous app
                // a moment to become visible, then start translating continuously.
                mainHandler.postDelayed(() -> setLiveEnabled(true), 550L);
            }
        }

        return START_STICKY;
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

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("HanView 실시간 번역")
                .setContentText("화면을 따라 번역 중 · 떠 있는 버튼으로 바로 켜고 끌 수 있어요.")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "HanView 실시간 번역",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("실시간 화면 번역을 실행하는 동안 표시됩니다.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @SuppressWarnings("deprecation")
    private void startProjection(int resultCode, Intent resultData) {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);

        captureWidth = metrics.widthPixels;
        captureHeight = metrics.heightPixels;
        densityDpi = metrics.densityDpi;

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                mainHandler.post(() -> stopSelf());
            }
        }, captureHandler);

        imageReader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2
        );

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;

            try {
                image = reader.acquireLatestImage();

                if (image == null || !captureRequested || processing || !liveEnabled) {
                    return;
                }

                captureRequested = false;
                processing = true;

                final int generation = liveGeneration;
                Bitmap bitmap = imageToBitmap(image);

                // Bring the previous translation back immediately after the frame is captured.
                mainHandler.post(() -> {
                    if (translationOverlay != null && liveEnabled) {
                        translationOverlay.setVisibility(View.VISIBLE);
                    }
                });

                processBitmap(bitmap, generation);
            } catch (Exception e) {
                captureRequested = false;
                processing = false;
                mainHandler.post(() -> {
                    if (translationOverlay != null && liveEnabled) {
                        translationOverlay.setVisibility(View.VISIBLE);
                    }
                });
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }, captureHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "HanViewLiveScreen",
                captureWidth,
                captureHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler
        );
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

    private void processBitmap(Bitmap bitmap, int generation) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> {
                    bitmap.recycle();

                    if (!isCurrentGeneration(generation)) {
                        processing = false;
                        return;
                    }

                    List<OcrBlock> blocks = extractChineseLines(text);

                    if (blocks.isEmpty()) {
                        processing = false;
                        mainHandler.post(() -> {
                            if (translationOverlay != null && isCurrentGeneration(generation)) {
                                translationOverlay.clearBlocks();
                            }
                        });
                        return;
                    }

                    translationEngine.translate(blocks, new TranslationEngine.Callback() {
                        @Override
                        public void onSuccess(List<OcrBlock> translated, boolean usedAi) {
                            processing = false;

                            if (!isCurrentGeneration(generation)) return;

                            mainHandler.post(() -> {
                                if (translationOverlay != null && isCurrentGeneration(generation)) {
                                    translationOverlay.setBlocks(translated);
                                }
                            });
                        }

                        @Override
                        public void onError(String message) {
                            // Live mode should not spam error toasts while the screen is moving.
                            // Keep the previous frame and simply try again on the next cycle.
                            processing = false;
                        }
                    });
                })
                .addOnFailureListener(e -> {
                    bitmap.recycle();
                    processing = false;
                });
    }

    private List<OcrBlock> extractChineseLines(Text result) {
        List<OcrBlock> out = new ArrayList<>();
        int id = 0;

        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String value = line.getText() == null ? "" : line.getText().trim();
                Rect rect = line.getBoundingBox();

                if (rect == null || value.isEmpty() || !containsChinese(value)) {
                    continue;
                }

                if (rect.width() < dp(14) || rect.height() < dp(9)) {
                    continue;
                }

                out.add(new OcrBlock(id++, value, rect));

                // Avoid a pathological full screen with hundreds of tiny OCR fragments.
                if (out.size() >= 60) {
                    return out;
                }
            }
        }

        return out;
    }

    private boolean containsChinese(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if ((c >= '\u3400' && c <= '\u4DBF')
                    || (c >= '\u4E00' && c <= '\u9FFF')) {
                return true;
            }
        }

        return false;
    }

    private void showBubble() {
        if (bubble != null) return;

        bubble = new TextView(this);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(12);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bubble.setContentDescription("실시간 번역 켜기 또는 끄기");
        styleBubble(false);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        bubbleParams = new WindowManager.LayoutParams(
                dp(52),
                dp(52),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = Math.max(dp(8), captureWidth - dp(64));
        bubbleParams.y = dp(270);

        final float[] downX = new float[1];
        final float[] downY = new float[1];
        final int[] startX = new int[1];
        final int[] startY = new int[1];

        bubble.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    downY[0] = event.getRawY();
                    startX[0] = bubbleParams.x;
                    startY[0] = bubbleParams.y;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    bubbleParams.x = startX[0]
                            + Math.round(event.getRawX() - downX[0]);
                    bubbleParams.y = startY[0]
                            + Math.round(event.getRawY() - downY[0]);

                    windowManager.updateViewLayout(bubble, bubbleParams);
                    return true;

                case MotionEvent.ACTION_UP:
                    float dx = event.getRawX() - downX[0];
                    float dy = event.getRawY() - downY[0];

                    if (Math.hypot(dx, dy) < dp(10)) {
                        setLiveEnabled(!liveEnabled);
                    }

                    return true;

                default:
                    return false;
            }
        });

        windowManager.addView(bubble, bubbleParams);
    }

    private void styleBubble(boolean enabled) {
        if (bubble == null) return;

        bubble.setText(enabled ? "한" : "OFF");

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(
                enabled
                        ? Color.rgb(34, 111, 91)
                        : Color.rgb(55, 60, 70)
        );
        background.setStroke(dp(1), Color.argb(180, 255, 255, 255));

        bubble.setBackground(background);
    }

    private void showTranslationOverlay() {
        if (translationOverlay != null) return;

        translationOverlay = new TranslationOverlayView(this);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        // Android 12+ only forwards touches through an application overlay
        // below the platform's obscuring-opacity threshold.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.alpha = 0.78f;
        }

        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(translationOverlay, params);
    }

    private void setLiveEnabled(boolean enabled) {
        if (captureHandler == null) return;

        liveGeneration++;
        captureRequested = false;
        liveEnabled = enabled;

        captureHandler.removeCallbacks(liveLoop);

        mainHandler.post(() -> {
            styleBubble(enabled);

            if (translationOverlay != null) {
                translationOverlay.setVisibility(View.VISIBLE);

                if (!enabled) {
                    translationOverlay.clearBlocks();
                }
            }
        });

        if (enabled) {
            captureHandler.post(liveLoop);
        }
    }

    private void prepareLiveCapture() {
        if (!liveEnabled || processing || captureRequested) return;

        // Do not OCR HanView's own translated Korean layer.
        mainHandler.post(() -> {
            if (translationOverlay != null && liveEnabled) {
                translationOverlay.setVisibility(View.INVISIBLE);
            }
        });

        captureHandler.postDelayed(() -> {
            if (!liveEnabled || processing) {
                mainHandler.post(() -> {
                    if (translationOverlay != null && liveEnabled) {
                        translationOverlay.setVisibility(View.VISIBLE);
                    }
                });
                return;
            }

            captureRequested = true;
        }, OVERLAY_HIDE_BEFORE_CAPTURE_MS);
    }

    private boolean isCurrentGeneration(int generation) {
        return liveEnabled && generation == liveGeneration;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        liveEnabled = false;
        liveGeneration++;
        captureRequested = false;
        processing = false;

        if (captureHandler != null) {
            captureHandler.removeCallbacks(liveLoop);
        }

        if (bubble != null) {
            try {
                windowManager.removeView(bubble);
            } catch (Exception ignored) {
            }
            bubble = null;
        }

        if (translationOverlay != null) {
            try {
                windowManager.removeView(translationOverlay);
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

        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }

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
