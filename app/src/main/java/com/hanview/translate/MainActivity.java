package com.hanview.translate;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String PREFS = "viewnyang_browser";
    private static final String KEY_LAST_URL = "last_url";

    private static final String DEFAULT_URL = "about:blank";

    private static final Pattern EPISODE_PATTERN =
            Pattern.compile("^/webtoon/\\d+/[^/]+/?$");

    private WebView webView;
    private EditText addressBar;
    private ProgressBar progressBar;
    private TextView backButton;
    private TextView forwardButton;

    private boolean readerLocked = true;
    private float downX;
    private float downY;
    private boolean moved;
    private boolean multiTouch;
    private int touchSlop;
    private boolean mainFrameLoadFailed = false;

    private SharedPreferences prefs;
    private AppUpdateManager updateManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);

        int systemUiFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            systemUiFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(systemUiFlags);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        updateManager = new AppUpdateManager(this);

        setContentView(buildUi());
        configureWebView();

        if (savedInstanceState == null) {
            String lastUrl = prefs.getString(KEY_LAST_URL, "");
            webView.loadUrl(
                    lastUrl == null || lastUrl.trim().isEmpty()
                            ? DEFAULT_URL
                            : lastUrl
            );
        } else {
            webView.restoreState(savedInstanceState);
        }

        getWindow().getDecorView().postDelayed(
                () -> updateManager.checkForUpdate(false),
                1200
        );
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
        root.requestApplyInsets();

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(10), dp(7), dp(8), dp(7));
        addressRow.setBackgroundColor(Color.WHITE);

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextSize(14);
        addressBar.setTextColor(Color.rgb(35, 38, 45));
        addressBar.setHintTextColor(Color.rgb(120, 125, 135));
        addressBar.setHint("검색어 또는 주소 입력");
        addressBar.setPadding(dp(15), 0, dp(15), 0);
        addressBar.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_URI
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setSelectAllOnFocus(true);
        addressBar.setBackground(rounded(Color.rgb(242, 243, 246), 22));

        LinearLayout.LayoutParams addressLp =
                new LinearLayout.LayoutParams(0, dp(44), 1f);
        addressRow.addView(addressBar, addressLp);

        TextView go = browserButton("→", 22);
        LinearLayout.LayoutParams goLp =
                new LinearLayout.LayoutParams(dp(48), dp(44));
        goLp.leftMargin = dp(3);
        addressRow.addView(go, goLp);

        go.setOnClickListener(v -> loadTypedAddress());
        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;

            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                loadTypedAddress();
                return true;
            }
            return false;
        });

        root.addView(
                addressRow,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(58)
                )
        );

        progressBar = new ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
        );
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        root.addView(
                progressBar,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(2)
                )
        );

        webView = new WebView(this);
        root.addView(
                webView,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(4), 0, dp(4), 0);
        bottomBar.setBackgroundColor(Color.rgb(250, 250, 252));
        bottomBar.setElevation(dp(8));

        backButton = browserButton("‹", 31);
        forwardButton = browserButton("›", 31);
        TextView homeButton = browserButton("⌂", 23);
        TextView reloadButton = browserButton("↻", 25);
        TextView menuButton = browserButton("⋮", 28);

        bottomBar.addView(backButton, navLp());
        bottomBar.addView(forwardButton, navLp());
        bottomBar.addView(homeButton, navLp());
        bottomBar.addView(reloadButton, navLp());
        bottomBar.addView(menuButton, navLp());

        backButton.setOnClickListener(v -> goBack());
        forwardButton.setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });
        homeButton.setOnClickListener(v -> loadHome());
        reloadButton.setOnClickListener(v -> webView.reload());
        menuButton.setOnClickListener(this::showBrowserMenu);

        root.addView(
                bottomBar,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(56)
                )
        );

        updateNavigationButtons();
        return root;
    }

    private LinearLayout.LayoutParams navLp() {
        return new LinearLayout.LayoutParams(0, dp(56), 1f);
    }

    private TextView browserButton(String label, float textSize) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(Color.rgb(45, 48, 55));
        view.setTextSize(textSize);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(Color.TRANSPARENT);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void loadTypedAddress() {
        String value = addressBar.getText().toString().trim();
        if (value.isEmpty()) return;

        String url = normalizeAddress(value);
        hideKeyboard();
        addressBar.clearFocus();
        webView.loadUrl(url);
    }

    private String normalizeAddress(String value) {
        String input = value.trim();

        if (input.startsWith("http://") || input.startsWith("https://")) {
            return input;
        }

        if (input.startsWith("//")) {
            return "https:" + input;
        }

        boolean looksLikeDomain = input.matches(
                "(?i)^(?:[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?\\.)+[a-z]{2,63}(?::\\d{1,5})?(?:/.*)?$"
        );

        boolean looksLikeIpv4 = input.matches(
                "^\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?(?:/.*)?$"
        );

        boolean looksLikeLocalhost =
                input.matches("(?i)^localhost(?::\\d{1,5})?(?:/.*)?$");

        if (looksLikeDomain || looksLikeIpv4 || looksLikeLocalhost) {
            return "https://" + input;
        }

        return "https://www.google.com/search?q=" + Uri.encode(input);
    }

    private void loadHome() {
        String current = webView.getUrl();
        try {
            Uri uri = Uri.parse(current);
            if (uri.getHost() != null) {
                webView.loadUrl("https://" + uri.getHost() + "/");
                return;
            }
        } catch (Exception ignored) {
        }

        webView.loadUrl(DEFAULT_URL);
    }

    private void showBrowserMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);

        menu.getMenu().add(
                readerLocked
                        ? "읽기 잠금 끄기"
                        : "읽기 잠금 켜기"
        );
        menu.getMenu().add("현재 주소 복사");
        menu.getMenu().add("클립보드 주소로 이동");
        menu.getMenu().add("캐시·쿠키 지우고 새로고침");
        menu.getMenu().add("앱 업데이트 확인");
        menu.getMenu().add("외부 브라우저로 열기");

        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();

            if (title.startsWith("읽기 잠금")) {
                readerLocked = !readerLocked;
                applyReaderGuard();
                Toast.makeText(
                        this,
                        readerLocked
                                ? "읽기 잠금 ON · 탭 이동과 자동스크롤 차단"
                                : "읽기 잠금 OFF · 사이트 버튼 사용 가능",
                        Toast.LENGTH_SHORT
                ).show();
                return true;
            }

            if ("현재 주소 복사".equals(title)) {
                copyCurrentUrl();
                return true;
            }

            if ("클립보드 주소로 이동".equals(title)) {
                pasteAndGo();
                return true;
            }

            if ("캐시·쿠키 지우고 새로고침".equals(title)) {
                clearSiteDataAndReload();
                return true;
            }

            if ("앱 업데이트 확인".equals(title)) {
                if (updateManager != null) {
                    updateManager.checkForUpdate(true);
                }
                return true;
            }

            if ("외부 브라우저로 열기".equals(title)) {
                openExternalBrowser();
                return true;
            }

            return false;
        });

        menu.show();
    }

    private void copyCurrentUrl() {
        String url = webView.getUrl();
        if (url == null || url.isEmpty()) return;

        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(
                ClipData.newPlainText("뷰냥 주소", url)
        );
        Toast.makeText(this, "주소를 복사했어요.", Toast.LENGTH_SHORT).show();
    }

    private void pasteAndGo() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        if (!clipboard.hasPrimaryClip()
                || clipboard.getPrimaryClip() == null
                || clipboard.getPrimaryClip().getItemCount() == 0) {
            Toast.makeText(this, "클립보드에 주소가 없어요.", Toast.LENGTH_SHORT).show();
            return;
        }

        CharSequence text =
                clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);

        if (text == null || text.toString().trim().isEmpty()) {
            return;
        }

        String value = text.toString().trim();
        addressBar.setText(value);
        webView.loadUrl(normalizeAddress(value));
    }

    private void clearSiteDataAndReload() {
        webView.clearCache(true);
        CookieManager.getInstance().removeAllCookies(value -> {
            CookieManager.getInstance().flush();
            runOnUiThread(() -> webView.reload());
        });
        Toast.makeText(
                this,
                "캐시와 쿠키를 지우고 다시 불러올게요.",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void openExternalBrowser() {
        String url = webView.getUrl();
        if (url == null || url.isEmpty()) return;

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "외부 브라우저를 열 수 없어요.",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) return;

        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settings.setOffscreenPreRaster(true);
        }

        String ua = settings.getUserAgentString();
        if (ua != null) {
            ua = ua
                    .replace("; wv", "")
                    .replace("Version/4.0 ", "");
            settings.setUserAgentString(ua);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setOnLongClickListener(v -> readerLocked);

        webView.setOnTouchListener((v, event) -> {
            if (!readerLocked) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    moved = false;
                    multiTouch = false;
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

                    // Reader mode allows vertical scrolling only.
                    // Consume a clearly horizontal one-finger gesture so the site
                    // cannot interpret it as previous/next episode navigation.
                    if (!multiTouch && dx > touchSlop && dx > dy * 1.15f) {
                        return true;
                    }

                    return false;

                case MotionEvent.ACTION_POINTER_UP:
                    return false;

                case MotionEvent.ACTION_UP:
                    // Let the page receive the completed tap. The injected
                    // capture-phase guard below blocks ordinary taps and only
                    // whitelists explicit previous/list/next episode controls.
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
            public void onPageStarted(
                    WebView view,
                    String url,
                    android.graphics.Bitmap favicon
            ) {
                super.onPageStarted(view, url, favicon);

                mainFrameLoadFailed = false;
                updateAddress(url);
                readerLocked = isEpisodeUrl(url);
                updateNavigationButtons();
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                super.onReceivedError(view, request, error);

                if (request != null && request.isForMainFrame()) {
                    mainFrameLoadFailed = true;
                    prefs.edit().remove(KEY_LAST_URL).apply();
                    updateAddress(request.getUrl().toString());
                    Toast.makeText(
                            MainActivity.this,
                            "접속이 막혔어요. 위 주소창에 새 주소를 붙여넣어 주세요.",
                            Toast.LENGTH_LONG
                    ).show();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                updateAddress(url);
                updateNavigationButtons();
                readerLocked = isEpisodeUrl(url);
                applyReaderGuard();

                if (readerLocked) {
                    applyImagePreload();
                }

                if (!mainFrameLoadFailed
                        && url != null
                        && (url.startsWith("http://") || url.startsWith("https://"))) {
                    prefs.edit().putString(KEY_LAST_URL, url).apply();
                }
            }
        });
    }

    private void updateAddress(String url) {
        if (addressBar == null || addressBar.hasFocus() || url == null) {
            return;
        }
        if ("about:blank".equals(url)) {
            addressBar.setText("");
            return;
        }
        addressBar.setText(url);
        addressBar.setSelection(addressBar.length());
    }

    private void updateNavigationButtons() {
        if (webView == null || backButton == null || forwardButton == null) {
            return;
        }

        backButton.setAlpha(webView.canGoBack() ? 1f : 0.35f);
        forwardButton.setAlpha(webView.canGoForward() ? 1f : 0.35f);
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

    private void applyReaderGuard() {
        if (webView == null) return;

        String locked = readerLocked ? "true" : "false";
        String script = """
                (function () {
                  window.__viewNyangLocked = %s;

                  function closestControl(target) {
                    if (!target || !target.closest) return null;
                    return target.closest('a,button,[role="button"],[onclick]');
                  }

                  function normalizeControlText(el) {
                    if (!el) return '';
                    var parts = [
                      el.innerText || '',
                      el.textContent || '',
                      el.getAttribute && el.getAttribute('aria-label') || '',
                      el.getAttribute && el.getAttribute('title') || '',
                      el.getAttribute && el.getAttribute('rel') || '',
                      el.id || '',
                      typeof el.className === 'string' ? el.className : ''
                    ];
                    return parts.join(' ').replace(/\\s+/g, ' ').trim().toLowerCase();
                  }

                  function isExplicitReaderControl(el) {
                    if (!el) return false;

                    if (el.getAttribute && el.getAttribute('data-viewnyang-allow') === '1') {
                      return true;
                    }

                    var text = normalizeControlText(el);

                    return /(이전화|이전 화|다음화|다음 화|목록|책갈피|bookmark|previous|prev|next|list)/i.test(text);
                  }

                  function isAllowedReaderControl(target) {
                    return isExplicitReaderControl(closestControl(target));
                  }

                  function findReaderControl(kind) {
                    var all = document.querySelectorAll(
                      'a,button,[role="button"],[onclick]'
                    );

                    var pattern = kind === 'prev'
                      ? /(이전화|이전 화|previous|prev)/i
                      : /(다음화|다음 화|next)/i;

                    for (var i = 0; i < all.length; i++) {
                      var el = all[i];
                      if (el.closest && el.closest('#viewnyang-nav-overlay')) continue;

                      if (pattern.test(normalizeControlText(el))) {
                        return el;
                      }
                    }

                    return null;
                  }

                  function activateReaderControl(kind) {
                    var el = findReaderControl(kind);

                    if (!el) {
                      return;
                    }

                    var anchor = el.tagName === 'A'
                      ? el
                      : (el.closest ? el.closest('a') : null);

                    var href = anchor && anchor.href ? anchor.href : '';

                    if (href) {
                      location.href = href;
                      return;
                    }

                    try {
                      el.setAttribute('data-viewnyang-allow', '1');
                      el.click();
                    } finally {
                      setTimeout(function () {
                        try {
                          el.removeAttribute('data-viewnyang-allow');
                        } catch (e) {}
                      }, 0);
                    }
                  }

                  function ensureNavOverlay() {
                    var overlay = document.getElementById('viewnyang-nav-overlay');
                    if (overlay) return overlay;

                    overlay = document.createElement('div');
                    overlay.id = 'viewnyang-nav-overlay';
                    overlay.setAttribute('data-viewnyang-allow', '1');
                    overlay.innerHTML =
                      '<button type="button" id="viewnyang-prev" data-viewnyang-allow="1">‹<span>이전화</span></button>' +
                      '<button type="button" id="viewnyang-next" data-viewnyang-allow="1"><span>다음화</span>›</button>';

                    document.documentElement.appendChild(overlay);

                    var prev = document.getElementById('viewnyang-prev');
                    var next = document.getElementById('viewnyang-next');

                    prev.addEventListener('click', function (event) {
                      event.preventDefault();
                      event.stopPropagation();
                      activateReaderControl('prev');
                    }, true);

                    next.addEventListener('click', function (event) {
                      event.preventDefault();
                      event.stopPropagation();
                      activateReaderControl('next');
                    }, true);

                    return overlay;
                  }

                  function setOverlayVisible(visible) {
                    var overlay = ensureNavOverlay();
                    overlay.classList.toggle('viewnyang-visible', !!visible);

                    var prev = document.getElementById('viewnyang-prev');
                    var next = document.getElementById('viewnyang-next');

                    if (prev) {
                      prev.style.display = findReaderControl('prev') ? 'flex' : 'none';
                    }

                    if (next) {
                      next.style.display = findReaderControl('next') ? 'flex' : 'none';
                    }
                  }

                  function toggleOverlay() {
                    var overlay = ensureNavOverlay();
                    setOverlayVisible(
                      !overlay.classList.contains('viewnyang-visible')
                    );
                  }

                  function isReaderImage(target) {
                    if (!target) return false;

                    var img = target.tagName === 'IMG'
                      ? target
                      : (target.closest ? target.closest('img') : null);

                    if (!img) return false;
                    if (img.closest && img.closest('#viewnyang-nav-overlay')) return false;

                    var rect = img.getBoundingClientRect();

                    return rect.width >= window.innerWidth * 0.45 &&
                           rect.height >= 120;
                  }

                  function stop(event) {
                    event.preventDefault();
                    event.stopPropagation();
                    if (event.stopImmediatePropagation) {
                      event.stopImmediatePropagation();
                    }
                  }

                  var lastImageToggleAt = 0;

                  function handleImageTap(event) {
                    if (!window.__viewNyangLocked) return false;
                    if (!isReaderImage(event.target)) return false;

                    var now = Date.now();

                    if (now - lastImageToggleAt > 300) {
                      lastImageToggleAt = now;
                      toggleOverlay();
                    }

                    stop(event);
                    return true;
                  }

                  function shouldBlock(event) {
                    return window.__viewNyangLocked &&
                      !isAllowedReaderControl(event.target);
                  }

                  function hideFloatingReaderControls() {
                    if (!window.__viewNyangLocked) return;

                    var all = document.querySelectorAll('body *');

                    for (var i = 0; i < all.length; i++) {
                      var el = all[i];

                      if (!el || !el.getBoundingClientRect) continue;
                      if (el.id === 'viewnyang-reader-style') continue;
                      if (el.id === 'viewnyang-nav-overlay') continue;
                      if (el.closest && el.closest('#viewnyang-nav-overlay')) continue;

                      var style = getComputedStyle(el);
                      if (style.display === 'none' || style.visibility === 'hidden') continue;

                      var pos = style.position;
                      if (pos !== 'fixed' && pos !== 'sticky') continue;

                      var rect = el.getBoundingClientRect();
                      if (rect.width < 1 || rect.height < 1) continue;

                      var controlCount = el.querySelectorAll(
                        'a,button,[role="button"],[onclick]'
                      ).length;

                      if (controlCount < 3) continue;

                      var rightRail =
                        rect.width <= 260 &&
                        rect.height >= 120 &&
                        rect.right >= window.innerWidth - 60;

                      var bottomRail =
                        rect.height <= 120 &&
                        rect.width >= 220 &&
                        rect.bottom >= window.innerHeight - 180;

                      if (rightRail || bottomRail) {
                        el.setAttribute('data-viewnyang-hidden-floating', '1');
                        el.style.setProperty('display', 'none', 'important');
                      }
                    }
                  }

                  function findAutoHideHeaders() {
                    var candidates = document.querySelectorAll(
                      'header,nav,[class*="header"],[class*="Header"],' +
                      '[class*="nav"],[class*="Nav"],' +
                      '[id*="header"],[id*="Header"],' +
                      '[id*="nav"],[id*="Nav"]'
                    );

                    for (var i = 0; i < candidates.length; i++) {
                      var el = candidates[i];

                      if (!el || !el.getBoundingClientRect) continue;
                      if (el.id === 'viewnyang-nav-overlay') continue;
                      if (el.closest && el.closest('#viewnyang-nav-overlay')) continue;
                      if (el.getAttribute('data-viewnyang-hidden-floating') === '1') continue;

                      var style = getComputedStyle(el);
                      var pos = style.position;

                      if (pos !== 'fixed' && pos !== 'sticky') continue;

                      var rect = el.getBoundingClientRect();

                      if (rect.width < window.innerWidth * 0.6) continue;
                      if (rect.height < 28 || rect.height > 260) continue;
                      if (rect.top > 180) continue;

                      var controls = el.querySelectorAll(
                        'a,button,[role="button"],[onclick]'
                      ).length;

                      if (controls < 2) continue;

                      el.setAttribute('data-viewnyang-autoheader', '1');
                    }
                  }

                  function setAutoHeadersHidden(hidden) {
                    var headers = document.querySelectorAll(
                      '[data-viewnyang-autoheader="1"]'
                    );

                    for (var i = 0; i < headers.length; i++) {
                      headers[i].classList.toggle(
                        'viewnyang-header-hidden',
                        !!hidden
                      );
                    }
                  }

                  function updateAutoHeadersForScroll() {
                    if (!window.__viewNyangLocked) {
                      setAutoHeadersHidden(false);
                      return;
                    }

                    var y = Math.max(
                      window.scrollY || 0,
                      document.documentElement.scrollTop || 0
                    );

                    if (typeof window.__viewNyangLastScrollY !== 'number') {
                      window.__viewNyangLastScrollY = y;
                      return;
                    }

                    var delta = y - window.__viewNyangLastScrollY;

                    if (y < 60) {
                      setAutoHeadersHidden(false);
                    } else if (delta > 10) {
                      setAutoHeadersHidden(true);
                    } else if (delta < -5) {
                      setAutoHeadersHidden(false);
                    }

                    window.__viewNyangLastScrollY = y;
                  }

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

                    var touchStartX = 0;
                    var touchStartY = 0;
                    var touchMoved = false;
                    var pointerStartX = 0;
                    var pointerStartY = 0;
                    var pointerMoved = false;

                    document.addEventListener('touchstart', function (event) {
                      if (!window.__viewNyangLocked || event.touches.length !== 1) return;
                      touchStartX = event.touches[0].clientX;
                      touchStartY = event.touches[0].clientY;
                      touchMoved = false;
                    }, true);

                    document.addEventListener('touchmove', function (event) {
                      if (!window.__viewNyangLocked || event.touches.length !== 1) return;

                      var dx = Math.abs(event.touches[0].clientX - touchStartX);
                      var dy = Math.abs(event.touches[0].clientY - touchStartY);

                      if (dx > 8 || dy > 8) touchMoved = true;

                      if (dx > 12 && dx > dy * 1.15) {
                        stop(event);
                      }
                    }, {capture:true, passive:false});

                    document.addEventListener('touchend', function (event) {
                      if (!window.__viewNyangLocked) return;

                      if (!touchMoved && handleImageTap(event)) {
                        return;
                      }

                      if (!touchMoved && shouldBlock(event)) {
                        stop(event);
                      }
                    }, true);

                    document.addEventListener('pointerdown', function (event) {
                      if (!window.__viewNyangLocked || event.pointerType !== 'touch') return;
                      pointerStartX = event.clientX;
                      pointerStartY = event.clientY;
                      pointerMoved = false;
                    }, true);

                    document.addEventListener('pointermove', function (event) {
                      if (!window.__viewNyangLocked || event.pointerType !== 'touch') return;

                      var dx = Math.abs(event.clientX - pointerStartX);
                      var dy = Math.abs(event.clientY - pointerStartY);

                      if (dx > 8 || dy > 8) pointerMoved = true;

                      if (dx > 12 && dx > dy * 1.15) {
                        stop(event);
                      }
                    }, true);

                    document.addEventListener('pointerup', function (event) {
                      if (!window.__viewNyangLocked || event.pointerType !== 'touch') return;

                      if (!pointerMoved && handleImageTap(event)) {
                        return;
                      }

                      if (!pointerMoved && shouldBlock(event)) {
                        stop(event);
                      }
                    }, true);

                    document.addEventListener('click', function (event) {
                      if (!window.__viewNyangLocked) return;

                      if (handleImageTap(event)) {
                        return;
                      }

                      if (shouldBlock(event)) {
                        stop(event);
                      }
                    }, true);

                    document.addEventListener('dblclick', function (event) {
                      if (shouldBlock(event)) stop(event);
                    }, true);

                    document.addEventListener('contextmenu', function (event) {
                      if (shouldBlock(event)) stop(event);
                    }, true);

                    var style = document.getElementById('viewnyang-reader-style');
                    if (!style) {
                      style = document.createElement('style');
                      style.id = 'viewnyang-reader-style';
                      document.documentElement.appendChild(style);
                    }

                    style.textContent =
                      'html{scroll-behavior:auto!important;overscroll-behavior-x:none!important;touch-action:pan-y pinch-zoom!important;}' +
                      'body{-webkit-tap-highlight-color:transparent!important;overscroll-behavior-x:none!important;touch-action:pan-y pinch-zoom!important;}' +
                      '[data-viewnyang-hidden-floating="1"]{display:none!important;}' +
                      '#viewnyang-nav-overlay{position:fixed!important;inset:0!important;z-index:2147483646!important;pointer-events:none!important;opacity:0!important;transition:opacity .16s ease!important;}' +
                      '#viewnyang-nav-overlay.viewnyang-visible{opacity:1!important;}' +
                      '#viewnyang-nav-overlay button{position:absolute!important;top:50%!important;transform:translateY(-50%)!important;display:flex!important;align-items:center!important;gap:6px!important;height:58px!important;padding:0 16px!important;border:0!important;border-radius:29px!important;background:rgba(20,20,24,.78)!important;color:#fff!important;font-size:30px!important;line-height:1!important;box-shadow:0 3px 16px rgba(0,0,0,.28)!important;pointer-events:auto!important;-webkit-tap-highlight-color:transparent!important;}' +
                      '#viewnyang-nav-overlay button span{font-size:14px!important;font-weight:700!important;white-space:nowrap!important;}' +
                      '#viewnyang-prev{left:12px!important;}' +
                      '#viewnyang-next{right:12px!important;}' +
                      '[data-viewnyang-autoheader="1"]{transition:transform .2s ease,opacity .2s ease!important;will-change:transform!important;}' +
                      '[data-viewnyang-autoheader="1"].viewnyang-header-hidden{transform:translateY(-115%)!important;opacity:0!important;pointer-events:none!important;}';

                    ensureNavOverlay();

                    var hideScheduled = false;
                    function scheduleHideFloating() {
                      if (hideScheduled) return;
                      hideScheduled = true;
                      requestAnimationFrame(function () {
                        hideScheduled = false;
                        hideFloatingReaderControls();
                      });
                    }

                    var headerScrollScheduled = false;

                    function scheduleHeaderUpdate() {
                      if (headerScrollScheduled) return;
                      headerScrollScheduled = true;

                      requestAnimationFrame(function () {
                        headerScrollScheduled = false;
                        updateAutoHeadersForScroll();
                      });
                    }

                    window.addEventListener('scroll', function () {
                      scheduleHeaderUpdate();
                    }, {passive:true});

                    window.addEventListener('resize', function () {
                      findAutoHideHeaders();
                      scheduleHeaderUpdate();
                    }, {passive:true});

                    var mutationScheduled = false;
                    var floatingObserver = new MutationObserver(function () {
                      if (mutationScheduled) return;
                      mutationScheduled = true;

                      setTimeout(function () {
                        mutationScheduled = false;
                        hideFloatingReaderControls();
                        findAutoHideHeaders();

                        var overlay = document.getElementById('viewnyang-nav-overlay');
                        if (overlay && overlay.classList.contains('viewnyang-visible')) {
                          setOverlayVisible(true);
                        }
                      }, 250);
                    });

                    floatingObserver.observe(document.documentElement, {
                      childList: true,
                      subtree: true
                    });

                    findAutoHideHeaders();
                    window.__viewNyangLastScrollY = window.scrollY || 0;

                    setTimeout(function () {
                      hideFloatingReaderControls();
                      findAutoHideHeaders();
                      updateAutoHeadersForScroll();
                    }, 0);

                    setTimeout(function () {
                      hideFloatingReaderControls();
                      findAutoHideHeaders();
                    }, 600);

                    setTimeout(function () {
                      hideFloatingReaderControls();
                      findAutoHideHeaders();
                    }, 1500);

                    // The page is normally fully assembled within a few seconds.
                    // Stop watching afterward so long reading sessions do not
                    // continuously scan the DOM and stress the WebView renderer.
                    setTimeout(function () {
                      try {
                        floatingObserver.disconnect();
                      } catch (e) {}
                    }, 5000);
                  } else {
                    hideFloatingReaderControls();
                    findAutoHideHeaders();
                    ensureNavOverlay();
                    updateAutoHeadersForScroll();
                  }
                })();
                """.formatted(locked);

        webView.evaluateJavascript(script, null);
    }

    private void applyImagePreload() {
        if (webView == null) return;

        String script = """
                (function () {
                  if (window.__viewNyangImagePreloadInstalled) {
                    if (window.__viewNyangPrepareImages) {
                      window.__viewNyangPrepareImages();
                    }
                    return;
                  }

                  window.__viewNyangImagePreloadInstalled = true;

                  function promote(img, priority) {
                    if (!img) return;

                    var src =
                      img.getAttribute('data-src') ||
                      img.getAttribute('data-original') ||
                      img.getAttribute('data-lazy-src') ||
                      img.getAttribute('data-url');

                    var srcset =
                      img.getAttribute('data-srcset') ||
                      img.getAttribute('data-lazy-srcset');

                    if (srcset && !img.getAttribute('srcset')) {
                      img.setAttribute('srcset', srcset);
                    }

                    if (src && (!img.getAttribute('src') ||
                        img.getAttribute('src').indexOf('data:image') === 0)) {
                      img.setAttribute('src', src);
                    }

                    img.loading = 'eager';
                    img.decoding = 'async';

                    try {
                      img.fetchPriority = priority ? 'high' : 'auto';
                    } catch (e) {}
                  }

                  var scheduled = false;

                  window.__viewNyangPrepareImages = function () {
                    scheduled = false;

                    var viewportBottom =
                      window.scrollY + window.innerHeight * 5;

                    var images = document.querySelectorAll('img');

                    for (var i = 0; i < images.length; i++) {
                      var img = images[i];
                      var rect = img.getBoundingClientRect();
                      var top = rect.top + window.scrollY;

                      if (top <= viewportBottom) {
                        promote(img, top < window.scrollY + window.innerHeight * 1.5);
                      }
                    }
                  };

                  function schedule() {
                    if (scheduled) return;
                    scheduled = true;
                    requestAnimationFrame(window.__viewNyangPrepareImages);
                  }

                  window.addEventListener('scroll', schedule, {passive:true});
                  window.addEventListener('resize', schedule, {passive:true});

                  var observer = new MutationObserver(schedule);
                  observer.observe(document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: [
                      'src',
                      'srcset',
                      'data-src',
                      'data-original',
                      'data-lazy-src',
                      'data-srcset',
                      'data-lazy-srcset'
                    ]
                  });

                  window.__viewNyangPrepareImages();
                  setTimeout(window.__viewNyangPrepareImages, 350);
                  setTimeout(window.__viewNyangPrepareImages, 1200);
                })();
                """;

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
    protected void onResume() {
        super.onResume();
        if (updateManager != null) {
            updateManager.resumePendingUpdateFlow();
        }
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
        if (updateManager != null) {
            updateManager.destroy();
        }

        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }
}
