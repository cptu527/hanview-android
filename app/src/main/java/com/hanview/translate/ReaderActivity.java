package com.hanview.translate;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Pattern;

public class ReaderActivity extends Activity {
    private static final Pattern EPISODE_PATTERN =
            Pattern.compile("^/webtoon/\\d+/[^/]+/?$");

    private WebView webView;
    private ProgressBar progressBar;
    private TextView address;
    private int touchSlop;
    private float downX;
    private float downY;
    private boolean moved;
    private boolean multiTouch;
    private long allowEpisodeNavigationUntil = 0L;
    private boolean hostFallbackAttempted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);

        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);

        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        try {
            setContentView(buildUi());
            configureWebView();

            String url = getIntent().getStringExtra("url");
            if (url == null || url.trim().isEmpty()) {
                url = "about:blank";
            }

            webView.loadUrl(url);
        } catch (Throwable error) {
            Toast.makeText(
                    this,
                    "웹 리더를 시작하지 못했어요: " + error.getClass().getSimpleName(),
                    Toast.LENGTH_LONG
            ).show();
            finish();
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom()
            );
            return insets;
        });

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), 0, dp(10), 0);

        TextView close = button("‹", 30);
        close.setOnClickListener(v -> finish());
        top.addView(close, new LinearLayout.LayoutParams(dp(50), dp(50)));

        address = new TextView(this);
        address.setTextSize(13);
        address.setTextColor(Color.rgb(70, 74, 82));
        address.setSingleLine(true);
        top.addView(address, new LinearLayout.LayoutParams(0, dp(50), 1f));

        TextView reload = button("↻", 24);
        reload.setOnClickListener(v -> webView.reload());
        top.addView(reload, new LinearLayout.LayoutParams(dp(50), dp(50)));

        root.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
        ));

        progressBar = new ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
        );
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(2)
        ));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        return root;
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);

        String ua = settings.getUserAgentString();
        if (ua != null) {
            settings.setUserAgentString(
                    ua.replace("; wv", "").replace("Version/4.0 ", "")
            );
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setOnTouchListener((v, event) -> {
            String url = webView.getUrl();
            if (!isEpisode(url)) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    moved = false;
                    multiTouch = false;

                    WebView.HitTestResult downHit = webView.getHitTestResult();
                    if (downHit != null
                            && downHit.getType() == WebView.HitTestResult.ANCHOR_TYPE) {
                        allowEpisodeNavigationUntil =
                                SystemClock.elapsedRealtime() + 1500L;
                    } else {
                        allowEpisodeNavigationUntil = 0L;
                    }
                    return false;

                case MotionEvent.ACTION_POINTER_DOWN:
                    multiTouch = true;
                    moved = true;
                    return false;

                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() > 1) {
                        multiTouch = true;
                        moved = true;
                        return false;
                    }

                    float dx = Math.abs(event.getX() - downX);
                    float dy = Math.abs(event.getY() - downY);

                    if (dx > touchSlop || dy > touchSlop) {
                        moved = true;
                    }

                    if (dx > touchSlop && dx > dy * 1.15f) {
                        return true;
                    }
                    return false;

                case MotionEvent.ACTION_UP:
                    if (!moved && !multiTouch) {
                        WebView.HitTestResult hit = webView.getHitTestResult();

                        if (hit != null
                                && (hit.getType() == WebView.HitTestResult.IMAGE_TYPE
                                || hit.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                            return true;
                        }
                    }
                    return false;

                case MotionEvent.ACTION_CANCEL:
                    moved = false;
                    multiTouch = false;
                    return false;

                default:
                    return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                progressBar.setProgress(progress);
                progressBar.setVisibility(
                        progress >= 100 ? View.GONE : View.VISIBLE
                );
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                String current = view.getUrl();
                String target = request.getUrl().toString();

                if (isEpisode(current)
                        && isEpisode(target)
                        && isDifferentEpisode(current, target)) {

                    if (SystemClock.elapsedRealtime()
                            > allowEpisodeNavigationUntil) {
                        return true;
                    }

                    allowEpisodeNavigationUntil = 0L;
                }

                return false;
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                if (request == null || !request.isForMainFrame()) {
                    return;
                }

                String failingUrl = request.getUrl().toString();
                int code = error == null ? 0 : error.getErrorCode();

                boolean connectionFailure =
                        code == ERROR_CONNECTION_RESET
                                || code == ERROR_HOST_LOOKUP
                                || code == ERROR_CONNECT
                                || code == ERROR_TIMEOUT;

                if (!hostFallbackAttempted
                        && connectionFailure
                        && isLegacyHost(failingUrl)) {

                    hostFallbackAttempted = true;
                    String fallbackUrl = switchHost(
                            failingUrl,
                            "sbxh9.com"
                    );

                    if (fallbackUrl != null) {
                        Toast.makeText(
                                ReaderActivity.this,
                                "기존 주소 연결이 끊겨 대체 주소로 다시 열게요.",
                                Toast.LENGTH_SHORT
                        ).show();

                        view.loadUrl(fallbackUrl);
                        return;
                    }
                }

                Toast.makeText(
                        ReaderActivity.this,
                        "페이지 연결에 실패했어요.",
                        Toast.LENGTH_SHORT
                ).show();
            }

            @Override
            public void onPageStarted(
                    WebView view,
                    String url,
                    android.graphics.Bitmap favicon
            ) {
                address.setText(url == null ? "" : url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                address.setText(url == null ? "" : url);

                if (isEpisode(url)) {
                    injectReaderGuard();
                }
            }

            @Override
            public boolean onRenderProcessGone(
                    WebView view,
                    RenderProcessGoneDetail detail
            ) {
                Toast.makeText(
                        ReaderActivity.this,
                        "웹 리더가 종료됐어요. 뷰냥 본체로 돌아갈게요.",
                        Toast.LENGTH_LONG
                ).show();
                finish();
                return true;
            }
        });
    }

    private boolean isLegacyHost(String url) {
        try {
            String host = Uri.parse(url).getHost();

            return host != null
                    && (host.equalsIgnoreCase("newtoki1.org")
                    || host.equalsIgnoreCase("www.newtoki1.org"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private String switchHost(String url, String newHost) {
        try {
            Uri old = Uri.parse(url);

            return old.buildUpon()
                    .scheme("https")
                    .authority(newHost)
                    .build()
                    .toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isDifferentEpisode(String first, String second) {
        try {
            String firstPath = Uri.parse(first).getPath();
            String secondPath = Uri.parse(second).getPath();

            return firstPath != null
                    && secondPath != null
                    && !firstPath.equals(secondPath);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isEpisode(String url) {
        if (url == null) return false;

        try {
            String path = Uri.parse(url).getPath();
            return path != null && EPISODE_PATTERN.matcher(path).matches();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void injectReaderGuard() {
        String script = """
                (function(){
                  if(window.__viewNyangSimpleGuard)return;
                  window.__viewNyangSimpleGuard=true;

                  function text(el){
                    if(!el)return '';
                    return [
                      el.innerText||'',
                      el.textContent||'',
                      el.getAttribute&&el.getAttribute('aria-label')||'',
                      el.getAttribute&&el.getAttribute('title')||''
                    ].join(' ').replace(/\\s+/g,' ').trim().toLowerCase();
                  }

                  function control(target){
                    return target&&target.closest
                      ? target.closest('a,button,[role="button"],[onclick]')
                      : null;
                  }

                  function allowed(target){
                    var el=control(target);
                    if(!el)return false;
                    return /(이전화|이전 화|다음화|다음 화|목록|책갈피|bookmark|previous|prev|next|list)/i.test(text(el));
                  }

                  function stop(e){
                    e.preventDefault();
                    e.stopPropagation();
                    if(e.stopImmediatePropagation)e.stopImmediatePropagation();
                  }

                  var sx=0,sy=0,moved=false;

                  document.addEventListener('touchstart',function(e){
                    if(e.touches.length!==1)return;
                    sx=e.touches[0].clientX;
                    sy=e.touches[0].clientY;
                    moved=false;
                  },true);

                  document.addEventListener('touchmove',function(e){
                    if(e.touches.length!==1)return;
                    var dx=Math.abs(e.touches[0].clientX-sx);
                    var dy=Math.abs(e.touches[0].clientY-sy);
                    if(dx>8||dy>8)moved=true;
                    if(dx>12&&dx>dy*1.15)stop(e);
                  },{capture:true,passive:false});

                  document.addEventListener('touchend',function(e){
                    if(!moved&&!allowed(e.target))stop(e);
                  },true);

                  document.addEventListener('click',function(e){
                    if(!allowed(e.target))stop(e);
                  },true);

                  var st=document.createElement('style');
                  st.textContent='html,body{overscroll-behavior-x:none!important;touch-action:pan-y pinch-zoom!important;}body{-webkit-tap-highlight-color:transparent!important;}';
                  document.documentElement.appendChild(st);
                })();
                """;

        webView.evaluateJavascript(script, null);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.setWebChromeClient(null);
                webView.setWebViewClient(null);
                webView.destroy();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    private TextView button(String label, float size) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(45, 48, 55));
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
