package com.hanview.translate;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class TranslationEngine {
    public static final String PREFS = "hanview";
    public static final String KEY_ENDPOINT = "translation_endpoint";

    public interface Callback {
        void onSuccess(List<OcrBlock> blocks, boolean usedAi);
        void onError(String message);
    }

    private final Context context;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Translator localTranslator;

    public TranslationEngine(Context context) {
        this.context = context.getApplicationContext();
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build();
        localTranslator = Translation.getClient(options);
    }

    public void translate(List<OcrBlock> blocks, Callback callback) {
        if (blocks.isEmpty()) {
            callback.onSuccess(blocks, false);
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String endpoint = prefs.getString(KEY_ENDPOINT, "").trim();
        if (!endpoint.isEmpty()) translateRemote(endpoint, blocks, callback);
        else translateLocal(blocks, callback);
    }

    private void translateRemote(String endpoint, List<OcrBlock> blocks, Callback callback) {
        networkExecutor.execute(() -> {
            try {
                JSONObject root = new JSONObject();
                root.put("source", "zh");
                root.put("target", "ko");
                root.put("mode", "chinese-shopping");
                JSONArray items = new JSONArray();
                for (OcrBlock block : blocks) {
                    JSONObject item = new JSONObject();
                    item.put("id", block.id);
                    item.put("text", block.original);
                    items.put(item);
                }
                root.put("items", items);

                HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                byte[] payload = root.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }

                int code = conn.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                String response = readAll(stream);
                if (code < 200 || code >= 300) throw new IllegalStateException("AI server HTTP " + code + ": " + response);

                JSONArray translations = new JSONObject(response).getJSONArray("translations");
                Map<Integer, String> byId = new HashMap<>();
                for (int i = 0; i < translations.length(); i++) {
                    JSONObject t = translations.getJSONObject(i);
                    byId.put(t.getInt("id"), t.getString("text"));
                }
                for (OcrBlock block : blocks) {
                    String translated = byId.get(block.id);
                    if (translated != null && !translated.trim().isEmpty()) block.translated = translated.trim();
                }
                callback.onSuccess(blocks, true);
            } catch (Exception remoteError) {
                translateLocal(blocks, new Callback() {
                    @Override
                    public void onSuccess(List<OcrBlock> translated, boolean ignored) {
                        callback.onSuccess(translated, false);
                    }
                    @Override
                    public void onError(String localMessage) {
                        callback.onError("AI 번역 실패: " + remoteError.getMessage() + "\n기기 번역도 실패: " + localMessage);
                    }
                });
            }
        });
    }

    private void translateLocal(List<OcrBlock> blocks, Callback callback) {
        localTranslator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> runLocalTranslations(blocks, callback))
                .addOnFailureListener(e -> callback.onError("중국어/한국어 번역 모델을 내려받지 못했어요: " + e.getMessage()));
    }

    private void runLocalTranslations(List<OcrBlock> blocks, Callback callback) {
        AtomicInteger remaining = new AtomicInteger(blocks.size());
        List<String> failures = new ArrayList<>();
        for (OcrBlock block : blocks) {
            localTranslator.translate(block.original)
                    .addOnSuccessListener(text -> {
                        block.translated = text == null || text.trim().isEmpty() ? block.original : text.trim();
                        if (remaining.decrementAndGet() == 0) finishLocal(blocks, failures, callback);
                    })
                    .addOnFailureListener(e -> {
                        synchronized (failures) {
                            failures.add(e.getMessage() == null ? "unknown" : e.getMessage());
                        }
                        if (remaining.decrementAndGet() == 0) finishLocal(blocks, failures, callback);
                    });
        }
    }

    private void finishLocal(List<OcrBlock> blocks, List<String> failures, Callback callback) {
        if (failures.size() == blocks.size()) callback.onError("기기 내 번역에 실패했어요.");
        else callback.onSuccess(blocks, false);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    public void close() {
        localTranslator.close();
        networkExecutor.shutdownNow();
    }
}
