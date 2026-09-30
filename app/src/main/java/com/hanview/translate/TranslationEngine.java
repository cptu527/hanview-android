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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TranslationEngine {
    public static final String PREFS = "hanview";
    private static final String CACHE_PREFS = "viewnyang_local_natural_cache_v4";
    private static final String CACHE_INDEX = "_index";
    private static final int MAX_PERSISTED_PAGES = 120;

    public interface Callback {
        void onSuccess(List<OcrBlock> blocks, boolean usedAi);
        void onError(String message);
    }

    private final Context context;
    private final LanguageIdentifier languageIdentifier =
            LanguageIdentification.getClient();
    private final Map<String, Translator> translators =
            new ConcurrentHashMap<>();
    private final Set<String> readyModels =
            ConcurrentHashMap.newKeySet();
    private final SharedPreferences persistentCache;
    private final LocalContextTranslator localContextTranslator;

    private final Map<String, List<String>> pageCache =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, List<String>>(36, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, List<String>> eldest
                        ) {
                            return size() > 36;
                        }
                    }
            );

    public TranslationEngine(Context context) {
        this.context = context.getApplicationContext();
        persistentCache =
                this.context.getSharedPreferences(
                        CACHE_PREFS,
                        Context.MODE_PRIVATE
                );
        localContextTranslator =
                new LocalContextTranslator(
                        this.context
                );

        // Download the common models quietly once. After that, translation is
        // on-device and does not consume ChatGPT/API usage.
        warmUp("ja");
        warmUp("en");
        warmUp("zh");
    }

    public static void prewarmCommon(Context context) {
        String[] common = new String[]{"ja", "en", "zh"};

        for (String tag : common) {
            String source =
                    TranslateLanguage.fromLanguageTag(tag);

            if (source == null
                    || TranslateLanguage.KOREAN.equals(source)) {
                continue;
            }

            TranslatorOptions options =
                    new TranslatorOptions.Builder()
                            .setSourceLanguage(source)
                            .setTargetLanguage(TranslateLanguage.KOREAN)
                            .build();

            Translator translator =
                    Translation.getClient(options);

            translator.downloadModelIfNeeded(
                            new DownloadConditions.Builder().build()
                    )
                    .addOnCompleteListener(task ->
                            translator.close()
                    );
        }
    }

    public void translate(
            List<OcrBlock> blocks,
            Callback callback
    ) {
        if (blocks.isEmpty()) {
            callback.onSuccess(blocks, false);
            return;
        }

        String pageHint =
                inferPageLanguage(blocks);

        String key =
                (
                        localContextTranslator.isReady()
                                ? "llm-v3:"
                                : "local-v4:"
                )
                        + buildPageKey(
                        blocks,
                        pageHint
                );

        List<String> cached =
                pageCache.get(key);

        if (cached == null) {
            cached =
                    loadPersistedPage(
                            key,
                            blocks.size()
                    );

            if (cached != null) {
                pageCache.put(
                        key,
                        cached
                );
            }
        }

        if (cached != null
                && cached.size() == blocks.size()) {
            for (int i = 0;
                 i < blocks.size();
                 i++) {
                blocks.get(i).translated =
                        cached.get(i);
            }

            callback.onSuccess(
                    blocks,
                    localContextTranslator.isReady()
            );
            return;
        }

        Callback cacheAndReturn =
                new Callback() {
                    @Override
                    public void onSuccess(
                            List<OcrBlock> translated,
                            boolean usedAi
                    ) {
                        List<String> values =
                                new ArrayList<>();

                        for (OcrBlock block :
                                translated) {
                            values.add(
                                    block.translated
                            );
                        }

                        pageCache.put(
                                key,
                                values
                        );
                        savePersistedPage(
                                key,
                                values
                        );

                        callback.onSuccess(
                                translated,
                                usedAi
                        );
                    }

                    @Override
                    public void onError(
                            String message
                    ) {
                        callback.onError(message);
                    }
                };

        if (localContextTranslator.isReady()) {
            translateWithLocalContextModel(
                    blocks,
                    pageHint,
                    cacheAndReturn
            );
        } else {
            translateLocalNatural(
                    blocks,
                    pageHint,
                    cacheAndReturn
            );
        }
    }

    private void translateWithLocalContextModel(
            List<OcrBlock> blocks,
            String pageHint,
            Callback callback
    ) {
        localContextTranslator.translate(
                blocks,
                new LocalContextTranslator.Callback() {
                    @Override
                    public void onSuccess(
                            Map<Integer, String> translations
                    ) {
                        List<OcrBlock> missing =
                                new ArrayList<>();

                        for (OcrBlock block : blocks) {
                            String value =
                                    translations.get(
                                            block.id
                                    );

                            if (value == null
                                    || value.trim().isEmpty()) {
                                missing.add(block);
                            } else {
                                block.translated =
                                        value.trim();
                            }
                        }

                        if (missing.isEmpty()) {
                            callback.onSuccess(
                                    blocks,
                                    true
                            );
                            return;
                        }

                        translateLocalNatural(
                                missing,
                                pageHint,
                                new Callback() {
                                    @Override
                                    public void onSuccess(
                                            List<OcrBlock> ignored,
                                            boolean usedAi
                                    ) {
                                        callback.onSuccess(
                                                blocks,
                                                true
                                        );
                                    }

                                    @Override
                                    public void onError(
                                            String message
                                    ) {
                                        callback.onSuccess(
                                                blocks,
                                                true
                                        );
                                    }
                                }
                        );
                    }

                    @Override
                    public void onError(
                            String message
                    ) {
                        // If the local LLM cannot initialize or parse one page,
                        // keep live translation usable with the bundled translator.
                        translateLocalNatural(
                                blocks,
                                pageHint,
                                callback
                        );
                    }
                }
        );
    }

    private void translateLocalNatural(
            List<OcrBlock> blocks,
            String pageHint,
            Callback callback
    ) {
        Map<String, List<OcrBlock>> groups =
                new ConcurrentHashMap<>();

        List<OcrBlock> unknown =
                Collections.synchronizedList(
                        new ArrayList<>()
                );

        for (OcrBlock block : blocks) {
            String quick =
                    quickLanguageGuess(
                            block.original,
                            pageHint
                    );

            if ("ko".equals(quick)) {
                block.translated =
                        polishKorean(
                                block.original,
                                block.original
                        );
            } else if (quick != null) {
                groups.computeIfAbsent(
                        quick,
                        key ->
                                Collections.synchronizedList(
                                        new ArrayList<>()
                                )
                ).add(block);
            } else {
                unknown.add(block);
            }
        }

        if (unknown.isEmpty()) {
            translateGroups(
                    groups,
                    blocks,
                    callback
            );
            return;
        }

        AtomicInteger remaining =
                new AtomicInteger(
                        unknown.size()
                );

        for (OcrBlock block : unknown) {
            languageIdentifier
                    .identifyLanguage(
                            block.original
                    )
                    .addOnSuccessListener(
                            languageTag -> {
                                String tag =
                                        languageTag;

                                if (tag == null
                                        || "und".equals(tag)) {
                                    tag =
                                            looksMostlyLatin(
                                                    block.original
                                            )
                                                    ? "en"
                                                    : pageHint;
                                }

                                if (tag != null
                                        && !"ko".equals(tag)) {
                                    groups.computeIfAbsent(
                                            tag,
                                            key ->
                                                    Collections.synchronizedList(
                                                            new ArrayList<>()
                                                    )
                                    ).add(block);
                                } else {
                                    block.translated =
                                            polishKorean(
                                                    block.original,
                                                    block.original
                                            );
                                }

                                if (remaining.decrementAndGet()
                                        == 0) {
                                    translateGroups(
                                            groups,
                                            blocks,
                                            callback
                                    );
                                }
                            }
                    )
                    .addOnFailureListener(
                            error -> {
                                String tag =
                                        looksMostlyLatin(
                                                block.original
                                        )
                                                ? "en"
                                                : pageHint;

                                if (tag != null
                                        && !"ko".equals(tag)) {
                                    groups.computeIfAbsent(
                                            tag,
                                            key ->
                                                    Collections.synchronizedList(
                                                            new ArrayList<>()
                                                    )
                                    ).add(block);
                                } else {
                                    block.translated =
                                            block.original;
                                }

                                if (remaining.decrementAndGet()
                                        == 0) {
                                    translateGroups(
                                            groups,
                                            blocks,
                                            callback
                                    );
                                }
                            }
                    );
        }
    }

    private void translateGroups(
            Map<String, List<OcrBlock>> groups,
            List<OcrBlock> allBlocks,
            Callback callback
    ) {
        if (groups.isEmpty()) {
            callback.onError(
                    "번역할 외국어 문장을 찾지 못했어요."
            );
            return;
        }

        AtomicInteger remainingGroups =
                new AtomicInteger(
                        groups.size()
                );
        AtomicInteger translatedCount =
                new AtomicInteger(0);

        for (Map.Entry<String, List<OcrBlock>>
                entry : groups.entrySet()) {
            translateGroup(
                    entry.getKey(),
                    entry.getValue(),
                    count -> {
                        translatedCount.addAndGet(
                                count
                        );

                        if (remainingGroups.decrementAndGet()
                                == 0) {
                            if (translatedCount.get()
                                    == 0) {
                                callback.onError(
                                        "무료 기기 번역 모델을 준비하지 못했어요. 인터넷에 연결한 상태에서 한 번 더 시도해 주세요."
                                );
                            } else {
                                callback.onSuccess(
                                        allBlocks,
                                        false
                                );
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
        String source =
                TranslateLanguage.fromLanguageTag(
                        languageTag
                );

        if (source == null
                || TranslateLanguage.KOREAN.equals(
                source
        )) {
            callback.done(0);
            return;
        }

        Translator translator =
                getTranslator(source);

        Runnable runTranslations = () -> {
            AtomicInteger remaining =
                    new AtomicInteger(
                            blocks.size()
                    );
            AtomicInteger successes =
                    new AtomicInteger(0);

            for (OcrBlock block : blocks) {
                // Translate only the actual merged paragraph. ML Kit Translate is
                // not a generative model; feeding fake PREV/TARGET/NEXT markers
                // makes it translate the markers and often destroys the sentence.
                String sourceText =
                        normalizeSource(
                                block.original
                        );

                translator.translate(
                                sourceText
                        )
                        .addOnSuccessListener(
                                text -> {
                                    if (text != null
                                            && !text.trim().isEmpty()
                                            && !text.trim().equals(
                                            block.original
                                    )) {
                                        block.translated =
                                                polishKorean(
                                                        text,
                                                        block.original
                                                );
                                        successes.incrementAndGet();
                                    }

                                    if (remaining.decrementAndGet()
                                            == 0) {
                                        callback.done(
                                                successes.get()
                                        );
                                    }
                                }
                        )
                        .addOnFailureListener(
                                error -> {
                                    if (remaining.decrementAndGet()
                                            == 0) {
                                        callback.done(
                                                successes.get()
                                        );
                                    }
                                }
                        );
            }
        };

        if (readyModels.contains(source)) {
            runTranslations.run();
            return;
        }

        translator.downloadModelIfNeeded(
                        new DownloadConditions.Builder().build()
                )
                .addOnSuccessListener(
                        unused -> {
                            readyModels.add(
                                    source
                            );
                            runTranslations.run();
                        }
                )
                .addOnFailureListener(
                        error ->
                                callback.done(0)
                );
    }

    private Translator getTranslator(
            String source
    ) {
        Translator existing =
                translators.get(source);

        if (existing != null) {
            return existing;
        }

        TranslatorOptions options =
                new TranslatorOptions.Builder()
                        .setSourceLanguage(
                                source
                        )
                        .setTargetLanguage(
                                TranslateLanguage.KOREAN
                        )
                        .build();

        Translator created =
                Translation.getClient(
                        options
                );

        Translator raced =
                translators.putIfAbsent(
                        source,
                        created
                );

        if (raced != null) {
            created.close();
            return raced;
        }

        return created;
    }

    private void warmUp(
            String languageTag
    ) {
        String source =
                TranslateLanguage
                        .fromLanguageTag(
                                languageTag
                        );

        if (source == null
                || TranslateLanguage.KOREAN.equals(
                source
        )) {
            return;
        }

        Translator translator =
                getTranslator(source);

        translator.downloadModelIfNeeded(
                        new DownloadConditions.Builder().build()
                )
                .addOnSuccessListener(
                        unused ->
                                readyModels.add(
                                        source
                                )
                );
    }

    private String normalizeSource(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\\n", "")
                .replace(" ", "")
                .replace("｡", "。")
                .replace("､", "、")
                .trim();
    }

    /**
     * Conservative Korean cleanup after the on-device translator.
     * This intentionally avoids inventing context or changing honorific level.
     * It only fixes common machine-translation stiffness and punctuation.
     */
    private String polishKorean(
            String translated,
            String source
    ) {
        if (translated == null) {
            return "";
        }

        String out =
                translated.trim();

        out = out
                .replace("「", "“")
                .replace("」", "”")
                .replace("『", "‘")
                .replace("』", "’")
                .replace("！", "!")
                .replace("？", "?")
                .replace("。", ".")
                .replace("，", ",")
                .replace("、", ", ");

        out = out
                .replaceAll("\\s+([,.!?])", "$1")
                .replaceAll("([,.!?])(?=[가-힣A-Za-z0-9“‘])", "$1 ")
                .replaceAll("\\s{2,}", " ")
                .trim();

        // Frequent literal Japanese constructions that are safe to compact.
        out = out
                .replaceAll("하지 않으면 안 됩니다([.!?]?)$", "해야 합니다$1")
                .replaceAll("하지 않으면 안 돼요([.!?]?)$", "해야 해요$1")
                .replaceAll("하지 않으면 안 된다([.!?]?)$", "해야 한다$1")
                .replaceAll("하지 않으면 안 돼([.!?]?)$", "해야 해$1")
                .replaceAll("하는 것이 가능합니다", "할 수 있습니다")
                .replaceAll("하는 것이 가능해요", "할 수 있어요")
                .replaceAll("할 수 있는 것이 아닙니다", "할 수 없습니다")
                .replaceAll("에 대해서", "에 대해")
                .replaceAll("라고 하는 ", "라는 ")
                .replaceAll("이라고 하는 ", "이라는 ");

        // ML Kit occasionally duplicates punctuation around Japanese quotes.
        out = out
                .replaceAll("([.!?])\\1+", "$1")
                .replaceAll("”\\s*\\.", ".”")
                .replaceAll("’\\s*\\.", ".’")
                .trim();

        return out;
    }

    private String quickLanguageGuess(
            String value,
            String pageHint
    ) {
        boolean hangul = false;
        boolean kana = false;
        boolean han = false;
        boolean devanagari = false;

        if (value == null) {
            return null;
        }

        for (int i = 0;
             i < value.length();
             i++) {
            char c =
                    value.charAt(i);

            if ((c >= 0xAC00
                    && c <= 0xD7A3)
                    || (c >= 0x1100
                    && c <= 0x11FF)) {
                hangul = true;
            }

            if (c >= 0x3040
                    && c <= 0x30FF) {
                kana = true;
            }

            if ((c >= 0x3400
                    && c <= 0x4DBF)
                    || (c >= 0x4E00
                    && c <= 0x9FFF)) {
                han = true;
            }

            if (c >= 0x0900
                    && c <= 0x097F) {
                devanagari = true;
            }
        }

        if (hangul
                && !kana
                && !han
                && !devanagari) {
            return "ko";
        }

        if (kana) {
            return "ja";
        }

        if (han
                && "ja".equals(
                pageHint
        )) {
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

    private String inferPageLanguage(
            List<OcrBlock> blocks
    ) {
        int kana = 0;
        int han = 0;

        for (OcrBlock block : blocks) {
            String value =
                    block.original;

            if (value == null) {
                continue;
            }

            for (int i = 0;
                 i < value.length();
                 i++) {
                char c =
                        value.charAt(i);

                if (c >= 0x3040
                        && c <= 0x30FF) {
                    kana++;
                } else if ((c >= 0x3400
                        && c <= 0x4DBF)
                        || (c >= 0x4E00
                        && c <= 0x9FFF)) {
                    han++;
                }
            }
        }

        if (kana >= 2) {
            return "ja";
        }

        if (han >= 2) {
            return "zh";
        }

        return null;
    }

    private boolean looksMostlyLatin(
            String value
    ) {
        int latin = 0;
        int letters = 0;

        if (value == null) {
            return false;
        }

        for (int i = 0;
             i < value.length();
             i++) {
            char c =
                    value.charAt(i);

            if (Character.isLetter(c)) {
                letters++;

                if ((c >= 'A'
                        && c <= 'Z')
                        || (c >= 'a'
                        && c <= 'z')
                        || (c >= 0x00C0
                        && c <= 0x024F)) {
                    latin++;
                }
            }
        }

        return letters > 0
                && latin * 2 >= letters;
    }

    private String buildPageKey(
            List<OcrBlock> blocks,
            String pageHint
    ) {
        StringBuilder signature =
                new StringBuilder(
                        "local-natural:"
                                + (pageHint == null
                                ? "auto"
                                : pageHint)
                                + ":"
                );

        for (OcrBlock block : blocks) {
            String source =
                    block.original == null
                            ? ""
                            : block.original
                                    .trim()
                                    .replaceAll(
                                            "\\s+",
                                            " "
                                    );

            signature.append(
                            source.length()
                    )
                    .append(':')
                    .append(source)
                    .append('|')
                    .append(
                            block.verticalSource
                    )
                    .append(';');
        }

        return signature.toString();
    }

    private List<String> loadPersistedPage(
            String pageKey,
            int expectedSize
    ) {
        try {
            String raw =
                    persistentCache.getString(
                            cacheKey(pageKey),
                            ""
                    );

            if (raw.isEmpty()) {
                return null;
            }

            JSONArray values =
                    new JSONArray(raw);

            if (values.length()
                    != expectedSize) {
                return null;
            }

            List<String> out =
                    new ArrayList<>();

            for (int i = 0;
                 i < values.length();
                 i++) {
                String value =
                        values.optString(
                                i,
                                ""
                        ).trim();

                if (value.isEmpty()) {
                    return null;
                }

                out.add(value);
            }

            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private synchronized void savePersistedPage(
            String pageKey,
            List<String> values
    ) {
        try {
            String hash =
                    cacheKey(pageKey);

            JSONArray payload =
                    new JSONArray();

            for (String value : values) {
                payload.put(
                        value == null
                                ? ""
                                : value
                );
            }

            JSONArray oldIndex;

            try {
                oldIndex =
                        new JSONArray(
                                persistentCache.getString(
                                        CACHE_INDEX,
                                        "[]"
                                )
                        );
            } catch (Exception ignored) {
                oldIndex =
                        new JSONArray();
            }

            JSONArray nextIndex =
                    new JSONArray();

            for (int i = 0;
                 i < oldIndex.length();
                 i++) {
                String item =
                        oldIndex.optString(
                                i,
                                ""
                        );

                if (!item.isEmpty()
                        && !hash.equals(
                        item
                )) {
                    nextIndex.put(item);
                }
            }

            nextIndex.put(hash);

            SharedPreferences.Editor editor =
                    persistentCache.edit()
                            .putString(
                                    hash,
                                    payload.toString()
                            );

            while (nextIndex.length()
                    > MAX_PERSISTED_PAGES) {
                String oldest =
                        nextIndex.optString(
                                0,
                                ""
                        );

                JSONArray trimmed =
                        new JSONArray();

                for (int i = 1;
                     i < nextIndex.length();
                     i++) {
                    trimmed.put(
                            nextIndex.optString(
                                    i,
                                    ""
                            )
                    );
                }

                nextIndex = trimmed;

                if (!oldest.isEmpty()) {
                    editor.remove(oldest);
                }
            }

            editor.putString(
                    CACHE_INDEX,
                    nextIndex.toString()
            ).apply();
        } catch (Exception ignored) {
        }
    }

    private String cacheKey(
            String value
    ) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            byte[] bytes =
                    digest.digest(
                            value.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            StringBuilder out =
                    new StringBuilder(
                            "p_"
                    );

            for (byte b : bytes) {
                out.append(
                        String.format(
                                java.util.Locale.ROOT,
                                "%02x",
                                b & 0xff
                        )
                );
            }

            return out.toString();
        } catch (Exception ignored) {
            return "p_"
                    + Integer.toHexString(
                    value.hashCode()
            );
        }
    }

    public void cancelPending() {
        // On-device ML Kit tasks cannot be cancelled reliably. Generation checks
        // in OverlayCaptureService prevent stale results from being displayed.
    }

    public void close() {
        languageIdentifier.close();

        for (Translator translator :
                translators.values()) {
            translator.close();
        }

        translators.clear();
        readyModels.clear();
        pageCache.clear();

        if (localContextTranslator != null) {
            localContextTranslator.close();
        }
    }
}
