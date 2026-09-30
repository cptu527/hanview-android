package com.hanview.translate;

import android.content.Context;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TranslationEngine {
    public static final String PREFS = "hanview";
    public static final String KEY_ENDPOINT = "translation_endpoint";
    public interface Callback {
        void onSuccess(List<OcrBlock> blocks, boolean usedAi);
        void onError(String message);
    }
    private final Context context;
    private final ChatGptPlanClient chatGptPlanClient;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    // Cache whole pages: a fragment must not reuse a translation from a different conversation.
    private final Map<String, List<String>> pageCache = Collections.synchronizedMap(
            new LinkedHashMap<String, List<String>>(24, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<String>> entry) {
                    return size() > 24;
                }
            });

    public TranslationEngine(Context context) {
        this.context = context.getApplicationContext();
        chatGptPlanClient = new ChatGptPlanClient(this.context);
    }

    public void translate(List<OcrBlock> blocks, Callback callback) {
        if (blocks.isEmpty()) { callback.onSuccess(blocks, true); return; }
        boolean connected = chatGptPlanClient.hasPlanAccess();
        String endpoint = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ENDPOINT, "").trim();
        if (!connected && endpoint.isEmpty()) {
            callback.onError("뷰냥에서 ChatGPT를 연결해 주세요. 기기 번역으로 대신 표시하지 않습니다.");
            return;
        }
        StringBuilder signature = new StringBuilder(connected ? "gpt:" : endpoint + ":");
        for (OcrBlock block : blocks) {
            signature.append(block.original.length()).append(':').append(block.original)
                    .append('@').append(block.bounds.left).append(',').append(block.bounds.top)
                    .append(',').append(block.verticalSource).append(';');
        }
        String key = signature.toString();
        List<String> cached = pageCache.get(key);
        if (cached != null && cached.size() == blocks.size()) {
            for (int i = 0; i < blocks.size(); i++) blocks.get(i).translated = cached.get(i);
            callback.onSuccess(blocks, true);
            return;
        }
        Callback result = new Callback() {
            @Override public void onSuccess(List<OcrBlock> translated, boolean usedAi) {
                List<String> values = new ArrayList<>();
                for (OcrBlock block : translated) values.add(block.translated);
                pageCache.put(key, values);
                callback.onSuccess(translated, true);
            }
            @Override public void onError(String message) { callback.onError(message); }
        };
        if (connected) translateWithChatGpt(blocks, result);
        else translateRemote(endpoint, blocks, result);
    }

    private void translateWithChatGpt(List<OcrBlock> blocks, Callback callback) {
        chatGptPlanClient.translate(blocks, new ChatGptPlanClient.TranslationCallback() {
            @Override public void onSuccess(Map<Integer, String> translations, String model) {
                for (OcrBlock block : blocks) {
                    String value = translations.get(block.id);
                    if (value == null || value.trim().isEmpty()) {
                        callback.onError("GPT 번역이 일부 누락됐어요. 다시 시도해 주세요.");
                        return;
                    }
                }
                for (OcrBlock block : blocks) block.translated = translations.get(block.id).trim();
                callback.onSuccess(blocks, true);
            }
            @Override public void onError(String message) {
                callback.onError("GPT 번역 실패: " + message);
            }
        });
    }

    private void translateRemote(
            String endpoint,
            List<OcrBlock> blocks,
            Callback callback
    ) {
        networkExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject root = new JSONObject();
                root.put("source", "auto");
                root.put("target", "ko");
                root.put("mode", "natural-live-screen");

                JSONArray items = new JSONArray();
                for (OcrBlock block : blocks) {
                    JSONObject item = new JSONObject();
                    item.put("id", block.id);
                    item.put("text", block.original);
                    items.put(item);
                }
                root.put("items", items);

                conn =
                        (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(12000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");

                byte[] payload = root.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);

                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }

                int code = conn.getResponseCode();
                InputStream stream =
                        code >= 200 && code < 300
                                ? conn.getInputStream()
                                : conn.getErrorStream();

                String response = readAll(stream);
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("AI server HTTP " + code);
                }

                JSONArray translations =
                        new JSONObject(response).getJSONArray("translations");

                Map<Integer, String> byId = new HashMap<>();
                for (int i = 0; i < translations.length(); i++) {
                    JSONObject t = translations.getJSONObject(i);
                    byId.put(t.getInt("id"), t.getString("text"));
                }

                for (OcrBlock block : blocks) {
                    String translated = byId.get(block.id);
                    if (translated != null && !translated.trim().isEmpty()) {
                        block.translated = translated.trim();
                    }
                }

                callback.onSuccess(blocks, true);
            } catch (Exception remoteError) {
                callback.onError("AI 번역 서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }

        return sb.toString();
    }

    public void cancelPending() { chatGptPlanClient.cancelTranslations(); }

    public void close() {
        pageCache.clear();
        networkExecutor.shutdownNow();
        chatGptPlanClient.cancelTranslations();
    }
}
