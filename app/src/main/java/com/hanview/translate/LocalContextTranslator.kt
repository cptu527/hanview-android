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
                    "/no_think\n" +
                            "너는 일본 만화·소설을 한국어로 현지화하는 전문 번역가다. " +
                            "화면에 보이는 모든 OCR 문장을 하나의 장면으로 읽고 번역한다. " +
                            "일본어의 생략된 주어·목적어, 인물 관계, 높임말, 말투, 감정, 앞뒤 문장을 함께 고려하되 " +
                            "원문에 없는 설정이나 사실을 만들지 않는다. " +
                            "직역체를 피하고 실제 한국어 대사와 소설 문장처럼 자연스럽게 쓴다. " +
                            "특히 '나는/당신/그것/것이다' 같은 불필요한 대명사와 일본어식 문형 반복을 줄인다. " +
                            "존댓말과 반말은 장면 안에서 일관되게 유지한다. " +
                            "고유명사·숫자·의미는 보존하고 검열하거나 요약하지 않는다. " +
                            "세로쓰기 일본 만화는 같은 세로열 안에서 위에서 아래로, 열은 오른쪽에서 왼쪽 순서로 읽는다. " +
                            "OCR 오탈자는 문맥상 명백한 경우만 바로잡는다. " +
                            "설명이나 사고 과정은 절대 출력하지 말고, 번역문만 반환한다. " +
                            "출력은 반드시 다음 JSON 형식만 사용한다: " +
                            "{\"translations\":[{\"id\":0,\"text\":\"자연스러운 한국어 번역\"}]}. " +
                            "입력된 모든 id를 정확히 한 번씩 반환한다."
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
                    maxNumTokens = 2048,
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
            "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/" +
                    MODEL_NAME + "?download=true"
        private const val LEGACY_MODEL_NAME =
            "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
        private const val MIN_MODEL_BYTES = 850_000_000L

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

                    val legacy = File(
                        target.parentFile,
                        LEGACY_MODEL_NAME
                    )
                    if (legacy.exists()) legacy.delete()

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
