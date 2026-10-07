package com.hanview.translate;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private EditText addressBar;
    private AppUpdateManager updateManager;

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

        updateManager = new AppUpdateManager(this);
        setContentView(buildUi());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(16), dp(24), dp(16), dp(24));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    dp(16) + insets.getSystemWindowInsetLeft(),
                    dp(24) + insets.getSystemWindowInsetTop(),
                    dp(16) + insets.getSystemWindowInsetRight(),
                    dp(24) + insets.getSystemWindowInsetBottom()
            );
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("뷰냥");
        title.setTextSize(27);
        title.setTextColor(Color.rgb(25, 27, 32));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(4), 0, 0, 0);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(60)
        ));

        TextView desc = new TextView(this);
        desc.setText("주소를 넣고 리더를 열어 주세요.\n리더가 죽어도 뷰냥 본체는 종료되지 않아요.");
        desc.setTextSize(14);
        desc.setTextColor(Color.rgb(105, 110, 120));
        desc.setPadding(dp(4), 0, dp(4), dp(18));
        root.addView(desc);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextSize(15);
        addressBar.setTextColor(Color.rgb(35, 38, 45));
        addressBar.setHintTextColor(Color.rgb(125, 129, 137));
        addressBar.setHint("검색어 또는 주소 입력");
        addressBar.setPadding(dp(16), 0, dp(16), 0);
        addressBar.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_URI
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setBackground(rounded(Color.rgb(243, 244, 247), 24));

        row.addView(addressBar, new LinearLayout.LayoutParams(0, dp(50), 1f));

        TextView go = textButton("→", 25);
        row.addView(go, new LinearLayout.LayoutParams(dp(54), dp(50)));
        go.setOnClickListener(v -> openReader());

        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;

            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                openReader();
                return true;
            }
            return false;
        });

        root.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
        ));

        TextView paste = textButton("클립보드 주소 붙여넣기", 15);
        paste.setBackground(rounded(Color.rgb(245, 246, 248), 18));
        LinearLayout.LayoutParams pasteLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
        );
        pasteLp.topMargin = dp(16);
        root.addView(paste, pasteLp);
        paste.setOnClickListener(v -> pasteAddress());

        TextView menu = textButton("설정 · 업데이트", 15);
        menu.setBackground(rounded(Color.rgb(245, 246, 248), 18));
        LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
        );
        menuLp.topMargin = dp(10);
        root.addView(menu, menuLp);
        menu.setOnClickListener(this::showMenu);

        return root;
    }

    private void openReader() {
        String raw = addressBar.getText().toString().trim();
        if (raw.isEmpty()) return;

        hideKeyboard();

        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra("url", normalizeAddress(raw));

        try {
            startActivity(intent);
        } catch (Throwable error) {
            Toast.makeText(
                    this,
                    "리더를 시작하지 못했어요: " + error.getClass().getSimpleName(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void pasteAddress() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        if (!clipboard.hasPrimaryClip()
                || clipboard.getPrimaryClip() == null
                || clipboard.getPrimaryClip().getItemCount() == 0) {
            Toast.makeText(this, "클립보드에 주소가 없어요.", Toast.LENGTH_SHORT).show();
            return;
        }

        CharSequence value =
                clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);

        if (value != null) {
            addressBar.setText(value.toString().trim());
            addressBar.setSelection(addressBar.length());
        }
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("앱 업데이트 확인");
        menu.getMenu().add("Google 열기");

        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();

            if ("앱 업데이트 확인".equals(title)) {
                updateManager.checkForUpdate(true);
                return true;
            }

            if ("Google 열기".equals(title)) {
                addressBar.setText("https://www.google.com/");
                openReader();
                return true;
            }

            return false;
        });

        menu.show();
    }

    private String normalizeAddress(String value) {
        String input = value.trim();

        if (input.startsWith("http://") || input.startsWith("https://")) {
            return input;
        }

        boolean looksLikeDomain = input.matches(
                "(?i)^(?:[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?\\.)+[a-z]{2,63}(?::\\d{1,5})?(?:/.*)?$"
        );

        if (looksLikeDomain) {
            return "https://" + input;
        }

        return "https://www.google.com/search?q=" + Uri.encode(input);
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) return;

        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }

    private TextView textButton(String label, float size) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(40, 43, 50));
        view.setGravity(Gravity.CENTER);
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

    @Override
    protected void onResume() {
        super.onResume();
        if (updateManager != null) {
            updateManager.resumePendingUpdateFlow();
        }
    }

    @Override
    protected void onDestroy() {
        if (updateManager != null) {
            updateManager.destroy();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
