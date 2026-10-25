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

/** 누가 말하는 중인지 */
enum class Talker { NONE, OWNER, STAFF }

/** 화면에 보여줄 대화 상태 */
data class LiveUi(
    val on: Boolean = false,
    val ready: Boolean = false,
    val detecting: Boolean = false,
    val recording: Talker = Talker.NONE,
    val toggled: Boolean = false,
    val translating: Talker = Talker.NONE,
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
 * 누르고 말하기 통역 엔진.
 * - '대화 시작'에서 마이크와 통역 세션 2개를 미리 열어 둔다 (누를 때 연결을 기다리지 않음)
 *     w  세션: 대표님 말 → 직원 언어 음성
 *     ko 세션: 직원 말 → 한국어 음성
 * - 버튼을 누르는 순간부터 (+누르기 직전 0.3초) 그 사람 쪽 세션으로만 소리를 바로 보낸다
 * - 손을 뗀 뒤 0.5초 더 듣고, 무음 0.8초를 붙여 서버가 말 끝을 확실히 알게 한다
 * - 세션이 아직 준비 중이면 소리를 버리지 않고 모아 두었다가 준비되면 한꺼번에 보낸다
 */
class Engine(private val ctx: Context, private val repo: Repo) {
    private val main = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private val _ui = MutableStateFlow(LiveUi())
    val ui: StateFlow<LiveUi> = _ui.asStateFlow()

    private val _playingId = MutableStateFlow<Long?>(null)
    val playingId: StateFlow<Long?> = _playingId.asStateFlow()

    private var workerId: String = ""
    private val sessions = mutableMapOf<String, LiveSession?>()
    private val pending = mutableMapOf<String, ArrayDeque<String>>()
    private val retries = mutableMapOf<String, Int>()
    private val turns = mutableMapOf<String, Turn?>()
    private val idle = mutableMapOf<String, Job?>()
    private var micJob: Job? = null
    private var diagJob: Job? = null
    private var mic: Mic? = null
    private val livePlayer = Player(voiceCall = true)
    private val mediaPlayer = Player(voiceCall = false)
    private val routing = Routing(ctx)

    // 녹음 상태 (마이크 스레드와 공유)
    private val lock = Any()
    @Volatile private var recRole: String? = null     // "w"=대표, "ko"=직원
    @Volatile private var tailChunks = -1              // 손 뗀 뒤 남은 0.1초 조각 수 (-1 = 누르고 있는 중)
    private val preRoll = ArrayDeque<String>()         // 누르기 직전 0.3초
    private var toggled = false
    private var tapStops = false

    @Volatile private var lastChunkAt = 0L
    @Volatile private var lastLevel = 0f
    private val silence = Base64.encodeToString(ByteArray(3200), Base64.NO_WRAP)

    private class Turn { val inp = StringBuilder(); val out = StringBuilder(); var lang = "" }

    private val worker: Worker? get() = repo.worker(workerId)
    private val settings get() = repo.settings.value

    /* ---------------- 대화 시작 / 끝 ---------------- */

    fun start(guest: Boolean) = main.launch {
        if (_ui.value.on) return@launch
        if (settings.geminiKey.isBlank()) { note("설정에서 Gemini 키를 먼저 넣어 주세요."); return@launch }
        val w = if (guest) {
            Worker(repo.newWorkerId(), "처음 보는 사람", "auto", guest = true).also { repo.upsertWorker(it, front = true); repo.select(it.id) }
        } else repo.current()
        workerId = w.id
        retries.clear(); turns.clear(); pending.clear()
        synchronized(lock) { recRole = null; tailChunks = -1; preRoll.clear() }
        toggled = false; tapStops = false
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
        lastChunkAt = SystemClock.elapsedRealtime()

        openSession("ko")
        if (w.lang != "auto") openSession("w")
        micJob = CoroutineScope(Dispatchers.IO).launch { micLoop(m) }
        diagJob = main.launch { while (isActive) { delay(1000); diagTick() } }
    }

    fun stop() = main.launch { stopInternal() }

    private fun stopInternal() {
        if (!_ui.value.on && mic == null) return
        synchronized(lock) { recRole = null; tailChunks = -1 }
        sessions.keys.toList().forEach { finishTurn(it) }
        sessions.values.forEach { it?.close() }
        sessions.clear(); pending.clear()
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

    fun languageChanged(id: String) = main.launch {
        if (_ui.value.on && id == workerId) {
            sessions["w"]?.close(); sessions.remove("w"); retries["w"] = 0; pending.remove("w")
            openSession("w")
            _ui.update { it.copy(detecting = false, note = "${Langs.get(worker!!.lang).name}로 바꿨습니다.") }
        }
    }

    /* ---------------- 누르고 말하기 ---------------- */

    /** 버튼을 누른 순간 */
    fun pressDown(owner: Boolean) = main.launch {
        if (!_ui.value.on) return@launch
        val role = if (owner) "w" else "ko"
        if (owner && worker?.lang == "auto") { note("상대가 먼저 말해야 언어를 알 수 있습니다. 직원 버튼을 눌러 상대가 말하게 하세요."); return@launch }
        // 한 번 눌러 켜 둔 상태에서 같은 버튼을 다시 누르면 = 끝
        if (toggled && recRole == role && tailChunks == -1) { toggled = false; tapStops = true; endRecording(); return@launch }
        if (recRole != null && recRole != role) flushTail()
        livePlayer.stopNow()             // 통역 음성이 나오던 중이면 바로 끊고 새 말을 듣는다
        toggled = false; tapStops = false
        idle[role]?.cancel()
        synchronized(lock) {
            recRole = role; tailChunks = -1
            preRoll.forEach { send(role, it) }   // 누르기 직전 0.3초
        }
        _ui.update { it.copy(recording = if (owner) Talker.OWNER else Talker.STAFF, toggled = false, capShown = true,
            capOwner = owner, capWho = if (owner) "대표" else (worker?.name ?: "직원"), capText = "듣는 중…", capSub = "") }
    }

    /** 버튼에서 손을 뗀 순간. 짧게 톡 눌렀으면 켜 둔 채로 계속 듣는다 */
    fun pressUp(owner: Boolean, heldMs: Long) = main.launch {
        if (tapStops) { tapStops = false; return@launch }
        val role = if (owner) "w" else "ko"
        if (recRole != role) return@launch
        if (heldMs < 450) { toggled = true; _ui.update { it.copy(toggled = true) }; return@launch }
        endRecording()
    }

    private fun endRecording() {
        synchronized(lock) { if (recRole != null && tailChunks == -1) tailChunks = 5 }   // 0.5초 더 듣기
        val t = if (recRole == "w") Talker.OWNER else Talker.STAFF
        _ui.update { it.copy(recording = Talker.NONE, toggled = false, translating = t) }
    }

    /** 다른 사람 버튼을 바로 누른 경우: 앞사람 말을 즉시 마무리 */
    private fun flushTail() {
        synchronized(lock) {
            val r = recRole ?: return
            repeat(8) { send(r, silence) }
            recRole = null; tailChunks = -1
            scheduleFinish(r)
        }
    }

    /** lock 안에서 호출. 세션이 준비 전이면 모아 둔다 */
    private fun send(role: String, b64: String) {
        val s = sessions[role]
        if (s != null && s.ready) s.sendAudio(b64)
        else pending.getOrPut(role) { ArrayDeque() }.let { q -> q.addLast(b64); while (q.size > 600) q.removeFirst() }
    }

    private fun scheduleFinish(role: String) {
        main.launch {
            idle[role]?.cancel()
            idle[role] = main.launch { delay(3500); finishTurn(role) }
        }
    }

    /* ---------------- 마이크 ---------------- */

    private suspend fun micLoop(m: Mic) {
        val buf = ByteArray(3200) // 0.1초
        var n2 = 0
        while (currentCoroutineContext().isActive) {
            val n = m.read(buf)
            if (n <= 0) { delay(20); if (mic == null) break; continue }
            var sum = 0L; var i = 0
            while (i + 1 < n) { val v = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8); sum += kotlin.math.abs(v.toShort().toInt()); i += 2 }
            lastLevel = sum.toFloat() / (n / 2) / 32768f
            lastChunkAt = SystemClock.elapsedRealtime()
            val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
            var finishedRole: String? = null
            synchronized(lock) {
                preRoll.addLast(b64); while (preRoll.size > 3) preRoll.removeFirst()
                val r = recRole
                if (r != null) {
                    send(r, b64)
                    if (tailChunks > 0) {
                        tailChunks--
                        if (tailChunks == 0) {
                            repeat(8) { send(r, silence) }   // 말 끝 알림
                            recRole = null; tailChunks = -1; finishedRole = r
                        }
                    }
                }
            }
            finishedRole?.let { scheduleFinish(it) }
            if (++n2 % 2 == 0) _ui.update { it.copy(level = if (recRole != null) lastLevel else 0f) }
        }
    }

    /* ---------------- 서버 세션 ---------------- */

    private fun openSession(role: String) {
        val w = worker ?: return
        val target = if (role == "ko") "ko" else w.lang
        val s = LiveSession(settings.geminiKey, target, object : LiveSession.Listener {
            override fun onReady(s: LiveSession) {
                // 준비되면 모아 둔 소리를 한꺼번에 보낸다 (첫마디 보존)
                synchronized(lock) { pending.remove(role)?.forEach { s.sendAudio(it) } }
                main.launch { if (sessions[role] === s) { retries[role] = 0; refreshReady() } }
            }
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
        synchronized(lock) { sessions[role] = s }
        s.open()
        refreshReady()
    }

    private fun reopen(role: String) {
        sessions[role]?.close()
        synchronized(lock) { sessions[role] = null }
        val n = (retries[role] ?: 0) + 1
        retries[role] = n
        refreshReady()
        main.launch { delay(minOf(3000L, 300L shl n)); if (_ui.value.on) openSession(role) }
    }

    private fun refreshReady() {
        val w = worker
        val need = if (w?.lang == "auto") listOf("ko") else listOf("ko", "w")
        val ready = _ui.value.on && need.all { sessions[it]?.ready == true }
        _ui.update { it.copy(ready = ready, detecting = w?.lang == "auto") }
    }

    private fun onContent(role: String, c: JSONObject) {
        val t = turns.getOrPut(role) { Turn() }!!
        c.optJSONObject("inputTranscription")?.let { o ->
            o.optString("text").takeIf { it.isNotEmpty() }?.let { t.inp.append(it) }
            o.optString("languageCode").takeIf { it.isNotEmpty() }?.let { t.lang = it }
        }
        c.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { t.out.append(it) }
        showCap(role, t)

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
        // 아직 녹음 중이면 기다리고, 끝났으면 서버의 '끝' 신호나 잠깐의 정적 후 기록한다
        idle[role]?.cancel()
        val stillTalking = recRole == role
        if (!stillTalking && (c.optBoolean("turnComplete") || c.optBoolean("generationComplete"))) finishTurn(role)
        else if (!stillTalking) idle[role] = main.launch { delay(1500); finishTurn(role) }
    }

    private fun showCap(role: String, t: Turn) {
        val owner = role == "w"
        _ui.update {
            it.copy(capShown = true, capOwner = owner, capWho = if (owner) "대표" else (worker?.name ?: "직원"),
                capText = t.inp.toString().trim().ifEmpty { "듣는 중…" },
                capSub = t.out.toString().trim().let { s -> if (s.isEmpty()) "" else "→ $s" })
        }
    }

    private fun finishTurn(role: String) {
        idle[role]?.cancel(); idle[role] = null
        if (recRole == role) return
        val t = turns[role]
        turns[role] = null
        val talker = if (role == "w") Talker.OWNER else Talker.STAFF
        if (_ui.value.translating == talker) _ui.update { it.copy(translating = Talker.NONE) }
        t ?: return
        val w = worker ?: return
        val inp = t.inp.toString().trim()
        val out = t.out.toString().trim()

        if (role == "w" && settings.accurate && inp.isNotEmpty() && w.lang != "auto") { accurateTurn(inp, w); return }
        if (out.isEmpty()) {
            if (inp.isNotEmpty()) note("통역 결과가 비어 있습니다. 한 번 더 말씀해 주세요.")
            return
        }
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
        note("${l.name}(${l.native})로 들립니다. 이제 대표님 버튼도 쓸 수 있습니다. 언어가 다르면 맨 앞 귀표를 길게 눌러 고치세요.")
        if (_ui.value.on) openSession("w")
        refreshReady()
    }

    /** 정확 모드·역번역은 충전된 계정이면 빠른 유료 모델을 우선 쓴다 */
    private fun fastModel(): String = settings.orModel

    private fun backTranslate(wid: String, m: Msg) = main.launch {
        val back = runCatching { OpenRouter.chat(settings.orKey, fastModel(), Prompts.back(m.tr, Langs.get(m.lang).en)) }.getOrNull()
        if (!back.isNullOrBlank()) repo.updateMsg(wid, m.id) { it.copy(back = back.trim().trim('"', '“', '”')) }
    }

    /* ---------------- 정확 모드 ---------------- */

    private fun accurateTurn(text: String, w: Worker) = main.launch {
        if (settings.orKey.isBlank()) { note("정확 모드는 OpenRouter 키가 있어야 합니다. 설정에서 넣어 주세요."); return@launch }
        val m = Msg(repo.nextId(), true, text, "", w.lang, voice = true, accurate = true, pending = true)
        repo.addMsg(w.id, m)
        _ui.update { it.copy(capShown = true, capOwner = true, capWho = "대표", capText = text, capSub = "정확 통역 중…", translating = Talker.OWNER) }
        try {
            val r = Prompts.parseJson(OpenRouter.chat(settings.orKey, fastModel(), Prompts.translate(text, w, repo.terms.value, "ko")))
                ?: throw ApiException("번역 결과를 읽지 못했습니다. 다시 말씀해 주세요.")
            val tr = r.optString("translation"); if (tr.isBlank()) throw ApiException("번역 결과가 비어 있습니다.")
            val terms = r.optJSONArray("terms")?.let { a -> (0 until minOf(6, a.length())).map { a.getString(it) } }.orEmpty()
            repo.updateMsg(w.id, m.id) { it.copy(tr = tr, back = r.optString("back_ko"), terms = terms, check = r.optString("check"), pending = false) }
            _ui.update { it.copy(capSub = "→ $tr") }
            val ok = speak(tr, w.lang, m.id, live = true)
            if (!ok) note("이 언어는 음성으로 읽을 수 없어 화면 글자로만 보여 줍니다. 직원에게 화면을 보여 주세요.")
        } catch (e: Exception) {
            repo.updateMsg(w.id, m.id) { it.copy(pending = false, error = e.message ?: "정확 통역 실패") }
        } finally {
            _ui.update { it.copy(translating = Talker.NONE) }
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
            val r = Prompts.parseJson(OpenRouter.chat(settings.orKey, fastModel(), Prompts.translate(text, w, repo.terms.value, null)))
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

    fun readAloud(m: Msg) = main.launch {
        if (_playingId.value == m.id) return@launch
        speak(m.tr, if (m.fromOwner) m.lang else "ko", m.id, live = _ui.value.on)
    }

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
        val parts = mutableListOf("마이크 " + if (!micOk) "✕ 멈춤" else if (recRole == null) "대기" else if (lastLevel > 0.004f) "● 소리 들어옴" else "○ 조용함")
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
