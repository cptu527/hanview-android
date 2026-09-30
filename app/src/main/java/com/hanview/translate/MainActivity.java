package com.hanview.translate;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_OVERLAY = 1002;
    private static final int REQ_NOTIFICATIONS = 1003;

    private TextView overlayStatus;
    private TextView localModelStatus;
    private Button localModelButton;
    private volatile boolean localModelDownloading = false;
    private TextView updateBadge;
    private Button updateButton;
    private AppUpdateManager updateManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        updateManager = new AppUpdateManager(
                this,
                new AppUpdateManager.Listener() {
                    @Override
                    public void onChecking() {
                        if (updateButton != null) {
                            updateButton.setText("업데이트 확인 중...");
                            updateButton.setEnabled(false);
                        }
                    }

                    @Override
                    public void onUpdateAvailable(String versionName) {
                        if (updateBadge != null) {
                            updateBadge.setVisibility(View.VISIBLE);
                            updateBadge.setText("● 새 업데이트 있음  v" + versionName);
                        }
                        if (updateButton != null) {
                            updateButton.setEnabled(true);
                            updateButton.setText("업데이트 받기");
                        }
                    }

                    @Override
                    public void onUpToDate() {
                        if (updateBadge != null) {
                            updateBadge.setVisibility(View.GONE);
                        }
                        if (updateButton != null) {
                            updateButton.setEnabled(true);
                            updateButton.setText("업데이트 확인");
                        }
                    }

                    @Override
                    public void onError() {
                        if (updateButton != null) {
                            updateButton.setEnabled(true);
                            updateButton.setText("업데이트 확인");
                        }
                    }
                }
        );
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();

        // Prepare Japanese/Chinese/English on-device translation models early so
        // shopping pages such as Taobao do not wait for model setup after capture.
        TranslationEngine.prewarmCommon(this);

        // Quiet automatic check. It only shows UI when a newer build exists.
        getWindow().getDecorView().postDelayed(() -> updateManager.checkForUpdate(false), 900);
    }

    private View buildUi() {
        int pad = dp(22);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(248, 249, 251));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(28), pad, dp(40));
        scroll.addView(root);

        TextView title = text("뷰냥", 32, Color.rgb(20, 24, 32));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = text(
                "화면 위의 외국어를 실시간으로 읽고 자연스러운 한국어로 보여줘요.",
                16,
                Color.rgb(83, 91, 105)
        );
        LinearLayout.LayoutParams subLp = lp();
        subLp.topMargin = dp(8);
        subLp.bottomMargin = dp(24);
        root.addView(sub, subLp);

        TextView liveInfo = infoBox(
                "무료 자연 번역\n" +
                "• ChatGPT/API를 사용하지 않아 추가 사용량이나 요금이 없음\n" +
                "• 일본어 세로 문장은 오른쪽→왼쪽 실제 읽는 순서대로 처리\n" +
                "• 번역 뒤 한국어식 어순·구두점·상투적인 직역 표현을 한 번 더 정리\n" +
                "• 중국어·일본어·영어 등 여러 언어를 기기에서 한국어로 번역\n" +
                "• 한 번 번역한 페이지는 기기에 저장해 빠르게 다시 표시"
        );
        root.addView(liveInfo);

        TextView localInfo = text(
                "번역은 기기 내 무료 모델로 처리합니다. 최초 1회 언어 모델을 내려받을 때만 인터넷이 필요하고, 이후에는 ChatGPT 플랜이나 API를 사용하지 않습니다.",
                12,
                Color.rgb(112, 119, 132)
        );
        root.addView(localInfo, spaced());

        root.addView(sectionTitle("고급 문맥 번역"));
        localModelStatus = infoBox("");
        root.addView(localModelStatus, spaced());

        localModelButton =
                button("고품질 문맥 모델 다운로드 (약 1GB)");
        localModelButton.setOnClickListener(v ->
                downloadLocalContextModel()
        );
        root.addView(
                localModelButton,
                spaced()
        );

        TextView modelInfo = text(
                "선택 기능입니다. 약 2.6GB의 Qwen3.5 4B 로컬 언어모델이 화면 전체 OCR과 이전 페이지 문맥, 빠른 초벌번역을 함께 보고 오역을 고친 뒤 한국어 만화처럼 다시 다듬습니다. 모델은 휴대폰 안에서만 실행되며 ChatGPT/API 사용량이나 추가 요금이 없습니다. 최초 실행 최적화 캐시까지 고려해 저장공간은 6GB 이상 여유를 권장합니다.",
                12,
                Color.rgb(112, 119, 132)
        );
        root.addView(modelInfo, spaced());

        root.addView(sectionTitle("화면 위 표시 권한"));
        overlayStatus = infoBox("");
        root.addView(overlayStatus, spaced());

        Button overlayButton = button("다른 앱 위에 표시 허용");
        overlayButton.setOnClickListener(v -> requestOverlayPermission());
        root.addView(overlayButton, spaced());

        root.addView(sectionTitle("실시간 번역"));
        Button start = button("실시간 번역 시작");
        start.setTextSize(18);
        start.setPadding(dp(16), dp(16), dp(16), dp(16));
        start.setOnClickListener(v -> startTranslation());
        root.addView(start, spaced());

        Button stop = button("번역 완전히 종료");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, OverlayCaptureService.class);
            i.setAction(OverlayCaptureService.ACTION_STOP);
            startService(i);
            Toast.makeText(this, "뷰냥 번역을 종료했어요.", Toast.LENGTH_SHORT).show();
        });
        root.addView(stop, spaced());

        TextView guide = infoBox(
                "사용법\n" +
                "① 위 권한을 한 번 허용\n" +
                "② 실시간 번역 시작 → 화면 공유 허용\n" +
                "③ 원하는 앱으로 돌아가 그냥 스크롤\n" +
                "④ ‘ON’은 대기, ‘OCR…’은 글자 인식, ‘번역…’은 번역 중, ‘초’는 빠른 초벌번역, ‘문’은 고급 문맥 보정 완료 상태"
        );
        LinearLayout.LayoutParams guideLp = spaced();
        guideLp.topMargin = dp(24);
        root.addView(guide, guideLp);

        root.addView(sectionTitle("업데이트"));

        updateBadge = text("● 새 업데이트 있음", 15, Color.rgb(220, 38, 38));
        updateBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        updateBadge.setVisibility(View.GONE);
        root.addView(updateBadge, spaced());

        TextView version = infoBox(
                "현재 버전 " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
        );
        root.addView(version, spaced());

        updateButton = button("업데이트 확인");
        updateButton.setOnClickListener(v -> updateManager.checkForUpdate(true));
        root.addView(updateButton, spaced());

        TextView privacy = text(
                "화면은 번역을 위해서만 읽습니다. 번역은 기기 내 모델로 처리하며 ChatGPT/API로 화면 내용을 보내지 않습니다. 번역 레이어는 원래 앱을 직접 수정하지 않고 화면 위에 표시됩니다.",
                12,
                Color.rgb(112, 119, 132)
        );
        LinearLayout.LayoutParams privacyLp = lp();
        privacyLp.topMargin = dp(20);
        root.addView(privacy, privacyLp);

        return scroll;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        if (updateManager != null) {
            updateManager.resumePendingUpdateFlow();
        }
    }

    private void updateStatus() {
        boolean overlay =
                Build.VERSION.SDK_INT
                        < Build.VERSION_CODES.M
                        || Settings.canDrawOverlays(this);

        if (overlayStatus != null) {
            overlayStatus.setText(
                    overlay
                            ? "✓ 화면 위 표시 권한이 허용되어 있어요."
                            : "권한이 필요해요. 아래 버튼을 눌러 허용해 주세요."
            );
        }

        if (!localModelDownloading
                && localModelStatus != null
                && localModelButton != null) {
            boolean modelReady =
                    LocalContextTranslator
                            .isModelReady(this);

            localModelStatus.setText(
                    modelReady
                            ? "✓ 4B 고급 문맥 모델 설치됨\n빠른 번역을 먼저 띄운 뒤 일본어 만화는 뒤에서 문맥·말투를 깊게 보정합니다."
                            : "고급 문맥 모델 미설치\n현재는 가벼운 기기 번역을 사용합니다."
            );

            localModelButton.setVisibility(
                    modelReady
                            ? View.GONE
                            : View.VISIBLE
            );
        }
    }

    private void downloadLocalContextModel() {
        if (localModelDownloading) {
            return;
        }

        localModelDownloading = true;

        if (localModelButton != null) {
            localModelButton.setEnabled(false);
            localModelButton.setText(
                    "문맥 모델 다운로드 준비 중..."
            );
        }

        if (localModelStatus != null) {
            localModelStatus.setText(
                    "Qwen3.5 4B 문맥 모델 다운로드를 시작합니다. 약 2.6GB이며 Wi-Fi와 6GB 이상의 여유공간을 권장해요."
            );
        }

        LocalContextTranslator.downloadModel(
                this,
                new LocalContextTranslator.DownloadCallback() {
                    @Override
                    public void onProgress(int percent) {
                        runOnUiThread(() -> {
                            if (localModelStatus != null) {
                                localModelStatus.setText(
                                        "고급 문맥 모델 다운로드 중... "
                                                + percent
                                                + "%"
                                );
                            }
                            if (localModelButton != null) {
                                localModelButton.setText(
                                        "다운로드 중 "
                                                + percent
                                                + "%"
                                );
                            }
                        });
                    }

                    @Override
                    public void onSuccess() {
                        runOnUiThread(() -> {
                            localModelDownloading = false;

                            Toast.makeText(
                                    MainActivity.this,
                                    "고급 문맥 번역 모델 설치 완료!",
                                    Toast.LENGTH_LONG
                            ).show();

                            if (localModelButton != null) {
                                localModelButton.setEnabled(true);
                                localModelButton.setText(
                                        "고품질 문맥 모델 다운로드 (약 1GB)"
                                );
                            }

                            updateStatus();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            localModelDownloading = false;

                            if (localModelStatus != null) {
                                localModelStatus.setText(
                                        "문맥 모델 다운로드 실패\n"
                                                + message
                                );
                            }

                            if (localModelButton != null) {
                                localModelButton.setEnabled(true);
                                localModelButton.setText(
                                        "다시 다운로드"
                                );
                            }

                            Toast.makeText(
                                    MainActivity.this,
                                    message,
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }
                }
        );
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Intent i = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(i, REQ_OVERLAY);
        } else {
            Toast.makeText(this, "이미 허용되어 있어요.", Toast.LENGTH_SHORT).show();
        }
    }

    private void startTranslation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Toast.makeText(
                    this,
                    "먼저 화면 위 표시 권한을 허용해 주세요.",
                    Toast.LENGTH_LONG
            ).show();
            requestOverlayPermission();
            return;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        startActivityForResult(
                manager.createScreenCaptureIntent(),
                REQ_CAPTURE
        );
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_CAPTURE
                && resultCode == RESULT_OK
                && data != null) {
            Intent service = new Intent(this, OverlayCaptureService.class);
            service.setAction(OverlayCaptureService.ACTION_START);
            service.putExtra(OverlayCaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(OverlayCaptureService.EXTRA_RESULT_DATA, data);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }

            Toast.makeText(
                    this,
                    "뷰냥 실시간 번역을 켰어요.",
                    Toast.LENGTH_SHORT
            ).show();

            moveTaskToBack(true);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != getPackageManager().PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS
            );
        }
    }

    @Override
    protected void onDestroy() {
        if (updateManager != null) {
            updateManager.destroy();
        }
        super.onDestroy();
    }

    private TextView sectionTitle(String value) {
        TextView t = text(value, 18, Color.rgb(28, 33, 43));
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        LinearLayout.LayoutParams p = lp();
        p.topMargin = dp(18);
        p.bottomMargin = dp(4);
        t.setLayoutParams(p);

        return t;
    }

    private TextView infoBox(String value) {
        TextView t = text(value, 14, Color.rgb(55, 62, 74));
        t.setBackgroundColor(Color.WHITE);
        t.setPadding(dp(14), dp(13), dp(14), dp(13));
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextSize(15);
        return b;
    }

    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0f, 1.15f);
        return t;
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams p = lp();
        p.topMargin = dp(9);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
