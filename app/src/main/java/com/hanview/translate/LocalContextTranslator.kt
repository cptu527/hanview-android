package com.hanview.translate

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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
    @Volatile private var engine: Engine? = null
    private val closed = AtomicBoolean(false)
    private val requestSequence = AtomicInteger(0)
    @Volatile private var previousPageContext: String = ""
    @Volatile private var activeBackend: String = "none"

    fun isReady(): Boolean = isModelReady(appContext)

    fun cancelPending() {
        requestSequence.incrementAndGet()
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
            callback.onError("로컬 문맥 모델이 아직 설치되지 않았어요.")
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
            try {
                if (closed.get()) return@launch

                val activeEngine = ensureEngine()
                val prompt = buildPrompt(snapshot)

                val systemInstruction = Contents.of(
                    "/no_think\n" +
                            "너는 일본어 만화·게임 대사를 한국어로 현지화하는 전문 번역가다. " +
                            "입력 items는 OCR 결과이며 source_ja가 유일한 원문이다. OCR에는 글자 누락·오인식·세로쓰기 분절이 있을 수 있으므로 " +
                            "장면 전체와 인접 항목, box 좌표, previous_page_context를 함께 보고 문장을 복원한다. " +
                            "세로쓰기는 같은 영역 안에서 위에서 아래, 열은 오른쪽에서 왼쪽으로 읽되 서로 다른 말풍선·패널을 억지로 합치지 않는다. " +
                            "생략된 주어와 목적어, 화자 관계, 호칭, 존댓말/반말, 감정과 말투를 앞뒤 문맥에 맞춰 일관되게 유지한다. " +
                            "원문 의미와 수위, 욕설, 은어를 임의로 순화·추가·삭제하지 않는다. 일본어 어순을 베끼지 말고 실제 한국 만화 대사처럼 자연스럽게 쓴다. " +
                            "각 입력 id를 정확히 한 번씩 translations에 반환하고, 같은 번역을 여러 id에 반복하지 않는다. " +
                            "설명·해설·번역 노트·메타 발언은 금지한다. 출력은 JSON 하나만 반환한다. " +
                            "형식은 {\"page_text\":\"\",\"translations\":[{\"id\":0,\"text\":\"번역\"}]}. " +
                            "page_text는 호환용이므로 원칙적으로 빈 문자열로 두고 translations를 완성한다."
                )

                val config = ConversationConfig(
                    systemInstruction = systemInstruction
                )

                val responseText = activeEngine
                    .createConversation(config)
                    .use { conversation ->
                        conversation.sendMessage(
                            prompt,
                            maxOutputToken = 900
                        ).toString()
                    }

                val parsed = parseResponse(responseText)

                if (sequence != requestSequence.get()) {
                    return@launch
                }

                if (parsed.translations.isEmpty()
                    && parsed.pageText.isBlank()
                ) {
                    callback.onError("로컬 문맥 모델의 번역 결과를 읽지 못했어요.")
                } else {
                    previousPageContext =
                        buildPreviousContext(
                            snapshot,
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
                    return@launch
                }

                callback.onError(
                    t.message?.takeIf { it.isNotBlank() }
                        ?: "로컬 문맥 번역 중 오류가 발생했어요."
                )
            }
        }
    }

    private suspend fun ensureEngine(): Engine {
        engine?.let { return it }

        return engineMutex.withLock {
            engine?.let { return@withLock it }

            val file = modelFile(appContext)
            if (!file.exists() || file.length() < MIN_MODEL_BYTES) {
                throw IllegalStateException("로컬 문맥 모델 파일이 없어요.")
            }

            fun createEngine(
                backend: Backend,
                label: String
            ): Engine {
                val created = Engine(
                    EngineConfig(
                        modelPath = file.absolutePath,
                        backend = backend,
                        maxNumTokens = 3072,
                        cacheDir = appContext.cacheDir.absolutePath
                    )
                )

                created.initialize()
                activeBackend = label
                return created
            }

            val created =
                try {
                    createEngine(
                        Backend.GPU(),
                        "gpu"
                    )
                } catch (gpuError: Throwable) {
                    try {
                        createEngine(
                            Backend.CPU(),
                            "cpu"
                        )
                    } catch (cpuError: Throwable) {
                        throw IllegalStateException(
                            "로컬 문맥 모델 실행 실패 (GPU/CPU 모두 실패)",
                            cpuError
                        )
                    }
                }

            engine = created
            created
        }
    }

    private fun buildPrompt(
        blocks: List<OcrBlock>
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
                        block.original
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
                previous.takeLast(2200)
            )
        }

        return root.toString()
    }

    private fun buildPreviousContext(
        blocks: List<OcrBlock>,
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
                    block.original
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

        return combined.takeLast(2600)
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

        scope.launch {
            engineMutex.withLock {
                engine?.close()
                engine = null
            }
            scope.cancel()
        }
    }

    companion object {
        private const val MODEL_DIR = "local_llm"
        private const val MODEL_NAME =
            "Qwen3.5-4B_mixed_int4.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3.5-4B/resolve/main/" +
                    MODEL_NAME + "?download=true"
        private val LEGACY_MODEL_NAMES = arrayOf(
            "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
        )
        private const val MIN_MODEL_BYTES = 2_300_000_000L

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
