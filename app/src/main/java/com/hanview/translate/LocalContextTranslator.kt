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

    fun isReady(): Boolean = isModelReady(appContext)

    fun translate(
        blocks: List<OcrBlock>,
        callback: Callback
    ) {
        if (!isReady()) {
            callback.onError("로컬 문맥 모델이 아직 설치되지 않았어요.")
            return
        }

        val snapshot = blocks.map { block ->
            OcrBlock(
                block.id,
                block.original,
                block.bounds
            ).also {
                it.verticalSource = block.verticalSource
                it.sourceGlyphWidthPx = block.sourceGlyphWidthPx
                it.sourceTextSizePx = block.sourceTextSizePx
            }
        }

        scope.launch {
            try {
                if (closed.get()) return@launch

                val activeEngine = ensureEngine()
                val prompt = buildPrompt(snapshot)

                val systemInstruction = Contents.of(
                    "You are ViewNyang's professional Korean manga/localization translator. " +
                            "Translate the supplied visible-page OCR into fluent natural Korean. " +
                            "Use the entire page as context to resolve omitted Japanese subjects, pronouns, " +
                            "relationships, tone, honorifics and consistent speech level. " +
                            "For Japanese vertical manga, reading order is generally top-to-bottom within a column " +
                            "and right-to-left across columns. Repair obvious OCR spacing/noise only when clear. " +
                            "Do not summarize, explain, censor, moralize or invent facts. " +
                            "Keep names, numbers and meaning faithful. Avoid Japanese literal syntax and needless " +
                            "나는/당신/그것 repetition. Return ONLY JSON in exactly this shape: " +
                            "{\"translations\":[{\"id\":0,\"text\":\"...\"}]}. " +
                            "Return every input id exactly once."
                )

                val config = ConversationConfig(
                    systemInstruction = systemInstruction
                )

                val responseText = activeEngine
                    .createConversation(config)
                    .use { conversation ->
                        conversation.sendMessage(
                            prompt,
                            maxOutputToken = 700
                        ).toString()
                    }

                val parsed = parseResponse(responseText)
                if (parsed.isEmpty()) {
                    callback.onError("로컬 문맥 모델의 번역 결과를 읽지 못했어요.")
                } else {
                    callback.onSuccess(parsed)
                }
            } catch (t: Throwable) {
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
                    maxNumTokens = 1280,
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
            items.put(
                JSONObject()
                    .put("id", block.id)
                    .put("reading_order", index)
                    .put("vertical", block.verticalSource)
                    .put("text", block.original)
            )
        }

        return JSONObject()
            .put("task", "translate_visible_page_to_natural_korean")
            .put("items", items)
            .toString()
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

            if (id != Int.MIN_VALUE && text.isNotEmpty()) {
                out[id] = text
            }
        }

        return out
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
            "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/" +
                    MODEL_NAME + "?download=true"
        private const val MIN_MODEL_BYTES = 250_000_000L

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

                    if (part.exists()) part.delete()

                    connection = URL(MODEL_URL)
                        .openConnection() as HttpURLConnection

                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.setRequestProperty(
                        "User-Agent",
                        "ViewNyang/Android"
                    )
                    connection.connect()

                    val code = connection.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException(
                            "문맥 모델 다운로드 실패 (HTTP $code)"
                        )
                    }

                    val total = connection.contentLengthLong

                    connection.inputStream.use { input ->
                        FileOutputStream(part).use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var downloaded = 0L
                            var lastPercent = -1

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

                    callback.onProgress(100)
                    callback.onSuccess()
                } catch (t: Throwable) {
                    if (part.exists()) part.delete()
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
