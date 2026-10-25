package com.seongjin.interpreter

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object Http {
    val ws: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()
    val api: OkHttpClient = ws.newBuilder()
        .pingInterval(0, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
}

class ApiException(message: String) : Exception(message)

/* ---------- Gemini 실시간 통역 세션 (한 방향) ---------- */

class LiveSession(
    private val key: String,
    val target: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(s: LiveSession)
        fun onContent(s: LiveSession, content: JSONObject)
        fun onGoAway(s: LiveSession)
        fun onClosed(s: LiveSession, code: Int, reason: String)
    }

    @Volatile var ready = false; private set
    private var ws: WebSocket? = null
    private val finished = AtomicBoolean(false)
    @Volatile private var closedByUs = false

    fun open() {
        val url = URL + URLEncoder.encode(key, "UTF-8")
        ws = Http.ws.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setupJson(target)) }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null); finish(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(code, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                finish(1006, t.message ?: "network")
        })
    }

    private fun handle(raw: String) {
        val m = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when {
            m.has("setupComplete") -> { ready = true; listener.onReady(this) }
            m.has("goAway") -> listener.onGoAway(this)
            m.has("serverContent") -> listener.onContent(this, m.getJSONObject("serverContent"))
        }
    }

    private fun finish(code: Int, reason: String) {
        ready = false
        if (finished.compareAndSet(false, true) && !closedByUs) listener.onClosed(this, code, reason)
    }

    fun sendAudio(b64: String) {
        if (ready) ws?.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
    }

    fun close() {
        closedByUs = true; ready = false
        runCatching { ws?.close(1000, null) }
    }

    companion object {
        const val MODEL = "gemini-3.5-live-translate-preview"
        const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="

        /** 받아쓰기 항목은 setup 바로 아래, 통역 설정은 generationConfig 안 (구글 API 규격) */
        fun setupJson(target: String): String = JSONObject().put("setup", JSONObject()
            .put("model", "models/$MODEL")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("translationConfig", JSONObject().put("targetLanguageCode", target).put("echoTargetLanguage", false)))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        ).toString()

        fun closeMessage(code: Int, reason: String, langName: String): String = when {
            Regex("api key|API_KEY|permission|unauth", RegexOption.IGNORE_CASE).containsMatchIn(reason) -> "Gemini 키가 맞지 않습니다. 설정에서 다시 확인하세요."
            Regex("quota|exhaust|rate", RegexOption.IGNORE_CASE).containsMatchIn(reason) -> "Gemini 사용 한도에 닿았습니다. 잠시 후 다시 하거나 Google 결제를 연결하세요."
            Regex("not found|model", RegexOption.IGNORE_CASE).containsMatchIn(reason) -> "실시간 통역 모델을 쓸 수 없습니다. 앱을 최신 버전으로 바꿔 주세요."
            Regex("language", RegexOption.IGNORE_CASE).containsMatchIn(reason) -> "${langName}는 실시간 통역이 지원되지 않습니다."
            code == 1006 -> "인터넷 연결이 끊겼습니다. 신호를 확인하고 다시 시작해 주세요."
            else -> "연결이 끊겼습니다" + (if (reason.isNotBlank()) " (${reason.take(60)})" else "") + ". 다시 시작해 주세요."
        }
    }
}

/* ---------- OpenRouter: 글 통역 · 정확 모드 · 역번역 ---------- */

object OpenRouter {
    private const val BASE = "https://openrouter.ai/api/v1"
    private val FREE_PREF = listOf("^google/gemma-4-31b", "^google/gemma-4-26b", "^google/gemma", "^nvidia/nemotron-3-ultra",
        "^nvidia/nemotron-3(\\.5)?-super", "^meta-llama/llama-[34]", "^qwen/", "^deepseek/").map { Regex(it) }
    private val PAID_PREF = listOf("^google/gemini-[\\d.]+-flash(?!.*(image|tts|audio))", "^openai/gpt-[\\w.-]*mini(?!.*(audio|search))",
        "^anthropic/claude-haiku").map { Regex(it) }
    private val FALLBACK_FREE = listOf("google/gemma-4-31b-it:free", "google/gemma-4-26b-a4b-it:free", "openrouter/free")
    /** 빠르고 정확한 유료 모델 (충전 계정용). 앞 모델이 막히면 다음으로 */
    val FAST = listOf("google/gemini-3.8-flash", "google/gemini-3.7-flash", "google/gemini-3.5-flash")

    data class PaidModel(val id: String, val wonPer100: Long)

    @Volatile var freeModels: List<String> = emptyList(); private set
    @Volatile var paidModels: List<PaidModel> = emptyList(); private set

    suspend fun loadModels() = withContext(Dispatchers.IO) {
        runCatching {
            Http.api.newCall(Request.Builder().url("$BASE/models").build()).execute().use { r ->
                if (!r.isSuccessful) return@use
                val all = JSONObject(r.body!!.string()).getJSONArray("data")
                val text = (0 until all.length()).map { all.getJSONObject(it) }.filter { m ->
                    val out = m.optJSONObject("architecture")?.optJSONArray("output_modalities")
                    val isText = out == null || (0 until out.length()).any { out.getString(it) == "text" }
                    isText && !Regex("guard|safety|code|coder|embed", RegexOption.IGNORE_CASE).containsMatchIn(m.getString("id"))
                }
                val free = text.filter { it.getString("id").endsWith(":free") }.map { it.getString("id") }
                val picked = mutableListOf<String>()
                for (re in FREE_PREF) for (id in free) if (re.containsMatchIn(id) && id !in picked && picked.size < 6) picked += id
                if (text.any { it.getString("id") == "openrouter/free" }) picked += "openrouter/free"
                freeModels = picked
                paidModels = text.filter { !it.getString("id").endsWith(":free") && PAID_PREF.any { re -> re.containsMatchIn(it.getString("id")) } }
                    .sortedBy { m -> PAID_PREF.indexOfFirst { it.containsMatchIn(m.getString("id")) } }
                    .take(4)
                    .map { m ->
                        val pr = m.optJSONObject("pricing")
                        val won = ((pr?.optString("prompt")?.toDoubleOrNull() ?: 0.0) * 500 +
                            (pr?.optString("completion")?.toDoubleOrNull() ?: 0.0) * 150) * 100 * 1400
                        PaidModel(m.getString("id"), maxOf(1L, Math.round(won)))
                    }
            }
        }
    }

    fun chain(setting: String): List<String> = when (setting) {
        "fast" -> FAST
        "auto" -> freeModels.ifEmpty { FALLBACK_FREE }.take(3)
        else -> listOf(setting)
    }

    suspend fun chat(key: String, model: String, prompt: String): String = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw ApiException("OpenRouter 키가 없습니다. 설정에서 넣어 주세요.")
        val body = JSONObject()
            .put("models", JSONArray(chain(model)))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("max_tokens", 1200).put("temperature", 0.2)
            .put("provider", JSONObject().put("sort", "latency"))   // 가장 빨리 답하는 서버로
        val req = Request.Builder().url("$BASE/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/lovesofia/sj-interpreter")
            .header("X-Title", "Seongjin Interpreter")
            .post(body.toString().toRequestBody(Http.JSON_TYPE)).build()
        val (code, text) = try {
            Http.api.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: IOException) { throw ApiException("인터넷 연결을 확인하세요.") }
        val json = runCatching { JSONObject(text) }.getOrNull()
        val err = json?.optJSONObject("error")
        if (code !in 200..299 || err != null) {
            val st = err?.optInt("code", code) ?: code
            val msg = err?.optString("message").orEmpty()
            throw ApiException(when {
                Regex("data policy|privacy", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
                    "OpenRouter 설정에서 무료 모델을 허용해야 합니다: openrouter.ai → Settings → Privacy"
                st == 401 -> "OpenRouter 키가 맞지 않습니다."
                st == 402 -> "충전 잔액이 부족합니다."
                st == 429 -> "요청이 몰렸습니다. 잠시 후 다시 하세요."
                st == 404 -> "선택한 모델이 지금 없습니다. 설정에서 ‘자동’으로 바꿔 주세요."
                else -> "번역 서버 오류 ($st) ${msg.take(60)}"
            })
        }
        val content = json!!.getJSONArray("choices").getJSONObject(0).getJSONObject("message").opt("content")
        val out = when (content) {
            is JSONArray -> (0 until content.length()).joinToString("") { content.getJSONObject(it).optString("text") }
            else -> content?.toString().orEmpty()
        }
        out.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
    }
}

/* ---------- Gemini 음성 생성 (정확 모드·읽어주기) ---------- */

object GeminiTts {
    private const val GAPI = "https://generativelanguage.googleapis.com/v1beta/"
    private const val NEW = "gemini-3.8-flash-tts"
    private const val OLD = "gemini-3.1-flash-tts-preview"

    /** 24kHz 16비트 PCM을 돌려준다 */
    suspend fun synth(key: String, text: String): ByteArray = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw ApiException("no key")
        val k = "?key=" + URLEncoder.encode(key, "UTF-8")
        val first = JSONObject().put("model", NEW)
            .put("input", JSONArray().put(JSONObject().put("type", "user_input")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", "Kore"))))
        var audio = post(GAPI + "interactions" + k, first)
        if (audio == null) {
            val second = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore")))))
            audio = post(GAPI + "models/$OLD:generateContent" + k, second)
        }
        audio ?: throw ApiException("tts failed")
        stripWav(Base64.decode(audio, Base64.DEFAULT))
    }

    private fun post(url: String, body: JSONObject): String? = runCatching {
        Http.api.newCall(Request.Builder().url(url).post(body.toString().toRequestBody(Http.JSON_TYPE)).build()).execute().use { r ->
            if (!r.isSuccessful) null else findAudio(JSONObject(r.body!!.string()))
        }
    }.getOrNull()

    private fun findAudio(x: Any?): String? {
        var hit: String? = null
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> {
                    if (v.optString("type") == "audio" && v.has("data")) hit = v.getString("data")
                    v.optJSONObject("inlineData")?.optString("data")?.takeIf { it.isNotEmpty() }?.let { hit = it }
                    v.keys().forEach { walk(v.opt(it)) }
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        walk(x)
        return hit
    }

    /** WAV 머리말이 있으면 떼고 소리 데이터만 돌려준다 */
    private fun stripWav(b: ByteArray): ByteArray {
        if (b.size < 12 || String(b, 0, 4) != "RIFF") return b
        var i = 12
        while (i + 8 <= b.size) {
            val id = String(b, i, 4)
            val len = (b[i + 4].toInt() and 0xff) or ((b[i + 5].toInt() and 0xff) shl 8) or
                ((b[i + 6].toInt() and 0xff) shl 16) or ((b[i + 7].toInt() and 0xff) shl 24)
            if (id == "data") return b.copyOfRange(i + 8, minOf(b.size, i + 8 + len))
            i += 8 + len
        }
        return b.copyOfRange(44.coerceAtMost(b.size), b.size)
    }
}

/* ---------- 번역 지시문 ---------- */

object Prompts {
    fun translate(text: String, w: Worker, terms: List<Term>, known: String?): String {
        val l = Langs.get(w.lang)
        val gloss = terms.filter { it.forLang(w.lang).isNotBlank() }.joinToString("\n") { "- ${it.ko} = ${it.forLang(w.lang)}" }.ifBlank { "(none)" }
        val src = if (known != null) "The message is in ${if (known == "ko") "Korean" else l.en} (already known; set \"source\" to \"$known\")."
            else "Detect whether it is Korean or ${l.en} — it is one of those two only."
        return """You are the on-site interpreter at a Korean pig farm. The farm owner speaks Korean. The worker (${w.name}) speaks ${l.en}.
$src
The text may come from speech recognition, so fix obvious recognition errors using farm context.

Rules:
1. Translate into the other language as short, plain spoken language for barn work. Owner-to-worker: clear, polite work instructions. Worker-to-owner: natural Korean.
2. Keep every number, pen or crate number, time, dose and count exactly.
3. When a concept below appears, use exactly the glossary term in the target language:
$gloss
4. If the owner spoke, back-translate your translation literally into Korean in "back_ko" so the owner can verify it. If the worker spoke, "back_ko" is "".
5. If something is ambiguous (unclear number, animal, or location), write a very short Korean warning in "check"; otherwise "".
6. "terms" lists the Korean glossary words you used.

Message:
""${'"'}$text""${'"'}

Reply with only JSON, no other text: {"source":"ko" or "${w.lang}","translation":"...","back_ko":"...","terms":[],"check":""}"""
    }

    fun back(text: String, langEn: String) =
        "Translate this $langEn sentence literally into Korean so a Korean farm owner can check what the worker heard. Reply with only the Korean sentence.\n\n$text"

    fun parseJson(t: String): JSONObject? {
        runCatching { return JSONObject(t) }
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a >= 0 && b > a) runCatching { return JSONObject(t.substring(a, b + 1)) }
        return null
    }
}
