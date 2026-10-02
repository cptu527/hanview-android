package com.hanview.translate;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String START_PATH = "/webtoon/18187/1520683";
    private static final String[] SITE_HOSTS = {
            "toki31.com",
            "sbxh9.com",
            "newtoki1.org"
    };

    private static final Pattern EPISODE_PATTERN =
            Pattern.compile("^/webtoon/\\d+/\\d+/?$");

    private WebView webView;
    private TextView lockButton;
    private boolean readerLocked = true;

    private float downX;
    private float downY;
    private boolean moved;
    private int touchSlop;
    private int activeHostIndex = 0;
    private boolean failoverInProgress = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);

        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        setContentView(buildUi());
        configureWebView();

        if (savedInstanceState == null) {
            webView.loadUrl(buildSiteUrl(SITE_HOSTS[activeHostIndex], START_PATH));
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(4), 0, dp(4), 0);
        toolbar.setBackgroundColor(Color.WHITE);

        toolbar.addView(action("‹", v -> goBack()));
        toolbar.addView(action("홈", v -> webView.loadUrl(buildSiteUrl(SITE_HOSTS[activeHostIndex], "/"))));
        toolbar.addView(action("새로고침", v -> webView.reload()));

        TextView spacer = new TextView(this);
        toolbar.addView(spacer, new LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
        ));

        lockButton = action("", v -> {
            readerLocked = !readerLocked;
            updateLockUi();
            applyReaderGuard();
            Toast.makeText(
                    this,
                    readerLocked
                            ? "읽기 잠금 ON · 탭 이동/자동스크롤 차단"
                            : "읽기 잠금 OFF · 사이트 버튼 사용 가능",
                    Toast.LENGTH_SHORT
            ).show();
        });
        toolbar.addView(lockButton);

        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        updateLockUi();
        return root;
    }

    private TextView action(String label, View.OnClickListener listener) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(Color.rgb(35, 39, 47));
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setMinHeight(dp(48));
        view.setOnClickListener(listener);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setMediaPlaybackRequiresUserGesture(true);

        String ua = settings.getUserAgentString();
        if (ua != null) {
            ua = ua.replace("; wv", "")
                    .replace("Version/4.0 ", "");
            settings.setUserAgentString(ua);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setOnLongClickListener(v -> readerLocked);

        webView.setOnTouchListener((v, event) -> {
            if (!readerLocked) {
                return false;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    moved = false;
                    return false;

                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - downX) > touchSlop
                            || Math.abs(event.getY() - downY) > touchSlop) {
                        moved = true;
                    }
                    return false;

                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        return true;
                    }
                    return false;

                default:
                    return false;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();

                if ("http".equalsIgnoreCase(scheme)
                        || "https".equalsIgnoreCase(scheme)) {
                    return false;
                }

                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                readerLocked = isEpisodeUrl(url);
                updateLockUi();
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    if (tryNextSiteHost(request.getUrl())) {
                        return;
                    }
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                failoverInProgress = false;
                syncActiveHost(url);
                readerLocked = isEpisodeUrl(url);
                updateLockUi();
                applyReaderGuard();
            }
        });
    }

    private String buildSiteUrl(String host, String pathAndQuery) {
        if (pathAndQuery == null || pathAndQuery.isEmpty()) {
            pathAndQuery = "/";
        }
        if (!pathAndQuery.startsWith("/")) {
            pathAndQuery = "/" + pathAndQuery;
        }
        return "https://" + host + pathAndQuery;
    }

    private void syncActiveHost(String url) {
        if (url == null) return;
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return;
            for (int i = 0; i < SITE_HOSTS.length; i++) {
                if (SITE_HOSTS[i].equalsIgnoreCase(host)) {
                    activeHostIndex = i;
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private boolean tryNextSiteHost(Uri failedUri) {
        if (failedUri == null || failoverInProgress) {
            return false;
        }

        String failedHost = failedUri.getHost();
        int failedIndex = -1;
        for (int i = 0; i < SITE_HOSTS.length; i++) {
            if (SITE_HOSTS[i].equalsIgnoreCase(failedHost)) {
                failedIndex = i;
                break;
            }
        }

        if (failedIndex < 0 || failedIndex >= SITE_HOSTS.length - 1) {
            return false;
        }

        activeHostIndex = failedIndex + 1;
        StringBuilder path = new StringBuilder(
                failedUri.getEncodedPath() == null || failedUri.getEncodedPath().isEmpty()
                        ? "/"
                        : failedUri.getEncodedPath()
        );
        if (failedUri.getEncodedQuery() != null) {
            path.append("?").append(failedUri.getEncodedQuery());
        }
        if (failedUri.getEncodedFragment() != null) {
            path.append("#").append(failedUri.getEncodedFragment());
        }

        failoverInProgress = true;
        String nextUrl = buildSiteUrl(SITE_HOSTS[activeHostIndex], path.toString());
        Toast.makeText(
                this,
                "접속 주소를 자동으로 바꿔 다시 연결할게요.",
                Toast.LENGTH_SHORT
        ).show();
        webView.post(() -> {
            failoverInProgress = false;
            webView.loadUrl(nextUrl);
        });
        return true;
    }

    private boolean isEpisodeUrl(String url) {
        if (url == null) return false;

        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            return path != null && EPISODE_PATTERN.matcher(path).matches();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateLockUi() {
        if (lockButton == null) return;
        lockButton.setText(readerLocked ? "잠금 ON" : "잠금 OFF");
    }

    private void applyReaderGuard() {
        if (webView == null) return;

        String locked = readerLocked ? "true" : "false";
        String script = """
                (function () {
                  window.__viewNyangLocked = %s;

                  if (!window.__viewNyangGuardInstalled) {
                    window.__viewNyangGuardInstalled = true;

                    var originalScrollTo = window.scrollTo.bind(window);
                    var originalScrollBy = window.scrollBy.bind(window);
                    var originalScrollIntoView = Element.prototype.scrollIntoView;

                    window.scrollTo = function () {
                      if (window.__viewNyangLocked) return;
                      return originalScrollTo.apply(window, arguments);
                    };

                    window.scrollBy = function () {
                      if (window.__viewNyangLocked) return;
                      return originalScrollBy.apply(window, arguments);
                    };

                    Element.prototype.scrollIntoView = function () {
                      if (window.__viewNyangLocked) return;
                      return originalScrollIntoView.apply(this, arguments);
                    };

                    var stopTap = function (event) {
                      if (!window.__viewNyangLocked) return;
                      event.preventDefault();
                      event.stopPropagation();
                      if (event.stopImmediatePropagation) {
                        event.stopImmediatePropagation();
                      }
                    };

                    document.addEventListener('click', stopTap, true);
                    document.addEventListener('dblclick', stopTap, true);
                    document.addEventListener('contextmenu', stopTap, true);

                    var style = document.createElement('style');
                    style.id = 'viewnyang-reader-style';
                    style.textContent =
                      'html{scroll-behavior:auto!important;}' +
                      'body{-webkit-tap-highlight-color:transparent!important;}';
                    document.documentElement.appendChild(style);
                  }
                })();
                """.formatted(locked);

        webView.evaluateJavascript(script, null);
    }

    private void goBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            webView.saveState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
