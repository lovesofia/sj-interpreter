package com.seongjin.interpreter

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/** 화면에 보여줄 실시간 대화 상태 */
data class LiveUi(
    val on: Boolean = false,
    val ready: Boolean = false,
    val detecting: Boolean = false,
    val speaking: Boolean = false,
    val level: Float = 0f,
    val capShown: Boolean = false,
    val capOwner: Boolean = false,
    val capWho: String = "",
    val capText: String = "",
    val capSub: String = "",
    val diag: String = "",
    val note: String = "",
    val route: String = "",
    val nameGuestId: String? = null,
)

/**
 * 실시간 통역 엔진.
 * 마이크 소리를 통역 세션 2개에 동시에 보낸다.
 *   ko 세션: 직원 말 → 한국어 음성 (대표님 한국어에는 침묵)
 *   w  세션: 대표님 말 → 직원 언어 음성 (직원 말에는 침묵)
 * 통역 음성이 나오는 동안에는 무음을 보내 스피커 소리가 다시 통역되지 않게 한다.
 */
class Engine(private val ctx: Context, private val repo: Repo) {
    private val main = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private val _ui = MutableStateFlow(LiveUi())
    val ui: StateFlow<LiveUi> = _ui.asStateFlow()

    /** 지금 소리 내어 읽고 있는 대화 기록 (읽어주기 버튼 강조용) */
    private val _playingId = MutableStateFlow<Long?>(null)
    val playingId: StateFlow<Long?> = _playingId.asStateFlow()

    private var workerId: String = ""
    private val sessions = mutableMapOf<String, LiveSession?>()
    private val retries = mutableMapOf<String, Int>()
    private val turns = mutableMapOf<String, Turn?>()
    private val idle = mutableMapOf<String, Job?>()
    private var micJob: Job? = null
    private var diagJob: Job? = null
    private var mic: Mic? = null
    private val livePlayer = Player(voiceCall = true)
    private val mediaPlayer = Player(voiceCall = false)
    private val routing = Routing(ctx)

    @Volatile private var chunks = 0L
    @Volatile private var lastChunkAt = 0L
    @Volatile private var lastLevel = 0f

    private class Turn { val inp = StringBuilder(); val out = StringBuilder(); var lang = "" }

    private val worker: Worker? get() = repo.worker(workerId)
    private val settings get() = repo.settings.value

    /* ---------------- 시작 / 종료 ---------------- */

    fun start(guest: Boolean) = main.launch {
        if (_ui.value.on) return@launch
        if (settings.geminiKey.isBlank()) { note("설정에서 Gemini 키를 먼저 넣어 주세요."); return@launch }
        val w = if (guest) {
            Worker(repo.newWorkerId(), "처음 보는 사람", "auto", guest = true).also { repo.upsertWorker(it, front = true); repo.select(it.id) }
        } else repo.current()
        workerId = w.id
        retries.clear(); turns.clear()
        _ui.value = LiveUi(on = true, detecting = w.lang == "auto")

        ctx.startForegroundService(Intent(ctx, InterpreterService::class.java))
        val route = routing.begin()
        val m = Mic()
        if (!m.start()) {
            stopInternal()
            note("마이크를 열 수 없습니다. 전화·녹음 앱 등 다른 앱이 마이크를 쓰고 있으면 닫고 다시 누르세요.")
            return@launch
        }
        mic = m
        _ui.update { it.copy(route = route) }
        chunks = 0; lastChunkAt = SystemClock.elapsedRealtime()

        openSession("ko")
        if (w.lang != "auto") openSession("w")
        micJob = CoroutineScope(Dispatchers.IO).launch { micLoop(m) }
        diagJob = main.launch { while (isActive) { delay(1000); diagTick() } }
    }

    fun stop() = main.launch { stopInternal() }

    private fun stopInternal() {
        if (!_ui.value.on && mic == null) return
        sessions.keys.toList().forEach { finishTurn(it) }
        sessions.values.forEach { it?.close() }
        sessions.clear()
        idle.values.forEach { it?.cancel() }; idle.clear()
        micJob?.cancel(); micJob = null
        diagJob?.cancel(); diagJob = null
        mic?.stop(); mic = null
        val left = livePlayer.remainingMs()
        main.launch { delay(left + 300); if (!_ui.value.on) { livePlayer.release(); routing.end() } }
        ctx.stopService(Intent(ctx, InterpreterService::class.java))

        val g = worker
        var nameId: String? = null
        if (g != null && g.guest) {
            if (g.lang == "auto") repo.removeWorker(g.id) else nameId = g.id
        }
        _ui.value = LiveUi(note = _ui.value.note, nameGuestId = nameId)
    }

    fun clearGuestPrompt() = _ui.update { it.copy(nameGuestId = null) }

    /** 대화 중에 직원 언어를 바꾸면 그 방향 세션을 새로 연다 */
    fun languageChanged(id: String) = main.launch {
        if (_ui.value.on && id == workerId) {
            sessions["w"]?.close(); sessions.remove("w"); retries["w"] = 0
            openSession("w")
            _ui.update { it.copy(detecting = false, note = "${Langs.get(worker!!.lang).name}로 바꿨습니다.") }
        }
    }

    /* ---------------- 마이크 → 서버 ---------------- */

    private suspend fun micLoop(m: Mic) {
        val buf = ByteArray(3200) // 0.1초
        val silence = Base64.encodeToString(ByteArray(3200), Base64.NO_WRAP)
        var wasSpeaking = false
        while (currentCoroutineContext().isActive) {
            val n = m.read(buf)
            if (n <= 0) { delay(20); if (mic == null) break; continue }
            var sum = 0L
            var i = 0
            while (i + 1 < n) { val v = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8); sum += kotlin.math.abs(v.toShort().toInt()); i += 2 }
            lastLevel = sum.toFloat() / (n / 2) / 32768f
            chunks++; lastChunkAt = SystemClock.elapsedRealtime()
            val sp = livePlayer.speaking()
            val b64 = if (sp) silence else Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
            val targets = synchronized(sessions) { sessions.values.filterNotNull() }
            targets.forEach { it.sendAudio(b64) }
            if (sp != wasSpeaking || chunks % 2 == 0L) {
                wasSpeaking = sp
                _ui.update { it.copy(speaking = sp, level = if (sp) 0f else lastLevel) }
            }
        }
    }

    /* ---------------- 서버 세션 ---------------- */

    private fun openSession(role: String) {
        val w = worker ?: return
        val target = if (role == "ko") "ko" else w.lang
        val s = LiveSession(settings.geminiKey, target, object : LiveSession.Listener {
            override fun onReady(s: LiveSession) { main.launch { if (sessions[role] === s) { retries[role] = 0; refreshReady() } } }
            override fun onContent(s: LiveSession, content: JSONObject) { main.launch { if (sessions[role] === s) onContent(role, content) } }
            override fun onGoAway(s: LiveSession) { main.launch { if (sessions[role] === s) reopen(role) } }
            override fun onClosed(s: LiveSession, code: Int, reason: String) {
                main.launch {
                    if (sessions[role] !== s || !_ui.value.on) return@launch
                    finishTurn(role)
                    if (code == 1007 || code == 1008 || (retries[role] ?: 0) >= 4) {
                        val msg = LiveSession.closeMessage(code, reason, Langs.get(worker?.lang ?: "ko").name)
                        stopInternal(); note(msg)
                    } else reopen(role)
                }
            }
        })
        synchronized(sessions) { sessions[role] = s }
        s.open()
        refreshReady()
    }

    private fun reopen(role: String) {
        finishTurn(role)
        sessions[role]?.close()
        synchronized(sessions) { sessions[role] = null }
        val n = (retries[role] ?: 0) + 1
        retries[role] = n
        refreshReady()
        main.launch { delay(minOf(4000L, 400L shl n)); if (_ui.value.on) openSession(role) }
    }

    private fun refreshReady() {
        val w = worker
        val need = if (w?.lang == "auto") listOf("ko") else listOf("ko", "w")
        val ready = _ui.value.on && need.all { sessions[it]?.ready == true }
        _ui.update { it.copy(ready = ready, detecting = w?.lang == "auto", capShown = ready || it.capShown) }
    }

    private fun onContent(role: String, c: JSONObject) {
        val t = turns.getOrPut(role) { Turn() }!!
        c.optJSONObject("inputTranscription")?.let { it.optString("text").takeIf { s -> s.isNotEmpty() }?.let { s -> t.inp.append(s) }; it.optString("languageCode").takeIf { s -> s.isNotEmpty() }?.let { s -> t.lang = s } }
        c.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { t.out.append(it) }

        // 두 세션이 같은 말을 들으므로, 말한 사람 방향의 세션 자막만 보여준다
        val ownerSpoke = Langs.norm(t.lang) == "ko"
        if (t.lang.isEmpty() || (ownerSpoke && role == "w") || (!ownerSpoke && role == "ko")) showCap(t)

        val accurateOwner = role == "w" && settings.accurate
        if (!accurateOwner) {
            c.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")?.takeIf { it.isNotEmpty() }?.let {
                        livePlayer.enqueue(Base64.decode(it, Base64.DEFAULT))
                    }
                }
            }
        }
        idle[role]?.cancel()
        if (c.optBoolean("turnComplete") || c.optBoolean("generationComplete")) finishTurn(role)
        else idle[role] = main.launch { delay(1800); finishTurn(role) }
    }

    private fun showCap(t: Turn) {
        val owner = Langs.norm(t.lang) == "ko"
        _ui.update {
            it.copy(capShown = true, capOwner = owner, capWho = if (owner) "대표" else (worker?.name ?: "직원"),
                capText = t.inp.toString().trim().ifEmpty { "…" }, capSub = t.out.toString().trim().let { s -> if (s.isEmpty()) "" else "→ $s" })
        }
    }

    private fun finishTurn(role: String) {
        idle[role]?.cancel(); idle[role] = null
        val t = turns[role] ?: return
        turns[role] = null
        val w = worker ?: return
        val inp = t.inp.toString().trim()
        val out = t.out.toString().trim()

        if (role == "w" && settings.accurate && inp.isNotEmpty() && Langs.norm(t.lang.ifEmpty { "ko" }) == "ko" && w.lang != "auto") {
            accurateTurn(inp, w); return
        }
        if (out.isEmpty()) return
        if (w.lang == "auto" && role == "ko") {
            val code = Langs.norm(t.lang)
            if (code.isNotEmpty() && code != "ko") setDetected(code)
        }
        val now = worker ?: return
        val m = Msg(repo.nextId(), fromOwner = role == "w", orig = inp.ifEmpty { "(음성)" }, tr = out, lang = now.lang, voice = true)
        repo.addMsg(now.id, m)
        if (m.fromOwner && settings.orKey.isNotBlank()) backTranslate(now.id, m)
    }

    private fun setDetected(code: String) {
        val l = Langs.get(code)
        val w = worker ?: return
        repo.upsertWorker(w.copy(lang = l.code, name = "새 상대 (${l.name})"))
        note("${l.name}(${l.native})로 들립니다. 이제 대표님 말도 ${l.name}로 통역합니다. 언어가 다르면 맨 앞 귀표를 길게 눌러 고치세요.")
        if (_ui.value.on) openSession("w")
        refreshReady()
    }

    private fun backTranslate(wid: String, m: Msg) = main.launch {
        val back = runCatching { OpenRouter.chat(settings.orKey, settings.orModel, Prompts.back(m.tr, Langs.get(m.lang).en)) }.getOrNull()
        if (!back.isNullOrBlank()) repo.updateMsg(wid, m.id) { it.copy(back = back.trim().trim('"', '“', '”')) }
    }

    /* ---------------- 정확 모드 ---------------- */

    private fun accurateTurn(text: String, w: Worker) = main.launch {
        if (settings.orKey.isBlank()) { note("정확 모드는 OpenRouter 키가 있어야 합니다. 설정에서 넣어 주세요."); return@launch }
        val m = Msg(repo.nextId(), true, text, "", w.lang, voice = true, accurate = true, pending = true)
        repo.addMsg(w.id, m)
        _ui.update { it.copy(capShown = true, capOwner = true, capWho = "대표", capText = text, capSub = "정확 통역 중…") }
        try {
            val r = Prompts.parseJson(OpenRouter.chat(settings.orKey, settings.orModel, Prompts.translate(text, w, repo.terms.value, "ko")))
                ?: throw ApiException("번역 결과를 읽지 못했습니다. 다시 말씀해 주세요.")
            val tr = r.optString("translation"); if (tr.isBlank()) throw ApiException("번역 결과가 비어 있습니다.")
            val terms = r.optJSONArray("terms")?.let { a -> (0 until minOf(6, a.length())).map { a.getString(it) } }.orEmpty()
            repo.updateMsg(w.id, m.id) { it.copy(tr = tr, back = r.optString("back_ko"), terms = terms, check = r.optString("check"), pending = false) }
            _ui.update { it.copy(capSub = "→ $tr") }
            val ok = speak(tr, w.lang, m.id, live = true)
            if (!ok) note("이 언어는 음성으로 읽을 수 없어 화면 글자로만 보여 줍니다. 직원에게 화면을 보여 주세요.")
        } catch (e: Exception) {
            repo.updateMsg(w.id, m.id) { it.copy(pending = false, error = e.message ?: "정확 통역 실패") }
        }
    }

    /* ---------------- 글로 통역 ---------------- */

    fun translateTyped(text: String) = main.launch {
        val w = repo.current()
        if (w.lang == "auto") { note("상대 언어를 먼저 감지해야 글로 통역할 수 있습니다."); return@launch }
        if (settings.orKey.isBlank()) { note("글로 통역하려면 설정에서 OpenRouter 키를 넣어 주세요."); return@launch }
        val m = Msg(repo.nextId(), true, text, "", w.lang, pending = true)
        repo.addMsg(w.id, m)
        try {
            val r = Prompts.parseJson(OpenRouter.chat(settings.orKey, settings.orModel, Prompts.translate(text, w, repo.terms.value, null)))
                ?: throw ApiException("번역 결과를 읽지 못했습니다. 다시 해 주세요.")
            val owner = r.optString("source", "ko") == "ko"
            val tr = r.optString("translation"); if (tr.isBlank()) throw ApiException("번역 결과가 비어 있습니다.")
            val terms = r.optJSONArray("terms")?.let { a -> (0 until minOf(6, a.length())).map { a.getString(it) } }.orEmpty()
            repo.updateMsg(w.id, m.id) { it.copy(fromOwner = owner, tr = tr, back = if (owner) r.optString("back_ko") else "", terms = terms, check = r.optString("check"), pending = false) }
            if (settings.autoSpeak) speak(tr, if (owner) w.lang else "ko", m.id, live = _ui.value.on)
        } catch (e: Exception) {
            repo.updateMsg(w.id, m.id) { it.copy(pending = false, error = e.message ?: "통역 실패") }
        }
    }

    /* ---------------- 소리 내어 읽기 ---------------- */

    /** 대화 기록의 '읽어주기' 버튼 */
    fun readAloud(m: Msg) = main.launch {
        if (_playingId.value == m.id) return@launch
        speak(m.tr, if (m.fromOwner) m.lang else "ko", m.id, live = _ui.value.on)
    }

    /** Gemini 음성 → 안 되면 휴대폰 음성. 재생 중인 기록 id를 표시했다가 끝나면 지운다 */
    private suspend fun speak(text: String, lang: String, msgId: Long, live: Boolean): Boolean {
        _playingId.value = msgId
        val pcm = if (settings.geminiKey.isNotBlank()) runCatching { GeminiTts.synth(settings.geminiKey, text) }.getOrNull() else null
        if (pcm != null && pcm.isNotEmpty()) {
            val p = if (live) livePlayer else mediaPlayer
            p.enqueue(pcm)
            main.launch { delay(p.remainingMs() + 150); if (_playingId.value == msgId) _playingId.value = null }
            return true
        }
        val ok = phoneSpeak(text, lang, msgId)
        if (!ok) _playingId.value = null
        return ok
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private suspend fun phoneSpeak(text: String, lang: String, msgId: Long): Boolean = withContext(Dispatchers.Main) {
        if (tts == null) {
            var done = false
            tts = TextToSpeech(ctx) { st -> ttsReady = st == TextToSpeech.SUCCESS; done = true }
            var waited = 0
            while (!done && waited < 3000) { delay(50); waited += 50 }
        }
        val t = tts
        if (!ttsReady || t == null) return@withContext false
        val r = t.setLanguage(Locale.forLanguageTag(lang))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return@withContext false
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "m$msgId")
        main.launch {
            delay(400)
            while (t.isSpeaking) delay(150)
            if (_playingId.value == msgId) _playingId.value = null
        }
        true
    }

    /* ---------------- 상태 점검 ---------------- */

    private fun diagTick() {
        if (!_ui.value.on) return
        val w = worker
        val micOk = SystemClock.elapsedRealtime() - lastChunkAt < 1500
        fun st(r: String) = when { sessions[r]?.ready == true -> "✓"; sessions.containsKey(r) -> "…"; else -> "—" }
        val parts = mutableListOf("마이크 " + if (!micOk) "✕ 멈춤" else if (lastLevel > 0.004f) "● 소리 들어옴" else "○ 조용함")
        parts += "한국어 ${st("ko")}"
        if (w != null && w.lang != "auto") parts += "${Langs.get(w.lang).name} ${st("w")}"
        if (_ui.value.route.isNotEmpty()) parts += _ui.value.route
        _ui.update { it.copy(diag = parts.joinToString(" · ")) }
        if (!micOk) note("마이크 소리가 들어오지 않습니다. 다른 앱이 마이크를 쓰고 있지 않은지 확인하고, 대화를 껐다가 다시 시작하세요.")
        else if (_ui.value.note.startsWith("마이크 소리가")) note("")
    }

    private fun note(t: String) = _ui.update { it.copy(note = t) }
    fun clearNote() = note("")
}
