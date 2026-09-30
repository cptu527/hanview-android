package com.hanview.translate

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LocalContextTranslator(
    context: Context
) {
    interface Callback {
        fun onSuccess(
            translations: Map<Int, String>,
            pageText: String
        )
        fun onError(message: String)
    }

    interface DownloadCallback {
        fun onProgress(percent: Int)
        fun onSuccess()
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineMutex = Mutex()
    private val inferenceMutex = Mutex()
    @Volatile private var engine: Engine? = null
    @Volatile private var activeConversation: Conversation? = null
    private val closed = AtomicBoolean(false)
    private val requestSequence = AtomicInteger(0)
    private val resetEngineRequested = AtomicBoolean(false)
    @Volatile private var previousPageContext: String = ""
    @Volatile private var activeBackend: String = "none"

    fun isReady(): Boolean = isModelReady(appContext)

    fun cancelPending() {
        requestSequence.incrementAndGet()

        // Cancelling only the callback sequence is not enough: the native GPU
        // decode would keep running and block the next page. Stop the actual
        // LiteRT-LM conversation as well.
        activeConversation?.let { conversation ->
            runCatching {
                conversation.cancelProcess()
            }
        }
    }

    fun abortAndReset() {
        requestSequence.incrementAndGet()
        resetEngineRequested.set(true)

        activeConversation?.let { conversation ->
            runCatching {
                conversation.cancelProcess()
            }
        }

        // If no inference is active, reset immediately. If one is active this
        // waits for its cancellation to unwind, then recreates the engine on
        // the next request.
        scope.launch {
            inferenceMutex.withLock {
                resetEngineIfRequested()
            }
        }
    }

    fun resetContext() {
        previousPageContext = ""
    }

    fun backendLabel(): String = activeBackend

    fun warmUp() {
        if (!isReady() || closed.get()) return

        scope.launch {
            try {
                ensureEngine()
            } catch (_: Throwable) {
            }
        }
    }

    fun translate(
        blocks: List<OcrBlock>,
        callback: Callback
    ) {
        if (!isReady()) {
            callback.onError("실시간 1.7B 번역 모델이 아직 설치되지 않았어요.")
            return
        }

        val sequence = requestSequence.incrementAndGet()

        val snapshot = blocks.map { block ->
            OcrBlock(
                block.id,
                block.original,
                block.bounds
            ).also {
                it.translated = block.translated
                it.verticalSource = block.verticalSource
                it.sourceGlyphWidthPx = block.sourceGlyphWidthPx
                it.sourceTextSizePx = block.sourceTextSizePx
                it.backgroundColor = block.backgroundColor
                it.textColor = block.textColor
                it.solidBackground = block.solidBackground
            }
        }

        scope.launch {
            inferenceMutex.withLock {
                try {
                    if (closed.get()) return@withLock

                    resetEngineIfRequested()
                    val activeEngine = ensureEngine()

                // One deliberate 4B pass is noticeably faster on phones than
                // running a reconstruction generation and then a translation
                // generation back-to-back. The prompt still asks the model to
                // repair obvious OCR errors before translating.
                val reconstructed =
                    snapshot.associate { block ->
                        block.id to block.original
                            .replace("\\n", "")
                            .trim()
                    }

                val prompt =
                    buildPrompt(
                        snapshot,
                        reconstructed
                    )

                val systemInstruction = Contents.of(
                    "/no_think\n" +
                            "너는 일본어 만화·게임을 한국어로 현지화하는 전문 번역가다. " +
                            "입력 source_ja는 OCR 결과라서 세로쓰기 분절, 글자 누락, 비슷한 한자 오인식이 있을 수 있다. " +
                            "번역하기 전에 페이지 전체와 box 좌표, reading_order_hint, previous_page_context를 함께 보고 명백한 OCR 오류만 내부적으로 복원해라. " +
                            "세로쓰기는 같은 대사 안에서 위→아래, 열은 오른쪽→왼쪽으로 읽고 서로 다른 말풍선이나 패널을 억지로 합치지 마라. " +
                            "화자, 대상, 관계, 호칭, 존댓말/반말, 감정과 앞뒤 논리를 파악한 뒤 실제 한국 만화 대사처럼 자연스럽게 번역해라. " +
                            "원문의 의미, 비난 강도, 욕설, 성적 표현, 은어는 임의로 순화·과장·삭제하지 않는다. 모르는 내용은 만들어내지 않는다. " +
                            "각 id를 정확히 한 번씩 반환하고 중복하지 않는다. 설명·해설·번역 노트·메타 발언은 금지한다. " +
                            "출력은 JSON 하나만 반환한다: {\"page_text\":\"\",\"translations\":[{\"id\":0,\"text\":\"최종 한국어\"}]}."
                )

                val config = ConversationConfig(
                    systemInstruction = systemInstruction,
                    extraContext = mapOf(
                        "enable_thinking" to false
                    ),
                    thinkingConfig = ThinkingConfig(
                        enableThinking = false
                    )
                )

                fun generateOnce(
                    promptText: String,
                    maxOutputTokens: Int
                ): String {
                    val conversation =
                        activeEngine.createConversation(
                            config
                        )

                    activeConversation = conversation

                    return try {
                        conversation.sendMessage(
                            promptText,
                            extraContext = mapOf(
                                "enable_thinking" to false
                            ),
                            maxOutputToken = maxOutputTokens,
                            thinkingConfig = ThinkingConfig(
                                enableThinking = false
                            )
                        ).toString()
                    } finally {
                        if (activeConversation === conversation) {
                            activeConversation = null
                        }

                        runCatching {
                            conversation.close()
                        }
                    }
                }

                val responseText =
                    try {
                        generateOnce(
                            prompt,
                            420
                        )
                    } catch (firstError: Throwable) {
                        if (!isInputContextTooLong(firstError)) {
                            throw firstError
                        }

                        // The 1.7B artifact supports a 4096-token context, but
                        // a previous-page carry-over can still push a dense manga
                        // page over the runtime's safety threshold. Retry once
                        // without history instead of surfacing INVALID_ARGUMENT.
                        previousPageContext = ""

                        generateOnce(
                            buildPrompt(
                                snapshot,
                                reconstructed
                            ),
                            360
                        )
                    }

                val parsed = parseResponse(responseText)

                if (sequence != requestSequence.get()) {
                    return@withLock
                }

                if (parsed.translations.isEmpty()
                    && parsed.pageText.isBlank()
                ) {
                    callback.onError("실시간 1.7B 모델의 번역 결과를 읽지 못했어요.")
                } else {
                    previousPageContext =
                        buildPreviousContext(
                            snapshot,
                            reconstructed,
                            parsed.translations,
                            parsed.pageText
                        )
                    callback.onSuccess(
                        parsed.translations,
                        parsed.pageText
                    )
                }
                } catch (t: Throwable) {
                    if (sequence != requestSequence.get()) {
                        return@withLock
                    }

                    callback.onError(
                        t.message?.takeIf { it.isNotBlank() }
                            ?: "실시간 1.7B 번역 중 오류가 발생했어요."
                    )
                } finally {
                    // A hard timeout asks for a fresh native engine. Do it only
                    // after the current decode has unwound so engine lifetime
                    // cannot race with native inference.
                    resetEngineIfRequested()
                }
            }
        }
    }

    private suspend fun resetEngineIfRequested() {
        if (!resetEngineRequested.compareAndSet(
                true,
                false
            )
        ) {
            return
        }

        activeConversation = null

        engineMutex.withLock {
            val current = engine
            engine = null
            activeBackend = "none"

            if (current != null) {
                runCatching {
                    current.close()
                }
            }
        }
    }

    private fun isInputContextTooLong(
        error: Throwable
    ): Boolean {
        var current: Throwable? = error

        while (current != null) {
            val message =
                current.message
                    .orEmpty()
                    .lowercase()

            if (message.contains(
                    "input context length is too long"
                )
                || message.contains(
                    "input token ids are too long"
                )
                || message.contains(
                    "exceeding the maximum number of tokens"
                )
                || message.contains(
                    "max_num_tokens"
                )
            ) {
                return true
            }

            current = current.cause
        }

        return false
    }

    private suspend fun ensureEngine(): Engine {
        engine?.let { return it }

        return engineMutex.withLock {
            engine?.let { return@withLock it }

            val file = modelFile(appContext)
            if (!file.exists() || file.length() < MIN_MODEL_BYTES) {
                throw IllegalStateException("실시간 1.7B 번역 모델 파일이 없어요.")
            }

            fun createEngine(
                backend: Backend,
                label: String
            ): Engine {
                val created = Engine(
                    EngineConfig(
                        modelPath = file.absolutePath,
                        backend = backend,
                        maxNumTokens = 4096,
                        cacheDir = appContext.cacheDir.absolutePath
                    )
                )

                created.initialize()
                activeBackend = label
                return created
            }

            // The block32 1.7B bundle is a GPU-specialized live model.
            // Silent CPU fallback makes a single page take minutes and looks hung.
            // Fail fast instead so the UI can show the real GPU/runtime problem.
            val created =
                try {
                    createEngine(
                        Backend.GPU(),
                        "gpu"
                    )
                } catch (gpuError: Throwable) {
                    activeBackend = "gpu-error"
                    throw IllegalStateException(
                        "GPU 번역 엔진 초기화 실패. 앱을 최신 버전으로 업데이트한 뒤 다시 시도해 주세요.",
                        gpuError
                    )
                }

            engine = created
            created
        }
    }

    private fun buildReconstructionPrompt(
        blocks: List<OcrBlock>
    ): String {
        val items = JSONArray()

        blocks.forEach { block ->
            val box = JSONArray()
                .put(block.bounds.left)
                .put(block.bounds.top)
                .put(block.bounds.right)
                .put(block.bounds.bottom)

            items.put(
                JSONObject()
                    .put("id", block.id)
                    .put("vertical", block.verticalSource)
                    .put("box", box)
                    .put(
                        "source_ja",
                        block.original
                            .replace("\\n", "")
                            .trim()
                    )
            )
        }

        val root = JSONObject()
            .put(
                "task",
                "Reconstruct the Japanese OCR only. Do not translate. Keep each id. Use coordinates for vertical reading order and obvious OCR errors, but never invent unsupported dialogue."
            )
            .put("items", items)

        val previous =
            previousPageContext.trim()

        if (previous.isNotEmpty()) {
            root.put(
                "previous_page_context",
                previous.takeLast(1400)
            )
        }

        return root.toString()
    }

    private fun parseReconstructedSources(
        raw: String,
        blocks: List<OcrBlock>
    ): Map<Int, String> {
        val fallback =
            LinkedHashMap<Int, String>()

        blocks.forEach {
            fallback[it.id] =
                it.original
                    .replace("\\n", "")
                    .trim()
        }

        var clean =
            raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

        val start =
            clean.indexOf('{')
        val end =
            clean.lastIndexOf('}')

        if (start < 0 || end <= start) {
            return fallback
        }

        return try {
            clean =
                clean.substring(
                    start,
                    end + 1
                )

            val root =
                JSONObject(clean)
            val list =
                root.optJSONArray(
                    "items"
                )
                ?: return fallback

            val parsed =
                LinkedHashMap<Int, String>()

            for (i in 0 until list.length()) {
                val item =
                    list.optJSONObject(i)
                        ?: continue
                val id =
                    item.optInt(
                        "id",
                        Int.MIN_VALUE
                    )
                val text =
                    item.optString(
                        "source_ja",
                        ""
                    )
                        .replace("\\n", "")
                        .trim()

                if (id != Int.MIN_VALUE
                    && text.isNotEmpty()
                ) {
                    parsed[id] =
                        text
                }
            }

            val minimum =
                kotlin.math.max(
                    1,
                    kotlin.math.ceil(
                        blocks.size * 0.70
                    ).toInt()
                )

            if (parsed.size < minimum) {
                fallback
            } else {
                fallback.apply {
                    putAll(parsed)
                }
            }
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun buildPrompt(
        blocks: List<OcrBlock>,
        reconstructed: Map<Int, String>
    ): String {
        val ordered = blocks.sortedWith { a, b ->
            val av = a.verticalSource ||
                a.bounds.height() > a.bounds.width() * 1.6f
            val bv = b.verticalSource ||
                b.bounds.height() > b.bounds.width() * 1.6f

            if (av && bv) {
                val verticalOverlap =
                    kotlin.math.max(
                        0,
                        kotlin.math.min(
                            a.bounds.bottom,
                            b.bounds.bottom
                        ) - kotlin.math.max(
                            a.bounds.top,
                            b.bounds.top
                        )
                    )
                val minHeight =
                    kotlin.math.max(
                        1,
                        kotlin.math.min(
                            a.bounds.height(),
                            b.bounds.height()
                        )
                    )

                if (verticalOverlap.toFloat() / minHeight >= 0.20f) {
                    val byX =
                        b.bounds.centerX()
                            .compareTo(
                                a.bounds.centerX()
                            )
                    if (byX != 0) return@sortedWith byX
                }
            }

            val byTop =
                a.bounds.top.compareTo(
                    b.bounds.top
                )
            if (byTop != 0) {
                byTop
            } else if (av && bv) {
                b.bounds.centerX()
                    .compareTo(
                        a.bounds.centerX()
                    )
            } else {
                a.bounds.left.compareTo(
                    b.bounds.left
                )
            }
        }

        val items = JSONArray()

        ordered.forEachIndexed { index, block ->
            val box = JSONArray()
                .put(block.bounds.left)
                .put(block.bounds.top)
                .put(block.bounds.right)
                .put(block.bounds.bottom)

            items.put(
                JSONObject()
                    .put("id", block.id)
                    .put("reading_order_hint", index)
                    .put("vertical", block.verticalSource)
                    .put("box", box)
                    .put(
                        "source_ja",
                        reconstructed[block.id]
                            ?.takeIf { it.isNotBlank() }
                            ?: block.original
                                .replace("\\n", "")
                                .trim()
                    )
            )
        }

        val root = JSONObject()
            .put(
                "task",
                "Read all OCR items as one Japanese scene, repair only obvious OCR/segmentation errors using context and coordinates, then translate every id into fluent Korean. Keep speakers, relationships, speech level, names, tone and meaning consistent. Do not trust or invent text that is not supported by source_ja."
            )
            .put("items", items)

        val previous =
            previousPageContext.trim()

        if (previous.isNotEmpty()) {
            root.put(
                "previous_page_context",
                previous.takeLast(900)
            )
        }

        return root.toString()
    }

    private fun buildPreviousContext(
        blocks: List<OcrBlock>,
        reconstructed: Map<Int, String>,
        translations: Map<Int, String>,
        pageText: String
    ): String {
        val ordered = blocks.sortedBy {
            it.id
        }

        val current =
            StringBuilder()

        for (block in ordered.takeLast(10)) {
            val ko =
                translations[block.id]
                    ?.trim()
                    .orEmpty()

            if (ko.isEmpty()) {
                continue
            }

            if (current.isNotEmpty()) {
                current.append("\n")
            }

            current.append("JA: ")
                .append(
                    reconstructed[block.id]
                        ?.takeIf { it.isNotBlank() }
                        ?: block.original
                            .replace("\n", " ")
                            .trim()
                )
                .append("\nKO: ")
                .append(ko)
        }

        if (current.isEmpty()
            && pageText.isNotBlank()) {
            current.append("KO_PAGE: ")
                .append(
                    pageText.trim()
                )
        }

        val prior =
            previousPageContext
                .trim()
                .takeLast(1100)

        val combined =
            buildString {
                if (prior.isNotEmpty()) {
                    append(prior)
                    append("\n---\n")
                }
                append(current)
            }
                .trim()

        return combined.takeLast(1400)
    }

    private data class ParsedResult(
        val translations: Map<Int, String>,
        val pageText: String
    )

    private fun parseResponse(
        raw: String
    ): ParsedResult {
        var clean = raw.trim()
        clean = clean
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')

        if (start >= 0 && end > start) {
            val jsonText =
                clean.substring(
                    start,
                    end + 1
                )

            try {
                val root =
                    JSONObject(jsonText)
                val list =
                    root.optJSONArray(
                        "translations"
                    )
                val out =
                    LinkedHashMap<Int, String>()

                if (list != null) {
                    for (i in 0 until list.length()) {
                        val item =
                            list.optJSONObject(i)
                                ?: continue
                        val id =
                            item.optInt(
                                "id",
                                Int.MIN_VALUE
                            )
                        val text =
                            item.optString(
                                "text",
                                ""
                            )
                                .trim()

                        if (id != Int.MIN_VALUE
                            && text.isNotEmpty()
                            && !looksLikeMetaCommentary(
                                text
                            )
                        ) {
                            out[id] =
                                text
                        }
                    }
                }

                val pageText =
                    root
                        .optString(
                            "page_text",
                            ""
                        )
                        .trim()
                        .takeIf {
                            it.isNotEmpty()
                                    && !looksLikeMetaCommentary(
                                it
                            )
                        }
                        ?: ""

                return ParsedResult(
                    translations = out,
                    pageText = pageText
                )
            } catch (_: Throwable) {
                // Fall through to plain-text recovery below.
            }
        }

        // Small local models occasionally ignore the JSON wrapper even when the
        // translation itself is good. Keep that result as a compatibility page
        // translation rather than throwing the whole pass away.
        val fallback =
            clean
                .trim()
                .takeIf {
                    it.isNotEmpty()
                            && !looksLikeMetaCommentary(
                        it
                    )
                }
                ?: ""

        return ParsedResult(
            translations = emptyMap(),
            pageText = fallback
        )
    }

    private fun looksLikeMetaCommentary(
        text: String
    ): Boolean {
        val compact = text.replace("\n", " ").trim()

        val banned = listOf(
            "한국어로 자연스럽게 번역",
            "자연스럽게 번역됨",
            "모든 문장",
            "유지됩니다",
            "원문의 의미",
            "번역문은",
            "번역 결과",
            "다음과 같이 번역",
            "json",
            "translation"
        )

        return banned.any {
            compact.contains(
                it,
                ignoreCase = true
            )
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return

        requestSequence.incrementAndGet()

        activeConversation?.let { conversation ->
            runCatching {
                conversation.cancelProcess()
            }
        }

        scope.launch {
            inferenceMutex.withLock {
                activeConversation = null

                engineMutex.withLock {
                    val current = engine
                    engine = null
                    activeBackend = "none"

                    if (current != null) {
                        runCatching {
                            current.close()
                        }
                    }
                }
            }

            scope.cancel()
        }
    }

    companion object {
        private const val MODEL_DIR = "local_llm"
        private const val MODEL_NAME =
            "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/" +
                    MODEL_NAME + "?download=true"
        private const val OLD_4B_MODEL_NAME =
            "Qwen3.5-4B_mixed_int4.litertlm"
        private val LEGACY_MODEL_NAMES = arrayOf(
            OLD_4B_MODEL_NAME,
            "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
        )
        private const val MIN_MODEL_BYTES = 900_000_000L
        private const val OLD_4B_MIN_BYTES = 2_300_000_000L

        @JvmStatic
        fun modelFile(context: Context): File {
            val dir = File(
                context.applicationContext.filesDir,
                MODEL_DIR
            )
            if (!dir.exists()) dir.mkdirs()
            return File(dir, MODEL_NAME)
        }

        @JvmStatic
        fun isModelReady(context: Context): Boolean {
            val file = modelFile(context)
            return file.exists()
                    && file.length() >= MIN_MODEL_BYTES
        }

        @JvmStatic
        fun hasOld4BModel(context: Context): Boolean {
            val dir = File(
                context.applicationContext.filesDir,
                MODEL_DIR
            )
            val file = File(
                dir,
                OLD_4B_MODEL_NAME
            )
            return file.exists()
                    && file.length() >= OLD_4B_MIN_BYTES
        }

        @JvmStatic
        fun deleteModel(context: Context) {
            val file = modelFile(context)
            if (file.exists()) file.delete()
            val part = File(file.absolutePath + ".part")
            if (part.exists()) part.delete()

            LEGACY_MODEL_NAMES.forEach { name ->
                val legacy = File(file.parentFile, name)
                if (legacy.exists()) legacy.delete()
            }
        }

        @JvmStatic
        fun downloadModel(
            context: Context,
            callback: DownloadCallback
        ) {
            val appContext = context.applicationContext

            Thread({
                val target = modelFile(appContext)
                val part = File(target.absolutePath + ".part")
                var connection: HttpURLConnection? = null

                try {
                    if (isModelReady(appContext)) {
                        callback.onProgress(100)
                        callback.onSuccess()
                        return@Thread
                    }

                    var existing = if (part.exists()) part.length() else 0L

                    connection = URL(MODEL_URL)
                        .openConnection() as HttpURLConnection

                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.setRequestProperty(
                        "User-Agent",
                        "ViewNyang/Android"
                    )

                    if (existing > 0L) {
                        connection.setRequestProperty(
                            "Range",
                            "bytes=$existing-"
                        )
                    }

                    connection.connect()

                    val code = connection.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException(
                            "문맥 모델 다운로드 실패 (HTTP $code)"
                        )
                    }

                    val resumed = code == HttpURLConnection.HTTP_PARTIAL

                    if (existing > 0L && !resumed) {
                        part.delete()
                        existing = 0L
                    }

                    val responseLength = connection.contentLengthLong
                    val total =
                        if (responseLength > 0L) {
                            existing + responseLength
                        } else {
                            -1L
                        }

                    connection.inputStream.use { input ->
                        FileOutputStream(part, resumed && existing > 0L).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var downloaded = existing
                            var lastPercent =
                                if (total > 0L) {
                                    ((downloaded * 100L) / total)
                                        .toInt()
                                        .coerceIn(0, 99)
                                } else {
                                    -1
                                }

                            if (lastPercent >= 0) {
                                callback.onProgress(lastPercent)
                            }

                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break

                                output.write(buffer, 0, read)
                                downloaded += read

                                if (total > 0L) {
                                    val percent =
                                        ((downloaded * 100L) / total)
                                            .toInt()
                                            .coerceIn(0, 99)

                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        callback.onProgress(percent)
                                    }
                                }
                            }
                            output.fd.sync()
                        }
                    }

                    if (part.length() < MIN_MODEL_BYTES) {
                        throw IllegalStateException(
                            "다운로드한 문맥 모델 파일이 너무 작아요."
                        )
                    }

                    if (target.exists()) target.delete()

                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }

                    LEGACY_MODEL_NAMES.forEach { name ->
                        val legacy = File(
                            target.parentFile,
                            name
                        )
                        if (legacy.exists()) legacy.delete()
                    }

                    callback.onProgress(100)
                    callback.onSuccess()
                } catch (t: Throwable) {
                    // Keep the partial file so a multi-GB model download can resume.
                    callback.onError(
                        t.message?.takeIf { it.isNotBlank() }
                            ?: "문맥 모델 다운로드에 실패했어요."
                    )
                } finally {
                    connection?.disconnect()
                }
            }, "viewnyang-local-llm-download").start()
        }
    }
}
