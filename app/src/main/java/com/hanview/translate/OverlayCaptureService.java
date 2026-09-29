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
    private int captureWidth;
    private int captureHeight;
    private int densityDpi;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        translationEngine = new TranslationEngine(this);
        captureThread = new HandlerThread("hanview-capture");
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
                showBubble();
                showTranslationOverlay();
            }
        }
        return START_STICKY;
    }

    private void startAsForeground() {
        createNotificationChannel();
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("HanView 번역 사용 중")
                .setContentText("타오바오에서 떠 있는 ‘번역’ 버튼을 누르세요.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "HanView 화면 번역", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("화면 번역을 실행하는 동안 표시됩니다.");
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

        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                mainHandler.post(() -> stopSelf());
            }
        }, captureHandler);

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null || !captureRequested || processing) return;
                captureRequested = false;
                processing = true;
                Bitmap bitmap = imageToBitmap(image);
                processBitmap(bitmap);
            } catch (Exception e) {
                processing = false;
                showError("화면을 읽지 못했어요: " + e.getMessage());
            } finally {
                if (image != null) image.close();
            }
        }, captureHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "HanViewScreen",
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
        Bitmap padded = Bitmap.createBitmap(paddedWidth, captureHeight, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap result = Bitmap.createBitmap(padded, 0, 0, captureWidth, captureHeight);
        if (result != padded) padded.recycle();
        return result;
    }

    private void processBitmap(Bitmap bitmap) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> {
                    bitmap.recycle();
                    List<OcrBlock> blocks = extractChineseBlocks(text);
                    if (blocks.isEmpty()) {
                        processing = false;
                        updateBubble("번역");
                        showToast("이 화면에서 번역할 중국어를 찾지 못했어요.");
                        return;
                    }

                    updateBubble("번역중");
                    translationEngine.translate(blocks, new TranslationEngine.Callback() {
                        @Override
                        public void onSuccess(List<OcrBlock> translated, boolean usedAi) {
                            processing = false;
                            mainHandler.post(() -> {
                                if (translationOverlay != null) translationOverlay.setBlocks(translated);
                                updateBubble(usedAi ? "AI" : "번역");
                                if (!usedAi) showToast("기기 내 번역으로 표시했어요.");
                            });
                        }
                        @Override
                        public void onError(String message) {
                            processing = false;
                            updateBubble("번역");
                            showError(message);
                        }
                    });
                })
                .addOnFailureListener(e -> {
                    bitmap.recycle();
                    processing = false;
                    updateBubble("번역");
                    showError("중국어 글자를 읽지 못했어요: " + e.getMessage());
                });
    }

    private List<OcrBlock> extractChineseBlocks(Text result) {
        List<OcrBlock> out = new ArrayList<>();
        int id = 0;
        for (Text.TextBlock block : result.getTextBlocks()) {
            String value = block.getText() == null ? "" : block.getText().trim();
            Rect rect = block.getBoundingBox();
            if (rect == null || value.isEmpty() || !containsChinese(value)) continue;
            if (rect.width() < dp(18) || rect.height() < dp(10)) continue;
            out.add(new OcrBlock(id++, value, rect));
        }
        return out;
    }

    private boolean containsChinese(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '\u3400' && c <= '\u4DBF') || (c >= '\u4E00' && c <= '\u9FFF')) return true;
        }
        return false;
    }

    private void showBubble() {
        if (bubble != null) return;
        bubble = new TextView(this);
        bubble.setText("번역");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(13);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.rgb(32, 38, 49));
        bg.setStroke(dp(2), Color.argb(210, 255, 255, 255));
        bubble.setBackground(bg);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        bubbleParams = new WindowManager.LayoutParams(dp(66), dp(66), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = Math.max(dp(8), captureWidth - dp(80));
        bubbleParams.y = dp(250);

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
                    bubbleParams.x = startX[0] + Math.round(event.getRawX() - downX[0]);
                    bubbleParams.y = startY[0] + Math.round(event.getRawY() - downY[0]);
                    windowManager.updateViewLayout(bubble, bubbleParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (Math.hypot(event.getRawX() - downX[0], event.getRawY() - downY[0]) < dp(10)) requestCapture();
                    return true;
                default:
                    return false;
            }
        });
        windowManager.addView(bubble, bubbleParams);
    }

    private void showTranslationOverlay() {
        if (translationOverlay != null) return;
        translationOverlay = new TranslationOverlayView(this);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        // Android 12+ only forwards touches through TYPE_APPLICATION_OVERLAY
        // when the obscuring opacity is at or below the platform threshold.
        // Keep the translation layer readable while allowing Taobao/1688 beneath it to scroll and tap.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.alpha = 0.78f;
        }
        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(translationOverlay, params);
    }

    private void requestCapture() {
        if (processing) {
            showToast("지금 번역 중이에요.");
            return;
        }
        updateBubble("읽는중");
        if (translationOverlay != null) translationOverlay.clearBlocks();
        captureHandler.postDelayed(() -> captureRequested = true, 180);
    }

    private void updateBubble(String text) {
        mainHandler.post(() -> { if (bubble != null) bubble.setText(text); });
    }

    private void showToast(String message) {
        mainHandler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void showError(String message) {
        mainHandler.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        captureRequested = false;
        processing = false;
        if (bubble != null) {
            try { windowManager.removeView(bubble); } catch (Exception ignored) {}
            bubble = null;
        }
        if (translationOverlay != null) {
            try { windowManager.removeView(translationOverlay); } catch (Exception ignored) {}
            translationOverlay = null;
        }
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mediaProjection != null) mediaProjection.stop();
        if (recognizer != null) recognizer.close();
        if (translationEngine != null) translationEngine.close();
        if (captureThread != null) captureThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
