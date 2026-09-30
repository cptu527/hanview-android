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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final Set<String> readyModels = ConcurrentHashMap.newKeySet();

    private final Map<String, String> translationCache =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, String>(512, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                            return size() > 1200;
                        }
                    }
            );

    public TranslationEngine(Context context) {
        this.context = context.getApplicationContext();
        // Load only the model needed for the language currently on screen.
        // This avoids allocating several translation models beside OCR at once.
    }

    public static void prewarmCommon(Context context) {
        String[] common = new String[]{"zh", "en", "ja"};

        for (String tag : common) {
            String source = TranslateLanguage.fromLanguageTag(tag);
            if (source == null || TranslateLanguage.KOREAN.equals(source)) {
                continue;
            }

            TranslatorOptions options =
                    new TranslatorOptions.Builder()
                            .setSourceLanguage(source)
                            .setTargetLanguage(TranslateLanguage.KOREAN)
                            .build();

            Translator translator = Translation.getClient(options);
            translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                    .addOnCompleteListener(task -> translator.close());
        }
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

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String endpoint = prefs.getString(KEY_ENDPOINT, "").trim();

        Callback mergeBack = new Callback() {
            @Override
            public void onSuccess(List<OcrBlock> translatedPending, boolean usedAi) {
                for (OcrBlock block : translatedPending) {
                    if (block.translated != null
                            && !block.translated.trim().isEmpty()
                            && !block.translated.equals(block.original)) {
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
                translateLocalAuto(blocks, callback);
            }
        });
    }

    private void translateLocalAuto(List<OcrBlock> blocks, Callback callback) {
        Map<String, List<OcrBlock>> groups = new ConcurrentHashMap<>();
        List<OcrBlock> unknown = Collections.synchronizedList(new ArrayList<>());

        for (OcrBlock block : blocks) {
            String quick = quickLanguageGuess(block.original);

            if ("ko".equals(quick)) {
                block.translated = block.original;
            } else if (quick != null) {
                groups.computeIfAbsent(
                        quick,
                        key -> Collections.synchronizedList(new ArrayList<>())
                ).add(block);
            } else {
                unknown.add(block);
            }
        }

        if (unknown.isEmpty()) {
            translateGroups(groups, blocks, callback);
            return;
        }

        AtomicInteger remaining = new AtomicInteger(unknown.size());

        for (OcrBlock block : unknown) {
            languageIdentifier.identifyLanguage(block.original)
                    .addOnSuccessListener(languageTag -> {
                        String tag = languageTag;

                        if (tag == null || "und".equals(tag)) {
                            tag = looksMostlyLatin(block.original) ? "en" : null;
                        }

                        if (tag != null && !"ko".equals(tag)) {
                            groups.computeIfAbsent(
                                    tag,
                                    key -> Collections.synchronizedList(new ArrayList<>())
                            ).add(block);
                        } else {
                            block.translated = block.original;
                        }

                        if (remaining.decrementAndGet() == 0) {
                            translateGroups(groups, blocks, callback);
                        }
                    })
                    .addOnFailureListener(e -> {
                        if (looksMostlyLatin(block.original)) {
                            groups.computeIfAbsent(
                                    "en",
                                    key -> Collections.synchronizedList(new ArrayList<>())
                            ).add(block);
                        } else {
                            block.translated = block.original;
                        }

                        if (remaining.decrementAndGet() == 0) {
                            translateGroups(groups, blocks, callback);
                        }
                    });
        }
    }

    private void translateGroups(
            Map<String, List<OcrBlock>> groups,
            List<OcrBlock> allBlocks,
            Callback callback
    ) {
        if (groups.isEmpty()) {
            callback.onError("번역 가능한 외국어를 찾지 못했어요.");
            return;
        }

        AtomicInteger remainingGroups = new AtomicInteger(groups.size());
        AtomicInteger translatedCount = new AtomicInteger(0);

        for (Map.Entry<String, List<OcrBlock>> entry : groups.entrySet()) {
            translateGroup(
                    entry.getKey(),
                    entry.getValue(),
                    count -> {
                        translatedCount.addAndGet(count);

                        if (remainingGroups.decrementAndGet() == 0) {
                            if (translatedCount.get() == 0) {
                                callback.onError("번역 모델을 준비하지 못했어요.");
                            } else {
                                callback.onSuccess(allBlocks, false);
                            }
                        }
                    }
            );
        }
    }

    private interface GroupResult {
        void done(int translatedCount);
    }

    private void translateGroup(
            String languageTag,
            List<OcrBlock> blocks,
            GroupResult callback
    ) {
        String source = TranslateLanguage.fromLanguageTag(languageTag);

        if (source == null || TranslateLanguage.KOREAN.equals(source)) {
            callback.done(0);
            return;
        }

        Translator translator = getTranslator(source);

        Runnable runTranslations = () -> {
            AtomicInteger remaining = new AtomicInteger(blocks.size());
            AtomicInteger successes = new AtomicInteger(0);

            for (OcrBlock block : blocks) {
                translator.translate(block.original)
                        .addOnSuccessListener(text -> {
                            if (text != null
                                    && !text.trim().isEmpty()
                                    && !text.trim().equals(block.original)) {
                                block.translated = text.trim();
                                successes.incrementAndGet();
                            }

                            if (remaining.decrementAndGet() == 0) {
                                callback.done(successes.get());
                            }
                        })
                        .addOnFailureListener(e -> {
                            if (remaining.decrementAndGet() == 0) {
                                callback.done(successes.get());
                            }
                        });
            }
        };

        if (readyModels.contains(source)) {
            runTranslations.run();
            return;
        }

        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> {
                    readyModels.add(source);
                    runTranslations.run();
                })
                .addOnFailureListener(e -> callback.done(0));
    }

    private Translator getTranslator(String source) {
        Translator existing = translators.get(source);
        if (existing != null) {
            return existing;
        }

        TranslatorOptions options =
                new TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(TranslateLanguage.KOREAN)
                        .build();

        Translator created = Translation.getClient(options);
        Translator raced = translators.putIfAbsent(source, created);

        if (raced != null) {
            created.close();
            return raced;
        }

        return created;
    }

    private void warmUp(String languageTag) {
        String source = TranslateLanguage.fromLanguageTag(languageTag);
        if (source == null || TranslateLanguage.KOREAN.equals(source)) return;

        Translator translator = getTranslator(source);
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> readyModels.add(source));
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

            if (c >= '\u3040' && c <= '\u30FF') {
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

        if (hangul && !kana && !han && !devanagari) return "ko";
        if (kana) return "ja";
        if (han) return "zh";
        if (devanagari) return "hi";
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

    private void putCached(String source, String translated) {
        translationCache.put(normalize(source), translated);
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ");
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

    public void close() {
        languageIdentifier.close();

        for (Translator translator : translators.values()) {
            translator.close();
        }

        translators.clear();
        readyModels.clear();
        networkExecutor.shutdownNow();
    }
}
