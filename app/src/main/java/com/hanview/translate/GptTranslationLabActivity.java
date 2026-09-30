package com.hanview.translate;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;

public class GptTranslationLabActivity extends Activity {
    private static final int REQ_IMAGE = 4101;

    private ChatGptPlanClient chatGpt;
    private TextView accountStatus;
    private TextView resultView;
    private TextView modelView;
    private Button translateButton;
    private Button connectButton;
    private ImageView preview;
    private Bitmap selectedBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        chatGpt =
                new ChatGptPlanClient(this);

        setContentView(buildUi());
        refreshAccount();
    }

    private View buildUi() {
        int pad = dp(18);

        ScrollView scroll =
                new ScrollView(this);

        LinearLayout root =
                new LinearLayout(this);
        root.setOrientation(
                LinearLayout.VERTICAL
        );
        root.setPadding(
                pad,
                pad,
                pad,
                pad
        );
        root.setBackgroundColor(
                Color.rgb(
                        248,
                        249,
                        251
                )
        );

        TextView title =
                text(
                        "GPT 번역 검증실",
                        24,
                        Color.rgb(
                                24,
                                27,
                                34
                        )
                );
        title.setTypeface(
                title.getTypeface(),
                android.graphics.Typeface.BOLD
        );
        root.addView(title);

        TextView info =
                text(
                        "뷰냥 실시간 번역과 완전히 분리된 테스트 화면입니다.\n"
                                + "스크린샷 이미지를 GPT가 직접 읽고, OCR 조각에 의존하지 않고 페이지 전체 문맥으로 한국어를 만듭니다.\n"
                                + "여기서 번역 품질을 먼저 확인한 뒤 실시간 오버레이에 연결합니다.",
                        14,
                        Color.rgb(
                                74,
                                80,
                                92
                        )
                );
        root.addView(
                info,
                spaced()
        );

        accountStatus =
                text(
                        "",
                        14,
                        Color.rgb(
                                45,
                                50,
                                60
                        )
                );
        root.addView(
                accountStatus,
                spaced()
        );

        connectButton =
                button(
                        "ChatGPT 연결"
                );
        connectButton.setOnClickListener(
                v -> connectChatGpt()
        );
        root.addView(
                connectButton,
                spaced()
        );

        Button choose =
                button(
                        "번역할 스크린샷 선택"
                );
        choose.setOnClickListener(
                v -> chooseImage()
        );
        root.addView(
                choose,
                spaced()
        );

        preview =
                new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(
                ImageView.ScaleType.CENTER_INSIDE
        );
        preview.setVisibility(
                View.GONE
        );
        root.addView(
                preview,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(260)
                )
        );

        translateButton =
                button(
                        "GPT로 페이지 전체 번역"
                );
        translateButton.setEnabled(false);
        translateButton.setOnClickListener(
                v -> runTranslation()
        );
        root.addView(
                translateButton,
                spaced()
        );

        modelView =
                text(
                        "",
                        12,
                        Color.rgb(
                                110,
                                116,
                                128
                        )
                );
        root.addView(
                modelView,
                spaced()
        );

        resultView =
                text(
                        "번역 결과가 여기에 표시됩니다.",
                        16,
                        Color.rgb(
                                25,
                                28,
                                34
                        )
                );
        resultView.setTextIsSelectable(true);
        resultView.setLineSpacing(
                dp(3),
                1.1f
        );
        resultView.setPadding(
                dp(14),
                dp(14),
                dp(14),
                dp(14)
        );
        resultView.setBackgroundColor(
                Color.WHITE
        );
        root.addView(
                resultView,
                spaced()
        );

        Button copy =
                button(
                        "번역 결과 복사"
                );
        copy.setOnClickListener(
                v -> copyResult()
        );
        root.addView(
                copy,
                spaced()
        );

        scroll.addView(root);
        return scroll;
    }

    private void refreshAccount() {
        boolean connected =
                chatGpt.hasPlanAccess();

        String email =
                chatGpt.connectedEmail();

        accountStatus.setText(
                connected
                        ? "✓ ChatGPT 플랜 연결됨"
                        + (
                        email.isEmpty()
                                ? ""
                                : "\n" + email
                )
                        : "ChatGPT 플랜 연결이 필요합니다."
        );

        connectButton.setText(
                connected
                        ? "ChatGPT 다시 연결"
                        : "ChatGPT 연결"
        );

        translateButton.setEnabled(
                connected
                        && selectedBitmap != null
        );
    }

    private void connectChatGpt() {
        connectButton.setEnabled(false);
        accountStatus.setText(
                "ChatGPT 연결을 기다리는 중..."
        );

        chatGpt.beginSignIn(
                this,
                new ChatGptPlanClient.SignInCallback() {
                    @Override
                    public void onSuccess(
                            String email
                    ) {
                        connectButton.setEnabled(true);
                        refreshAccount();

                        Toast.makeText(
                                GptTranslationLabActivity.this,
                                "ChatGPT 연결 완료",
                                Toast.LENGTH_SHORT
                        ).show();
                    }

                    @Override
                    public void onError(
                            String message
                    ) {
                        connectButton.setEnabled(true);
                        refreshAccount();

                        Toast.makeText(
                                GptTranslationLabActivity.this,
                                message,
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
        );
    }

    private void chooseImage() {
        Intent intent =
                new Intent(
                        Intent.ACTION_OPEN_DOCUMENT
                );
        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );
        intent.setType(
                "image/*"
        );
        startActivityForResult(
                intent,
                REQ_IMAGE
        );
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode != REQ_IMAGE
                || resultCode != RESULT_OK
                || data == null) {
            return;
        }

        Uri uri =
                data.getData();

        if (uri == null) {
            return;
        }

        try (InputStream input =
                     getContentResolver()
                             .openInputStream(
                                     uri
                             )) {
            Bitmap bitmap =
                    BitmapFactory.decodeStream(
                            input
                    );

            if (bitmap == null) {
                throw new IllegalStateException(
                        "이미지를 읽지 못했어요."
                );
            }

            if (selectedBitmap != null
                    && !selectedBitmap.isRecycled()) {
                selectedBitmap.recycle();
            }

            selectedBitmap = bitmap;
            preview.setImageBitmap(
                    bitmap
            );
            preview.setVisibility(
                    View.VISIBLE
            );
            resultView.setText(
                    "이미지를 선택했어요. 아래 버튼을 눌러 GPT 번역 품질을 확인하세요."
            );
            modelView.setText("");
            refreshAccount();
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    e.getMessage() == null
                            ? "이미지를 열지 못했어요."
                            : e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void runTranslation() {
        if (selectedBitmap == null) {
            return;
        }

        if (!chatGpt.hasPlanAccess()) {
            Toast.makeText(
                    this,
                    "먼저 ChatGPT를 연결해 주세요.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        translateButton.setEnabled(false);
        translateButton.setText(
                "GPT가 화면 전체를 읽는 중..."
        );
        resultView.setText(
                "이미지의 원문, 세로쓰기 순서, 말투와 문맥을 GPT가 직접 분석하고 있어요."
        );
        modelView.setText("");

        chatGpt.translateImage(
                selectedBitmap,
                new ChatGptPlanClient.VisionTranslationCallback() {
                    @Override
                    public void onSuccess(
                            String pageText,
                            String model
                    ) {
                        runOnUiThread(() -> {
                            translateButton.setEnabled(true);
                            translateButton.setText(
                                    "GPT로 페이지 전체 번역"
                            );
                            modelView.setText(
                                    "사용 모델: "
                                            + model
                            );
                            resultView.setText(
                                    pageText
                            );
                        });
                    }

                    @Override
                    public void onError(
                            String message
                    ) {
                        runOnUiThread(() -> {
                            translateButton.setEnabled(true);
                            translateButton.setText(
                                    "GPT로 페이지 전체 번역"
                            );
                            modelView.setText(
                                    "번역 실패"
                            );
                            resultView.setText(
                                    message
                            );
                        });
                    }
                }
        );
    }

    private void copyResult() {
        String value =
                resultView.getText()
                        .toString();

        ClipboardManager manager =
                (ClipboardManager)
                        getSystemService(
                                Context.CLIPBOARD_SERVICE
                        );

        manager.setPrimaryClip(
                ClipData.newPlainText(
                        "뷰냥 GPT 번역",
                        value
                )
        );

        Toast.makeText(
                this,
                "복사했어요.",
                Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    protected void onDestroy() {
        chatGpt.cancelTranslations();

        if (selectedBitmap != null
                && !selectedBitmap.isRecycled()) {
            selectedBitmap.recycle();
        }

        super.onDestroy();
    }

    private TextView text(
            String value,
            int size,
            int color
    ) {
        TextView view =
                new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(
            String label
    ) {
        Button button =
                new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
        params.topMargin = dp(12);
        return params;
    }

    private int dp(int value) {
        return Math.round(
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density
        );
    }
}
