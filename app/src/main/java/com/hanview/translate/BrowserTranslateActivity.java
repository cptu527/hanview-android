package com.hanview.translate;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class BrowserTranslateActivity extends Activity {
    private static final String DEFAULT_URL = "https://hitomi.la/";

    private WebView webView;
    private EditText address;
    private FrameLayout webContainer;
    private FrameLayout translationLayer;
    private TextView status;
    private Button translateButton;

    private TextRecognizer recognizer;
    private ChatGptPlanClient chatGpt;

    private volatile boolean translating = false;
    private int pageGeneration = 0;
    private int lastScrollY = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        chatGpt = new ChatGptPlanClient(this);
        recognizer = TextRecognition.getClient(
                new JapaneseTextRecognizerOptions.Builder().build()
        );

        setContentView(buildUi());

        String initial =
                getIntent().getStringExtra("url");

        loadUrl(
                initial == null || initial.trim().isEmpty()
                        ? DEFAULT_URL
                        : initial
        );
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 19, 22));

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setPadding(dp(8), dp(8), dp(8), dp(4));

        Button back = smallButton("‹");
        back.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        addressRow.addView(back, fixed(dp(44), dp(42)));

        Button forward = smallButton("›");
        forward.setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });
        addressRow.addView(forward, fixed(dp(44), dp(42)));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(14);
        address.setTextColor(Color.rgb(30, 32, 36));
        address.setHintTextColor(Color.rgb(130, 135, 145));
        address.setHint("주소 입력");
        address.setBackgroundColor(Color.WHITE);
        address.setPadding(dp(10), 0, dp(10), 0);
        address.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter =
                    event != null
                            && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            && event.getAction() == KeyEvent.ACTION_DOWN;

            if (enter || event == null) {
                openTypedAddress();
                return true;
            }
            return false;
        });

        LinearLayout.LayoutParams addressLp =
                new LinearLayout.LayoutParams(
                        0,
                        dp(42),
                        1f
                );
        addressLp.leftMargin = dp(4);
        addressLp.rightMargin = dp(4);
        addressRow.addView(address, addressLp);

        Button go = smallButton("이동");
        go.setTextSize(12);
        go.setOnClickListener(v -> openTypedAddress());
        addressRow.addView(go, fixed(dp(58), dp(42)));

        root.addView(
                addressRow,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(dp(8), dp(2), dp(8), dp(6));

        Button reload = smallButton("새로고침");
        reload.setTextSize(12);
        reload.setOnClickListener(v -> {
            clearTranslations();
            webView.reload();
        });
        actionRow.addView(
                reload,
                new LinearLayout.LayoutParams(
                        0,
                        dp(40),
                        1f
                )
        );

        Button connect = smallButton("ChatGPT");
        connect.setTextSize(12);
        connect.setOnClickListener(v ->
                startActivity(
                        new Intent(
                                this,
                                GptTranslationLabActivity.class
                        )
                )
        );
        LinearLayout.LayoutParams connectLp =
                new LinearLayout.LayoutParams(
                        0,
                        dp(40),
                        1f
                );
        connectLp.leftMargin = dp(6);
        actionRow.addView(connect, connectLp);

        translateButton = smallButton("이 화면 번역");
        translateButton.setTextSize(12);
        translateButton.setOnClickListener(v ->
                translateVisiblePage()
        );
        LinearLayout.LayoutParams translateLp =
                new LinearLayout.LayoutParams(
                        0,
                        dp(40),
                        1.2f
                );
        translateLp.leftMargin = dp(6);
        actionRow.addView(translateButton, translateLp);

        root.addView(
                actionRow,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(Color.rgb(205, 209, 218));
        status.setPadding(dp(12), dp(3), dp(12), dp(6));
        status.setText("뷰냥 브라우저 · 화면 공유 없이 번역");
        root.addView(
                status,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        webContainer = new FrameLayout(this);
        webContainer.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        configureWebView();

        webContainer.addView(
                webView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        translationLayer = new FrameLayout(this);
        translationLayer.setClickable(false);
        translationLayer.setFocusable(false);
        webContainer.addView(
                translationLayer,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        root.addView(
                webContainer,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        return root;
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setMediaPlaybackRequiresUserGesture(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(
                new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(
                            WebView view,
                            WebResourceRequest request
                    ) {
                        Uri uri = request.getUrl();
                        String scheme = uri.getScheme();

                        if ("http".equalsIgnoreCase(scheme)
                                || "https".equalsIgnoreCase(scheme)) {
                            clearTranslations();
                            view.loadUrl(uri.toString());
                            return true;
                        }

                        try {
                            startActivity(
                                    new Intent(
                                            Intent.ACTION_VIEW,
                                            uri
                                    )
                            );
                        } catch (Exception ignored) {
                        }
                        return true;
                    }

                    @Override
                    public void onPageStarted(
                            WebView view,
                            String url,
                            Bitmap favicon
                    ) {
                        pageGeneration++;
                        clearTranslations();
                        address.setText(url);
                        status.setText("페이지 불러오는 중…");
                    }

                    @Override
                    public void onPageFinished(
                            WebView view,
                            String url
                    ) {
                        address.setText(url);
                        status.setText(
                                chatGpt.hasPlanAccess()
                                        ? "준비됨 · ‘이 화면 번역’을 눌러 주세요."
                                        : "ChatGPT 연결이 필요합니다."
                        );
                    }
                }
        );

        if (android.os.Build.VERSION.SDK_INT >= 23) {
            webView.setOnScrollChangeListener(
                    (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                        if (Math.abs(scrollY - lastScrollY) >= dp(12)) {
                            clearTranslations();
                            lastScrollY = scrollY;
                        }
                    }
            );
        }
    }

    private void openTypedAddress() {
        String value =
                address.getText()
                        .toString()
                        .trim();

        if (value.isEmpty()) {
            return;
        }

        if (!value.startsWith("http://")
                && !value.startsWith("https://")) {
            value = "https://" + value;
        }

        loadUrl(value);
        hideKeyboard();
    }

    private void loadUrl(
            String url
    ) {
        clearTranslations();
        webView.loadUrl(url);
        address.setText(url);
    }

    private void translateVisiblePage() {
        if (translating) {
            return;
        }

        if (!chatGpt.hasPlanAccess()) {
            Toast.makeText(
                    this,
                    "먼저 ChatGPT를 연결해 주세요.",
                    Toast.LENGTH_LONG
            ).show();

            startActivity(
                    new Intent(
                            this,
                            GptTranslationLabActivity.class
                    )
            );
            return;
        }

        if (webView.getWidth() <= 0
                || webView.getHeight() <= 0) {
            Toast.makeText(
                    this,
                    "페이지가 아직 준비되지 않았어요.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        clearTranslations();
        translating = true;
        translateButton.setEnabled(false);
        status.setText("화면 글자 읽는 중…");

        final int generation =
                ++pageGeneration;

        Bitmap bitmap =
                Bitmap.createBitmap(
                        webView.getWidth(),
                        webView.getHeight(),
                        Bitmap.Config.ARGB_8888
                );

        Canvas canvas =
                new Canvas(bitmap);

        webView.draw(canvas);

        recognizer.process(
                        InputImage.fromBitmap(
                                bitmap,
                                0
                        )
                )
                .addOnSuccessListener(
                        result ->
                                handleOcrResult(
                                        result,
                                        generation
                                )
                )
                .addOnFailureListener(
                        error -> {
                            finishTranslationUi();
                            status.setText("글자 인식 실패");
                            Toast.makeText(
                                    BrowserTranslateActivity.this,
                                    "화면 글자 인식에 실패했어요.",
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                )
                .addOnCompleteListener(
                        task -> bitmap.recycle()
                );
    }

    private void handleOcrResult(
            Text result,
            int generation
    ) {
        if (generation != pageGeneration) {
            finishTranslationUi();
            return;
        }

        List<OcrBlock> cjkBlocks =
                new ArrayList<>();
        List<OcrBlock> otherBlocks =
                new ArrayList<>();

        int id = 0;

        for (Text.TextBlock textBlock :
                result.getTextBlocks()) {
            Rect bounds =
                    textBlock.getBoundingBox();

            String source =
                    textBlock.getText() == null
                            ? ""
                            : textBlock.getText().trim();

            if (bounds == null
                    || source.length() < 2) {
                continue;
            }

            OcrBlock block =
                    new OcrBlock(
                            id++,
                            source,
                            bounds
                    );

            block.verticalSource =
                    bounds.height()
                            > bounds.width() * 1.35f;

            if (containsCjk(source)) {
                cjkBlocks.add(block);
            } else {
                otherBlocks.add(block);
            }

            if (id >= 60) {
                break;
            }
        }

        List<OcrBlock> blocks =
                !cjkBlocks.isEmpty()
                        ? cjkBlocks
                        : otherBlocks;

        if (blocks.isEmpty()) {
            finishTranslationUi();
            status.setText("번역할 글자를 찾지 못했어요.");
            return;
        }

        status.setText(
                "ChatGPT 서버 번역 중… "
                        + blocks.size()
                        + "개 문단"
        );

        chatGpt.translate(
                blocks,
                new ChatGptPlanClient.TranslationCallback() {
                    @Override
                    public void onSuccess(
                            Map<Integer, String> translations,
                            String model
                    ) {
                        runOnUiThread(() -> {
                            if (generation != pageGeneration) {
                                finishTranslationUi();
                                return;
                            }

                            showTranslations(
                                    blocks,
                                    translations
                            );

                            finishTranslationUi();

                            status.setText(
                                    "번역 완료 · "
                                            + shortModelName(model)
                            );
                        });
                    }

                    @Override
                    public void onError(
                            String message
                    ) {
                        runOnUiThread(() -> {
                            if (generation != pageGeneration) {
                                finishTranslationUi();
                                return;
                            }

                            finishTranslationUi();
                            status.setText("서버 번역 실패");

                            Toast.makeText(
                                    BrowserTranslateActivity.this,
                                    message,
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }
                }
        );
    }

    private void showTranslations(
            List<OcrBlock> blocks,
            Map<Integer, String> translations
    ) {
        clearTranslations();

        List<OcrBlock> translated =
                new ArrayList<>();

        int verticalCount = 0;

        for (OcrBlock block : blocks) {
            String value =
                    translations.get(
                            block.id
                    );

            if (value == null
                    || value.trim().isEmpty()) {
                continue;
            }

            block.translated =
                    value.trim();
            translated.add(block);

            if (block.verticalSource) {
                verticalCount++;
            }
        }

        if (translated.isEmpty()) {
            status.setText(
                    "번역 결과가 비어 있어요."
            );
            return;
        }

        if (verticalCount >= 2
                || translated.size() >= 5) {
            showMangaPanel(
                    translated
            );
            return;
        }

        for (OcrBlock block : translated) {
            addInlineTranslation(
                    block
            );
        }
    }

    private void showMangaPanel(
            List<OcrBlock> blocks
    ) {
        List<OcrBlock> ordered =
                new ArrayList<>(blocks);

        ordered.sort(
                new Comparator<OcrBlock>() {
                    @Override
                    public int compare(
                            OcrBlock a,
                            OcrBlock b
                    ) {
                        boolean av =
                                a.verticalSource;
                        boolean bv =
                                b.verticalSource;

                        if (av && bv) {
                            int byX =
                                    Integer.compare(
                                            b.bounds.centerX(),
                                            a.bounds.centerX()
                                    );

                            if (byX != 0) {
                                return byX;
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
                }
        );

        StringBuilder body =
                new StringBuilder();

        for (OcrBlock block : ordered) {
            if (block.translated == null
                    || block.translated.trim().isEmpty()) {
                continue;
            }

            if (body.length() > 0) {
                body.append("\n\n");
            }

            body.append(
                    block.translated.trim()
            );
        }

        TextView panel =
                new TextView(this);

        panel.setText(
                body.toString()
        );
        panel.setTextColor(Color.WHITE);
        panel.setTextSize(15);
        panel.setLineSpacing(dp(3), 1.12f);
        panel.setPadding(
                dp(14),
                dp(12),
                dp(14),
                dp(12)
        );
        panel.setBackgroundColor(
                Color.argb(
                        235,
                        15,
                        16,
                        18
                )
        );
        panel.setMaxHeight(
                Math.max(
                        dp(160),
                        webContainer.getHeight() * 42 / 100
                )
        );
        panel.setVerticalScrollBarEnabled(true);
        panel.setMovementMethod(
                new ScrollingMovementMethod()
        );
        panel.setClickable(true);

        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                );

        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        lp.bottomMargin = dp(8);

        translationLayer.addView(
                panel,
                lp
        );
    }

    private void addInlineTranslation(
            OcrBlock block
    ) {
        TextView view =
                new TextView(this);

        view.setText(
                block.translated
        );
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        view.setPadding(
                dp(7),
                dp(5),
                dp(7),
                dp(5)
        );
        view.setBackgroundColor(
                Color.argb(
                        232,
                        16,
                        17,
                        20
                )
        );

        int containerWidth =
                Math.max(
                        dp(160),
                        webContainer.getWidth()
                );

        int left =
                Math.max(
                        0,
                        Math.min(
                                block.bounds.left,
                                containerWidth - dp(100)
                        )
                );

        int width =
                Math.min(
                        containerWidth - left,
                        Math.max(
                                dp(110),
                                Math.min(
                                        dp(260),
                                        block.bounds.width() * 3
                                )
                        )
                );

        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(
                        width,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                );

        lp.leftMargin = left;
        lp.topMargin =
                Math.max(
                        0,
                        block.bounds.top
                );

        translationLayer.addView(
                view,
                lp
        );
    }

    private boolean containsCjk(
            String value
    ) {
        for (int i = 0;
             i < value.length();
             i++) {
            char c =
                    value.charAt(i);

            if ((c >= 0x3040
                    && c <= 0x30FF)
                    || (c >= 0x3400
                    && c <= 0x4DBF)
                    || (c >= 0x4E00
                    && c <= 0x9FFF)) {
                return true;
            }
        }

        return false;
    }

    private void clearTranslations() {
        if (translationLayer != null) {
            translationLayer.removeAllViews();
        }
    }

    private void finishTranslationUi() {
        translating = false;

        if (translateButton != null) {
            translateButton.setEnabled(true);
        }
    }

    private String shortModelName(
            String model
    ) {
        if (model == null
                || model.trim().isEmpty()) {
            return "ChatGPT";
        }

        String clean =
                model.trim();

        return clean.length() <= 24
                ? clean
                : clean.substring(
                        0,
                        24
                );
    }

    private Button smallButton(
            String text
    ) {
        Button button =
                new Button(this);

        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(35, 38, 44));
        button.setBackgroundColor(Color.WHITE);
        return button;
    }

    private LinearLayout.LayoutParams fixed(
            int width,
            int height
    ) {
        return new LinearLayout.LayoutParams(
                width,
                height
        );
    }

    private void hideKeyboard() {
        View focused =
                getCurrentFocus();

        if (focused == null) {
            return;
        }

        InputMethodManager imm =
                (InputMethodManager)
                        getSystemService(
                                Context.INPUT_METHOD_SERVICE
                        );

        if (imm != null) {
            imm.hideSoftInputFromWindow(
                    focused.getWindowToken(),
                    0
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
    protected void onResume() {
        super.onResume();

        if (status != null
                && webView != null
                && !translating) {
            status.setText(
                    chatGpt.hasPlanAccess()
                            ? "준비됨 · ‘이 화면 번역’을 눌러 주세요."
                            : "ChatGPT 연결이 필요합니다."
            );
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null
                && webView.canGoBack()) {
            webView.goBack();
            return;
        }

        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (chatGpt != null) {
            chatGpt.cancelTranslations();
        }

        if (recognizer != null) {
            recognizer.close();
        }

        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }

        super.onDestroy();
    }
}
