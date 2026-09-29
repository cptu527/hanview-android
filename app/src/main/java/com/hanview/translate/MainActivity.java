package com.hanview.translate;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_OVERLAY = 1002;
    private static final int REQ_NOTIFICATIONS = 1003;

    private TextView overlayStatus;
    private TextView aiStatus;
    private EditText endpointInput;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(TranslationEngine.PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
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

        TextView title = text("HanView", 30, Color.rgb(20, 24, 32));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = text("중국 앱 화면을 읽고 자연스러운 한국어로 바로 덮어 보여주는 번역 도구", 16, Color.rgb(83, 91, 105));
        LinearLayout.LayoutParams subLp = lp();
        subLp.topMargin = dp(8);
        subLp.bottomMargin = dp(26);
        root.addView(sub, subLp);

        root.addView(sectionTitle("1. 화면 위 표시 권한"));
        overlayStatus = infoBox("");
        root.addView(overlayStatus, spaced());

        Button overlayButton = button("다른 앱 위에 표시 권한 열기");
        overlayButton.setOnClickListener(v -> requestOverlayPermission());
        root.addView(overlayButton, spaced());

        root.addView(sectionTitle("2. AI 고급 번역 서버"));
        root.addView(text("AI 서버 주소가 있으면 문맥을 보고 자연스럽게 번역합니다. 아직 주소가 없으면 기기 내 번역으로 자동 대체됩니다.", 14, Color.rgb(83, 91, 105)), spaced());

        endpointInput = new EditText(this);
        endpointInput.setHint("https://.../translate");
        endpointInput.setText(prefs.getString(TranslationEngine.KEY_ENDPOINT, ""));
        endpointInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpointInput.setSingleLine(true);
        endpointInput.setTextSize(14);
        endpointInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        endpointInput.setBackgroundColor(Color.WHITE);
        root.addView(endpointInput, spaced());

        Button saveEndpoint = button("AI 서버 주소 저장");
        saveEndpoint.setOnClickListener(v -> {
            String endpoint = endpointInput.getText().toString().trim();
            prefs.edit().putString(TranslationEngine.KEY_ENDPOINT, endpoint).apply();
            updateStatus();
            Toast.makeText(this, endpoint.isEmpty() ? "기기 내 번역 모드로 저장했어요." : "AI 번역 서버를 저장했어요.", Toast.LENGTH_SHORT).show();
        });
        root.addView(saveEndpoint, spaced());

        aiStatus = infoBox("");
        root.addView(aiStatus, spaced());

        root.addView(sectionTitle("3. 번역 시작"));
        Button start = button("번역 시작");
        start.setTextSize(18);
        start.setPadding(dp(16), dp(16), dp(16), dp(16));
        start.setOnClickListener(v -> startTranslation());
        root.addView(start, spaced());

        Button stop = button("번역 종료");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, OverlayCaptureService.class);
            i.setAction(OverlayCaptureService.ACTION_STOP);
            startService(i);
        });
        root.addView(stop, spaced());

        TextView guide = infoBox("사용법\n① 권한 허용\n② 번역 시작을 누르고 화면 캡처를 허용\n③ 타오바오로 돌아가서 떠 있는 ‘번역’ 버튼을 누르기\n④ 중국어 위치 위에 한국어 번역이 표시됨");
        LinearLayout.LayoutParams guideLp = spaced();
        guideLp.topMargin = dp(26);
        root.addView(guide, guideLp);

        TextView privacy = text("화면 캡처는 번역 버튼을 눌렀을 때만 처리합니다. OpenAI 같은 AI 서비스의 비밀 키는 APK에 저장하지 않습니다.", 12, Color.rgb(112, 119, 132));
        LinearLayout.LayoutParams privacyLp = lp();
        privacyLp.topMargin = dp(18);
        root.addView(privacy, privacyLp);

        return scroll;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        if (overlayStatus == null) return;
        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        overlayStatus.setText(overlay ? "✓ 화면 위 표시 권한이 허용되어 있어요." : "권한이 필요해요. 아래 버튼을 눌러 허용해 주세요.");
        String endpoint = prefs.getString(TranslationEngine.KEY_ENDPOINT, "").trim();
        aiStatus.setText(endpoint.isEmpty() ? "현재: 기기 내 번역 모드 (AI 서버 연결 전)" : "현재: AI 고급 번역 모드\n" + endpoint);
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
        } else {
            Toast.makeText(this, "이미 허용되어 있어요.", Toast.LENGTH_SHORT).show();
        }
    }

    private void startTranslation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 화면 위 표시 권한을 허용해 주세요.", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }

        prefs.edit().putString(TranslationEngine.KEY_ENDPOINT, endpointInput.getText().toString().trim()).apply();
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, OverlayCaptureService.class);
            service.setAction(OverlayCaptureService.ACTION_START);
            service.putExtra(OverlayCaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(OverlayCaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
            Toast.makeText(this, "HanView가 켜졌어요. 타오바오에서 ‘번역’ 버튼을 눌러보세요.", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != getPackageManager().PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private TextView sectionTitle(String value) {
        TextView t = text(value, 18, Color.rgb(28, 33, 43));
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams p = lp();
        p.topMargin = dp(12);
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
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
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
