package com.hanview.translate;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TranslationEngine {
    public static final String PREFS = "hanview";
    private static final String CACHE_PREFS = "viewnyang_local_natural_cache_v6";
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
    private final Handler fallbackHandler =
            new Handler(Looper.getMainLooper());
    private final AtomicInteger requestSequence =
            new AtomicInteger(0);

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

        // The live translation service is already running at this point. If
        // the user installed the 4B model, initialize it asynchronously now so
        // OCR and model startup overlap instead of making the first page wait.
        if (localContextTranslator.isReady()) {
            localContextTranslator.warmUp();
        }
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

        final int sequence =
                requestSequence.incrementAndGet();

        // Every new screen invalidates a slower refinement from the previous one.
        localContextTranslator.cancelPending();

        String pageHint =
                inferPageLanguage(blocks);

        String baseKey =
                buildPageKey(
                        blocks,
                        pageHint
                );

        boolean useContextModel =
                localContextTranslator.isReady()
                        && shouldUseContextModel(
                        blocks
                );

        if (!localContextTranslator.isReady()) {
            localContextTranslator.resetContext();
        }

        String refinedKey =
                "llm-v17:"
                        + baseKey;

        if (useContextModel) {
            String pageText =
                    loadPageTranslation(
                            refinedKey
                    );

            if (pageText != null
                    && !pageText.trim().isEmpty()) {
                callback.onSuccess(
                        Collections.singletonList(
                                makePageTranslationBlock(
                                        blocks,
                                        pageText
                                )
                        ),
                        true
                );
                return;
            }

            List<String> refinedCached =
                    getCachedPage(
                            refinedKey,
                            blocks.size()
                    );

            if (refinedCached != null) {
                applyCachedPage(
                        blocks,
                        refinedCached
                );
                callback.onSuccess(
                        blocks,
                        true
                );
                return;
            }
        }

        // Japanese manga with the 4B model installed is quality-first.
        // Do not paint ML Kit's rough sentence-by-sentence result first.
        // Wait for one context-aware result even when it takes longer.
        if (useContextModel) {
            AtomicBoolean deepDelivered =
                    new AtomicBoolean(false);

            startContextRefinement(
                    copyBlocks(blocks),
                    refinedKey,
                    sequence,
                    deepDelivered,
                    callback
            );
            return;
        }

        String fastKey =
                "fast-v15:"
                        + baseKey;

        List<String> fastCached =
                getCachedPage(
                        fastKey,
                        blocks.size()
                );

        if (fastCached != null) {
            applyCachedPage(
                    blocks,
                    fastCached
            );

            callback.onSuccess(
                    blocks,
                    false
            );

            if (useContextModel) {
                startContextRefinement(
                        copyBlocks(blocks),
                        refinedKey,
                        sequence,
                        new AtomicBoolean(false),
                        callback
                );
            }
            return;
        }

        AtomicBoolean deepStarted =
                new AtomicBoolean(false);
        AtomicBoolean deepDelivered =
                new AtomicBoolean(false);

        Runnable startDeepFallback = () -> {
            if (sequence
                    != requestSequence.get()) {
                return;
            }

            if (!useContextModel
                    || !deepStarted.compareAndSet(
                    false,
                    true
            )) {
                return;
            }

            // If the fast translator stalls, do not block the whole feature.
            // Let the 4B model translate the original Japanese directly.
            startContextRefinement(
                    copyBlocks(blocks),
                    refinedKey,
                    sequence,
                    deepDelivered,
                    callback
            );
        };

        if (useContextModel) {
            fallbackHandler.postDelayed(
                    startDeepFallback,
                    1400L
            );
        }

        translateLocalNatural(
                blocks,
                pageHint,
                new Callback() {
                    @Override
                    public void onSuccess(
                            List<OcrBlock> translated,
                            boolean usedAi
                    ) {
                        if (sequence
                                != requestSequence.get()) {
                            return;
                        }

                        saveCachedPage(
                                fastKey,
                                translated
                        );

                        // Do not overwrite a higher-quality result that already won the race.
                        if (!deepDelivered.get()) {
                            callback.onSuccess(
                                    translated,
                                    false
                            );
                        }

                        if (useContextModel
                                && deepStarted.compareAndSet(
                                false,
                                true
                        )) {
                            // Fast translation arrived in time: feed it as a draft
                            // so the 4B model can correct errors and rewrite it naturally.
                            startContextRefinement(
                                    copyBlocks(translated),
                                    refinedKey,
                                    sequence,
                                    deepDelivered,
                                    callback
                            );
                        }
                    }

                    @Override
                    public void onError(
                            String message
                    ) {
                        if (sequence
                                != requestSequence.get()) {
                            return;
                        }

                        if (useContextModel) {
                            if (deepStarted.compareAndSet(
                                    false,
                                    true
                            )) {
                                startContextRefinement(
                                        copyBlocks(blocks),
                                        refinedKey,
                                        sequence,
                                        deepDelivered,
                                        callback
                                );
                            }
                            return;
                        }

                        callback.onError(message);
                    }
                }
        );
    }

    private boolean shouldUseContextModel(
            List<OcrBlock> blocks
    ) {
        int kana = 0;
        int han = 0;
        int meaningfulChars = 0;

        for (OcrBlock block : blocks) {
            String value =
                    block.original;

            if (value == null) {
                continue;
            }

            for (int i = 0;
                 i < value.length();
                 i++) {
                char ch =
                        value.charAt(i);

                if (Character.isLetterOrDigit(ch)) {
                    meaningfulChars++;
                }

                if (ch >= 0x3040
                        && ch <= 0x30FF) {
                    kana++;
                } else if ((ch >= 0x3400
                        && ch <= 0x4DBF)
                        || (ch >= 0x4E00
                        && ch <= 0x9FFF)) {
                    han++;
                }
            }
        }

        boolean looksJapanese =
                kana >= 1
                        || han >= 2;

        if (!looksJapanese) {
            return false;
        }

        // Short labels, title cards and one-line captions do not benefit from
        // spinning up the 1.7B context model. ML Kit handles them immediately.
        // Reserve the heavier model for pages where multiple lines/bubbles or
        // enough text make speaker/context reconstruction worthwhile.
        if (meaningfulChars < 16) {
            return false;
        }

        int nonEmptyBlocks = 0;

        for (OcrBlock block : blocks) {
            if (block.original != null
                    && !block.original.trim().isEmpty()) {
                nonEmptyBlocks++;
            }
        }

        return meaningfulChars >= 28
                || nonEmptyBlocks >= 2;
    }

    private void startContextRefinement(
            List<OcrBlock> blocks,
            String cacheKey,
            int sequence,
            AtomicBoolean deepDelivered,
            Callback callback
    ) {
        localContextTranslator.translate(
                blocks,
                new LocalContextTranslator.Callback() {
                    @Override
                    public void onSuccess(
                            Map<Integer, String> translations,
                            String pageText
                    ) {
                        if (sequence
                                != requestSequence.get()) {
                            return;
                        }

                        int accepted = 0;

                        for (OcrBlock block : blocks) {
                            String value =
                                    translations.get(
                                            block.id
                                    );

                            if (value == null
                                    || value.trim().isEmpty()) {
                                continue;
                            }

                            block.translated =
                                    value.trim();
                            accepted++;
                        }

                        int minimum =
                                Math.max(
                                        1,
                                        (int) Math.ceil(
                                                blocks.size()
                                                        * 0.55
                                        )
                                );

                        // Prefer per-bubble results so the high-quality pass keeps
                        // the original text locations instead of collapsing the
                        // whole page into one giant translation card.
                        if (accepted >= minimum) {
                            saveCachedPage(
                                    cacheKey,
                                    blocks
                            );

                            deepDelivered.set(true);

                            callback.onSuccess(
                                    blocks,
                                    true
                            );
                            return;
                        }

                        // Plain-text/page output is only a compatibility fallback
                        // for a model response that could not be mapped by id.
                        if (pageText != null
                                && !pageText.trim().isEmpty()) {
                            String cleanPageText =
                                    pageText.trim();

                            savePageTranslation(
                                    cacheKey,
                                    cleanPageText
                            );

                            deepDelivered.set(true);

                            callback.onSuccess(
                                    Collections.singletonList(
                                            makePageTranslationBlock(
                                                    blocks,
                                                    cleanPageText
                                            )
                                    ),
                                    true
                            );
                            return;
                        }

                        fallbackFromContextModel(
                                blocks,
                                sequence,
                                callback
                        );
                    }

                    @Override
                    public void onError(
                            String message
                    ) {
                        if (sequence
                                != requestSequence.get()) {
                            return;
                        }

                        if (!deepDelivered.get()) {
                            fallbackFromContextModel(
                                    blocks,
                                    sequence,
                                    callback
                            );
                        }
                    }
                }
        );
    }

    private void fallbackFromContextModel(
            List<OcrBlock> blocks,
            int sequence,
            Callback callback
    ) {
        if (sequence
                != requestSequence.get()) {
            return;
        }

        translateLocalNatural(
                blocks,
                inferPageLanguage(
                        blocks
                ),
                new Callback() {
                    @Override
                    public void onSuccess(
                            List<OcrBlock> translated,
                            boolean usedAi
                    ) {
                        if (sequence
                                != requestSequence.get()) {
                            return;
                        }

                        callback.onSuccess(
                                translated,
                                false
                        );
                    }

                    @Override
                    public void onError(
                            String message
                    ) {
                        if (sequence
                                != requestSequence.get()) {
                            return;
                        }

                        callback.onError(
                                message
                        );
                    }
                }
        );
    }

    private OcrBlock makePageTranslationBlock(
            List<OcrBlock> source,
            String pageText
    ) {
        Rect bounds =
                new Rect();

        boolean initialized = false;
        StringBuilder original =
                new StringBuilder();

        OcrBlock styleSource =
                source.isEmpty()
                        ? null
                        : source.get(0);

        for (OcrBlock block : source) {
            if (!initialized) {
                bounds.set(
                        block.bounds
                );
                initialized = true;
            } else {
                bounds.union(
                        block.bounds
                );
            }

            if (block.original != null
                    && !block.original.trim().isEmpty()) {
                if (original.length() > 0) {
                    original.append("\n");
                }
                original.append(
                        block.original.trim()
                );
            }
        }

        if (!initialized) {
            bounds.set(
                    0,
                    0,
                    1,
                    1
            );
        }

        OcrBlock page =
                new OcrBlock(
                        source.isEmpty()
                                ? 0
                                : source.get(0).id,
                        original.toString(),
                        bounds
                );

        if (styleSource != null) {
            page.copyVisualStyleFrom(
                    styleSource
            );
        }

        page.translated =
                pageText;
        page.verticalSource =
                true;
        page.pageTranslation =
                true;

        return page;
    }

    private String loadPageTranslation(
            String key
    ) {
        String value =
                persistentCache.getString(
                        cacheKey(
                                "page-text:"
                                        + key
                        ),
                        ""
                );

        if (value == null
                || value.trim().isEmpty()) {
            return null;
        }

        return value;
    }

    private void savePageTranslation(
            String key,
            String pageText
    ) {
        if (pageText == null
                || pageText.trim().isEmpty()) {
            return;
        }

        persistentCache.edit()
                .putString(
                        cacheKey(
                                "page-text:"
                                        + key
                        ),
                        pageText.trim()
                )
                .apply();
    }

    private List<OcrBlock> copyBlocks(
            List<OcrBlock> source
    ) {
        List<OcrBlock> out =
                new ArrayList<>();

        for (OcrBlock block : source) {
            OcrBlock copy =
                    new OcrBlock(
                            block.id,
                            block.original,
                            block.bounds
                    );

            copy.translated =
                    block.translated;
            copy.copyVisualStyleFrom(
                    block
            );
            out.add(copy);
        }

        return out;
    }

    private List<String> getCachedPage(
            String key,
            int expectedSize
    ) {
        List<String> cached =
                pageCache.get(key);

        if (cached == null) {
            cached =
                    loadPersistedPage(
                            key,
                            expectedSize
                    );

            if (cached != null) {
                pageCache.put(
                        key,
                        cached
                );
            }
        }

        return cached;
    }

    private void applyCachedPage(
            List<OcrBlock> blocks,
            List<String> values
    ) {
        for (int i = 0;
             i < blocks.size()
                     && i < values.size();
             i++) {
            blocks.get(i).translated =
                    values.get(i);
        }
    }

    private void saveCachedPage(
            String key,
            List<OcrBlock> blocks
    ) {
        List<String> values =
                new ArrayList<>();

        for (OcrBlock block : blocks) {
            values.add(
                    block.translated == null
                            ? ""
                            : block.translated
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
                                if (hasCjkText(allBlocks)
                                        && !localContextTranslator.isReady()) {
                                    callback.onError(
                                            "실시간 1.7B 번역 모델이 설치되어 있지 않아요. 뷰냥 앱을 열어 약 1GB 모델을 한 번 설치해 주세요."
                                    );
                                } else {
                                    callback.onError(
                                            "간이 기기 번역 모델 다운로드에 실패했어요. 네트워크 연결을 확인한 뒤 다시 시도해 주세요."
                                    );
                                }
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

    private boolean hasCjkText(
            List<OcrBlock> blocks
    ) {
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

                if ((c >= 0x3040
                        && c <= 0x30FF)
                        || (c >= 0x3400
                        && c <= 0x4DBF)
                        || (c >= 0x4E00
                        && c <= 0x9FFF)) {
                    return true;
                }
            }
        }

        return false;
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
        requestSequence.incrementAndGet();
        // ML Kit tasks cannot be cancelled reliably, but stale LLM refinement can.
        localContextTranslator.cancelPending();
    }

    public void abortContextModel() {
        requestSequence.incrementAndGet();
        localContextTranslator.abortAndReset();
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
