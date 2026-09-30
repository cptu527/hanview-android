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
        fun onSuccess(translations: Map<Int, String>)
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

    fun isReady(): Boolean = isModelReady(appContext)

    fun cancelPending() {
        requestSequence.incrementAndGet()
    }

    fun resetContext() {
        previousPageContext = ""
    }

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
                            "너는 일본어 세로쓰기 만화 전문 한국어 번역가다. " +
                            "입력 배열은 이미 만화의 실제 읽는 순서(오른쪽 열에서 왼쪽 열, 각 열은 위에서 아래)로 정렬되어 있다. " +
                            "절대로 순서를 재배열하지 말고 reading_order 순서 그대로 장면을 이해한다. " +
                            "각 item의 text는 서로 다른 세로열일 수 있으므로 임의로 두 item을 이어 붙여 새 문장을 만들지 않는다. " +
                            "다만 앞뒤 item의 의미는 문맥으로만 참고해 생략된 주어·대상·인물 관계·존댓말·반말·감정을 자연스럽게 복원한다. " +
                            "직역체를 피하고 한국 만화 대사처럼 짧고 자연스럽게 쓴다. " +
                            "원문에 없는 정보, 설명, 해설, 번역 노트, 요약은 절대 추가하지 않는다. " +
                            "특히 '한국어로 번역됨', '자연스럽게 번역', '문장은', '유지됩니다', '원문의 의미' 같은 메타 설명을 절대 출력하지 않는다. " +
                            "고유명사·숫자·의미는 보존하고, 원문의 질문은 질문으로, 호소는 호소로 유지한다. " +
                            "출력은 반드시 JSON 하나만 반환한다: " +
                            "{\"translations\":[{\"id\":0,\"text\":\"번역문\"}]}. " +
                            "모든 입력 id를 정확히 한 번씩 반환하고 text에는 번역문 외의 말을 넣지 않는다."
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

                if (parsed.isEmpty()) {
                    callback.onError("로컬 문맥 모델의 번역 결과를 읽지 못했어요.")
                } else {
                    previousPageContext =
                        buildPreviousContext(
                            snapshot,
                            parsed
                        )
                    callback.onSuccess(parsed)
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

            val created = Engine(
                EngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.CPU(),
                    maxNumTokens = 4096,
                    cacheDir = appContext.cacheDir.absolutePath
                )
            )

            created.initialize()
            engine = created
            created
        }
    }

    private fun buildPrompt(
        blocks: List<OcrBlock>
    ): String {
        val ordered = blocks.sortedWith { a, b ->
            if (a.verticalSource && b.verticalSource) {
                val column = b.bounds.right.compareTo(a.bounds.right)
                if (column != 0) return@sortedWith column
            }

            val top = a.bounds.top.compareTo(b.bounds.top)
            if (top != 0) top else a.bounds.left.compareTo(b.bounds.left)
        }

        val items = JSONArray()

        ordered.forEachIndexed { index, block ->
            val item = JSONObject()
                .put("id", block.id)
                .put("reading_order", index)
                .put("vertical", block.verticalSource)
                .put("source_ja", block.original)

            val draft = block.translated?.trim().orEmpty()
            if (draft.isNotEmpty()
                && draft != block.original.trim()
            ) {
                item.put("draft_ko", draft)
            }

            items.put(item)
        }

        val root = JSONObject()
            .put(
                "task",
                "Act as a professional Japanese-to-Korean manga localizer. " +
                        "For each item, source_ja is authoritative and draft_ko is only a rough machine draft. " +
                        "Correct any mistranslation in the draft, then rewrite it into fluent Korean dialogue/prose " +
                        "that sounds as if it was originally written in Korean. Preserve each id and reading order. " +
                        "Use nearby items and previous_page_context to resolve omitted subjects, who is speaking to whom, " +
                        "honorifics, emotional tone and consistent speech level. Keep the meaning faithful; do not summarize, " +
                        "explain, sanitize, merge unrelated items, or invent details."
            )
            .put("items", items)

        val previous = previousPageContext.trim()
        if (previous.isNotEmpty()) {
            root.put(
                "previous_page_context",
                previous
            )
        }

        return root.toString()
    }

    private fun buildPreviousContext(
        blocks: List<OcrBlock>,
        translations: Map<Int, String>
    ): String {
        val ordered = blocks.sortedWith { a, b ->
            if (a.verticalSource && b.verticalSource) {
                val column = b.bounds.right.compareTo(a.bounds.right)
                if (column != 0) return@sortedWith column
            }

            val top = a.bounds.top.compareTo(b.bounds.top)
            if (top != 0) top else a.bounds.left.compareTo(b.bounds.left)
        }

        val tail = ordered.takeLast(8)
        val out = StringBuilder()

        for (block in tail) {
            val ko = translations[block.id]?.trim().orEmpty()
            if (ko.isEmpty()) continue

            if (out.isNotEmpty()) out.append("\n")
            out.append("JA: ")
                .append(block.original.replace("\n", " ").trim())
                .append("\nKO: ")
                .append(ko)
                .append("\n")
        }

        val value = out.toString().trim()
        return if (value.length <= 2200) {
            value
        } else {
            value.takeLast(2200)
        }
    }

    private fun parseResponse(
        raw: String
    ): Map<Int, String> {
        var clean = raw.trim()
        clean = clean
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')

        if (start >= 0 && end > start) {
            clean = clean.substring(start, end + 1)
        }

        val root = JSONObject(clean)
        val list = root.optJSONArray("translations")
            ?: return emptyMap()

        val out = LinkedHashMap<Int, String>()

        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optInt("id", Int.MIN_VALUE)
            val text = item.optString("text", "").trim()

            if (id != Int.MIN_VALUE
                && text.isNotEmpty()
                && !looksLikeMetaCommentary(text)
            ) {
                out[id] = text
            }
        }

        return out
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
