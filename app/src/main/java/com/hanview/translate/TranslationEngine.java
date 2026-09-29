package com.hanview.translate;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final LanguageIdentifier languageIdentifier = LanguageIdentification.getClient();
    private final Map<String, Translator> translators = new ConcurrentHashMap<>();

    private final Map<String, String> translationCache =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, String>(512, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, String> eldest
                        ) {
                            return size() > 900;
                        }
                    }
            );

    public TranslationEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public void translate(List<OcrBlock> blocks, Callback callback) {
        if (blocks.isEmpty()) {
            callback.onSuccess(blocks, false);
            return;
        }

        List<OcrBlock> pending = new ArrayList<>();

        for (OcrBlock block : blocks) {
            String cached = getCached(block.original);

            if (cached != null) {
                block.translated = cached;
            } else {
                pending.add(block);
            }
        }

        if (pending.isEmpty()) {
            callback.onSuccess(blocks, false);
            return;
        }

        SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        String endpoint = prefs.getString(KEY_ENDPOINT, "").trim();

        Callback mergeBack = new Callback() {
            @Override
            public void onSuccess(
                    List<OcrBlock> translatedPending,
                    boolean usedAi
            ) {
                for (OcrBlock block : translatedPending) {
                    if (block.translated != null
                            && !block.translated.trim().isEmpty()) {
                        putCached(block.original, block.translated);
                    }
                }

                callback.onSuccess(blocks, usedAi);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        };

        if (!endpoint.isEmpty()) {
            translateRemote(endpoint, pending, mergeBack);
        } else {
            translateLocalAuto(pending, mergeBack);
        }
    }

    private void translateRemote(
            String endpoint,
            List<OcrBlock> blocks,
            Callback callback
    ) {
        networkExecutor.execute(() -> {
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

                HttpURLConnection conn =
                        (HttpURLConnection) new URL(endpoint).openConnection();

                conn.setRequestMethod("POST");
                conn.setConnectTimeout(7000);
                conn.setReadTimeout(18000);
                conn.setDoOutput(true);
                conn.setRequestProperty(
                        "Content-Type",
                        "application/json; charset=utf-8"
                );
                conn.setRequestProperty("Accept", "application/json");

                byte[] payload =
                        root.toString().getBytes(StandardCharsets.UTF_8);

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
                    throw new IllegalStateException(
                            "AI server HTTP " + code
                    );
                }

                JSONArray translations =
                        new JSONObject(response).getJSONArray("translations");

                Map<Integer, String> byId = new ConcurrentHashMap<>();

                for (int i = 0; i < translations.length(); i++) {
                    JSONObject t = translations.getJSONObject(i);
                    byId.put(t.getInt("id"), t.getString("text"));
                }

                for (OcrBlock block : blocks) {
                    String translated = byId.get(block.id);

                    if (translated != null
                            && !translated.trim().isEmpty()) {
                        block.translated = translated.trim();
                    }
                }

                callback.onSuccess(blocks, true);
            } catch (Exception remoteError) {
                // A live screen should keep working even if the AI backend
                // is unavailable. Fall back to on-device auto language translation.
                translateLocalAuto(blocks, callback);
            }
        });
    }

    private void translateLocalAuto(
            List<OcrBlock> blocks,
            Callback callback
    ) {
        AtomicInteger remaining = new AtomicInteger(blocks.size());
        AtomicInteger successes = new AtomicInteger(0);

        for (OcrBlock block : blocks) {
            translateOneLocal(block, ok -> {
                if (ok) {
                    successes.incrementAndGet();
                }

                if (remaining.decrementAndGet() == 0) {
                    if (successes.get() == 0) {
                        callback.onError(
                                "현재 화면에서 번역 가능한 언어를 찾지 못했어요."
                        );
                    } else {
                        callback.onSuccess(blocks, false);
                    }
                }
            });
        }
    }

    private interface LocalResult {
        void done(boolean ok);
    }

    private void translateOneLocal(
            OcrBlock block,
            LocalResult callback
    ) {
        String quick = quickLanguageGuess(block.original);

        if ("ko".equals(quick)) {
            block.translated = block.original;
            callback.done(false);
            return;
        }

        if (quick != null) {
            translateWithLanguage(block, quick, callback);
            return;
        }

        languageIdentifier.identifyLanguage(block.original)
                .addOnSuccessListener(languageTag -> {
                    if (languageTag == null
                            || "und".equals(languageTag)
                            || "ko".equals(languageTag)) {
                        String fallback = looksMostlyLatin(block.original)
                                ? "en"
                                : null;

                        if (fallback == null) {
                            block.translated = block.original;
                            callback.done(false);
                        } else {
                            translateWithLanguage(
                                    block,
                                    fallback,
                                    callback
                            );
                        }

                        return;
                    }

                    translateWithLanguage(
                            block,
                            languageTag,
                            callback
                    );
                })
                .addOnFailureListener(e -> {
                    if (looksMostlyLatin(block.original)) {
                        translateWithLanguage(block, "en", callback);
                    } else {
                        block.translated = block.original;
                        callback.done(false);
                    }
                });
    }

    private void translateWithLanguage(
            OcrBlock block,
            String languageTag,
            LocalResult callback
    ) {
        String source =
                TranslateLanguage.fromLanguageTag(languageTag);

        if (source == null
                || TranslateLanguage.KOREAN.equals(source)) {
            block.translated = block.original;
            callback.done(false);
            return;
        }

        Translator translator = translators.get(source);

        if (translator == null) {
            TranslatorOptions options =
                    new TranslatorOptions.Builder()
                            .setSourceLanguage(source)
                            .setTargetLanguage(
                                    TranslateLanguage.KOREAN
                            )
                            .build();

            translator = Translation.getClient(options);
            translators.put(source, translator);
        }

        final Translator readyTranslator = translator;

        readyTranslator.downloadModelIfNeeded(
                        new DownloadConditions.Builder().build()
                )
                .addOnSuccessListener(unused ->
                        readyTranslator.translate(block.original)
                                .addOnSuccessListener(text -> {
                                    if (text != null
                                            && !text.trim().isEmpty()) {
                                        block.translated = text.trim();
                                        callback.done(true);
                                    } else {
                                        block.translated = block.original;
                                        callback.done(false);
                                    }
                                })
                                .addOnFailureListener(e -> {
                                    block.translated = block.original;
                                    callback.done(false);
                                })
                )
                .addOnFailureListener(e -> {
                    block.translated = block.original;
                    callback.done(false);
                });
    }

    private String quickLanguageGuess(String value) {
        boolean hangul = false;
        boolean kana = false;
        boolean han = false;
        boolean devanagari = false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if ((c >= '\uAC00' && c <= '\uD7A3')
                    || (c >= '\u1100' && c <= '\u11FF')) {
                hangul = true;
            }

            if ((c >= '\u3040' && c <= '\u30FF')) {
                kana = true;
            }

            if ((c >= '\u3400' && c <= '\u4DBF')
                    || (c >= '\u4E00' && c <= '\u9FFF')) {
                han = true;
            }

            if (c >= '\u0900' && c <= '\u097F') {
                devanagari = true;
            }
        }

        if (hangul && !kana && !han && !devanagari) {
            return "ko";
        }

        if (kana) {
            return "ja";
        }

        if (han) {
            return "zh";
        }

        if (devanagari) {
            return "hi";
        }

        return null;
    }

    private boolean looksMostlyLatin(String value) {
        int latin = 0;
        int letters = 0;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (Character.isLetter(c)) {
                letters++;

                if ((c >= 'A' && c <= 'Z')
                        || (c >= 'a' && c <= 'z')
                        || (c >= '\u00C0' && c <= '\u024F')) {
                    latin++;
                }
            }
        }

        return letters > 0 && latin * 2 >= letters;
    }

    private String getCached(String source) {
        return translationCache.get(normalize(source));
    }

    private void putCached(
            String source,
            String translated
    ) {
        translationCache.put(
                normalize(source),
                translated
        );
    }

    private String normalize(String value) {
        if (value == null) return "";

        return value
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String readAll(InputStream in)
            throws Exception {
        if (in == null) return "";

        StringBuilder sb = new StringBuilder();

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     in,
                                     StandardCharsets.UTF_8
                             )
                     )) {
            String line;

            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }

        return sb.toString();
    }

    public void close() {
        languageIdentifier.close();

        for (Translator translator : translators.values()) {
            translator.close();
        }

        translators.clear();
        networkExecutor.shutdownNow();
    }
}
