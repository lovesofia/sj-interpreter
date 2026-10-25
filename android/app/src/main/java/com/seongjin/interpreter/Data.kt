package com.seongjin.interpreter

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/* ---------- 언어 ---------- */

data class Lang(val code: String, val name: String, val native: String, val en: String)

object Langs {
    private val table = linkedMapOf(
        "ko" to Lang("ko", "한국어", "한국어", "Korean"),
        "ne" to Lang("ne", "네팔어", "नेपाली", "Nepali (Devanagari script)"),
        "uz" to Lang("uz", "우즈베크어", "Oʻzbekcha", "Uzbek (Latin script)"),
        "ru" to Lang("ru", "러시아어", "Русский", "Russian"),
        "vi" to Lang("vi", "베트남어", "Tiếng Việt", "Vietnamese"),
        "km" to Lang("km", "캄보디아어", "ខ្មែរ", "Khmer"),
        "my" to Lang("my", "미얀마어", "မြန်မာ", "Burmese"),
        "th" to Lang("th", "태국어", "ไทย", "Thai"),
        "en" to Lang("en", "영어", "English", "English"),
    )
    val AUTO = Lang("auto", "언어 감지 중", "자동 감지", "")

    fun staffChoices(): List<Lang> = synchronized(table) { table.values.filter { it.code != "ko" } }

    fun norm(code: String?): String = code.orEmpty().lowercase().split('-', '_').first()

    /** 목록에 없는 언어가 감지되면 휴대폰 언어 이름으로 만들어 추가한다 (예: kk → 카자흐어) */
    fun get(code: String): Lang {
        if (code == "auto") return AUTO
        val c = norm(code)
        return synchronized(table) {
            table.getOrPut(c) {
                val loc = Locale.forLanguageTag(c)
                Lang(
                    c,
                    loc.getDisplayLanguage(Locale.KOREAN).ifBlank { c },
                    loc.getDisplayLanguage(loc).ifBlank { c },
                    loc.getDisplayLanguage(Locale.ENGLISH).ifBlank { c },
                )
            }
        }
    }
}

/* ---------- 데이터 ---------- */

data class Worker(
    val id: String,
    val name: String,
    val lang: String,
    val guest: Boolean = false,
    val example: Boolean = false,
)

data class Msg(
    val id: Long,
    val fromOwner: Boolean,
    val orig: String,
    val tr: String,
    val lang: String,
    val back: String = "",
    val terms: List<String> = emptyList(),
    val check: String = "",
    val voice: Boolean = false,
    val accurate: Boolean = false,
    val pending: Boolean = false,
    val error: String = "",
    val example: Boolean = false,
)

data class Term(val ko: String, val ne: String, val uz: String, val ru: String, val ok: Boolean = false) {
    fun forLang(code: String): String = when (code) { "ne" -> ne; "uz" -> uz; "ru" -> ru; else -> "" }
}

data class AppSettings(
    val geminiKey: String = "",
    val orKey: String = "",
    val orModel: String = "fast",
    val accurate: Boolean = false,
    val autoSpeak: Boolean = true,
)

/* ---------- 저장소: 휴대폰 안에만 저장 ---------- */

class Repo(ctx: Context) {
    private val p = ctx.getSharedPreferences("sj", Context.MODE_PRIVATE)
    private var seq = System.currentTimeMillis()

    val workers = MutableStateFlow(loadWorkers())
    val feeds = MutableStateFlow(loadFeeds())
    val terms = MutableStateFlow(loadTerms())
    val settings = MutableStateFlow(loadSettings())
    val currentId = MutableStateFlow(p.getString("current", null) ?: workers.value.first().id)

    fun nextId(): Long = synchronized(this) { ++seq }
    fun newWorkerId(): String = "w" + nextId()

    fun current(): Worker = workers.value.find { it.id == currentId.value } ?: workers.value.first()
    fun worker(id: String): Worker? = workers.value.find { it.id == id }

    fun select(id: String) {
        currentId.value = id
        p.edit().putString("current", id).apply()
    }

    fun upsertWorker(w: Worker, front: Boolean = false) {
        workers.update { list ->
            when {
                list.any { it.id == w.id } -> list.map { if (it.id == w.id) w else it }
                front -> listOf(w) + list
                else -> list + w
            }
        }
        saveWorkers()
    }

    fun removeWorker(id: String) {
        workers.update { list -> list.filterNot { it.id == id }.ifEmpty { listOf(Worker(newWorkerId(), "직원", "ne")) } }
        feeds.update { it - id }
        if (currentId.value == id) select(workers.value.first().id)
        saveWorkers(); saveFeeds()
    }

    fun addMsg(workerId: String, m: Msg) {
        feeds.update { f -> f + (workerId to (f[workerId].orEmpty().filterNot { it.example } + m)) }
        saveFeeds()
    }

    fun updateMsg(workerId: String, id: Long, fn: (Msg) -> Msg) {
        feeds.update { f -> f + (workerId to f[workerId].orEmpty().map { if (it.id == id) fn(it) else it }) }
        saveFeeds()
    }

    fun deleteMsg(workerId: String, id: Long) {
        feeds.update { f -> f + (workerId to f[workerId].orEmpty().filterNot { it.id == id }) }
        saveFeeds()
    }

    fun clearFeed(workerId: String) {
        feeds.update { it - workerId }
        saveFeeds()
    }

    fun setTerms(list: List<Term>) { terms.value = list; saveTerms() }
    fun setSettings(s: AppSettings) { settings.value = s; saveSettings() }

    /* --- JSON 저장/불러오기 --- */

    private fun loadWorkers(): List<Worker> {
        val raw = p.getString("workers", null)
        val list = if (raw == null) SAMPLE_WORKERS else runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Worker(o.getString("id"), o.getString("name"), o.getString("lang"), o.optBoolean("guest"), o.optBoolean("example"))
            }
        }.getOrDefault(SAMPLE_WORKERS)
        list.forEach { if (it.lang != "auto") Langs.get(it.lang) }
        return list.filterNot { it.guest && it.lang == "auto" }.ifEmpty { SAMPLE_WORKERS }
    }

    private fun saveWorkers() {
        val a = JSONArray()
        workers.value.forEach { w ->
            a.put(JSONObject().put("id", w.id).put("name", w.name).put("lang", w.lang).put("guest", w.guest).put("example", w.example))
        }
        p.edit().putString("workers", a.toString()).apply()
    }

    private fun loadFeeds(): Map<String, List<Msg>> {
        val raw = p.getString("feeds", null) ?: return SAMPLE_FEED
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { k ->
                val a = o.getJSONArray(k)
                (0 until a.length()).map { i -> msgFromJson(a.getJSONObject(i)) }
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveFeeds() {
        val o = JSONObject()
        feeds.value.forEach { (k, list) ->
            val a = JSONArray(); list.filterNot { it.pending }.forEach { a.put(msgToJson(it)) }; o.put(k, a)
        }
        p.edit().putString("feeds", o.toString()).apply()
    }

    private fun loadTerms(): List<Term> {
        val raw = p.getString("terms", null) ?: return SAMPLE_TERMS
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Term(o.getString("ko"), o.optString("ne"), o.optString("uz"), o.optString("ru"), o.optBoolean("ok"))
            }
        }.getOrDefault(SAMPLE_TERMS)
    }

    private fun saveTerms() {
        val a = JSONArray()
        terms.value.forEach { t -> a.put(JSONObject().put("ko", t.ko).put("ne", t.ne).put("uz", t.uz).put("ru", t.ru).put("ok", t.ok)) }
        p.edit().putString("terms", a.toString()).apply()
    }

    private fun loadSettings(): AppSettings {
        val raw = p.getString("settings", null) ?: return AppSettings()
        return runCatching {
            val o = JSONObject(raw)
            AppSettings(o.optString("geminiKey"), o.optString("orKey"), o.optString("orModel", "fast").let { if (it == "auto") "fast" else it }, o.optBoolean("accurate"), o.optBoolean("autoSpeak", true))
        }.getOrDefault(AppSettings())
    }

    private fun saveSettings() {
        val s = settings.value
        val o = JSONObject().put("geminiKey", s.geminiKey).put("orKey", s.orKey).put("orModel", s.orModel)
            .put("accurate", s.accurate).put("autoSpeak", s.autoSpeak)
        p.edit().putString("settings", o.toString()).apply()
    }

    private fun msgToJson(m: Msg) = JSONObject()
        .put("id", m.id).put("fromOwner", m.fromOwner).put("orig", m.orig).put("tr", m.tr).put("lang", m.lang)
        .put("back", m.back).put("terms", JSONArray(m.terms)).put("check", m.check).put("voice", m.voice)
        .put("accurate", m.accurate).put("error", m.error).put("example", m.example)

    private fun msgFromJson(o: JSONObject): Msg {
        val t = o.optJSONArray("terms")
        return Msg(
            o.getLong("id"), o.getBoolean("fromOwner"), o.optString("orig"), o.optString("tr"), o.optString("lang"),
            o.optString("back"), if (t == null) emptyList() else (0 until t.length()).map { t.getString(it) },
            o.optString("check"), o.optBoolean("voice"), o.optBoolean("accurate"), false, o.optString("error"), o.optBoolean("example"),
        )
    }

    companion object {
        val SAMPLE_WORKERS = listOf(
            Worker("w1", "라주", "ne", example = true),
            Worker("w2", "자수르", "uz", example = true),
            Worker("w3", "알렉세이", "ru", example = true),
        )
        val SAMPLE_TERMS = listOf(
            Term("분만틀", "फ्यारोइङ क्रेट", "tug‘ish kletkasi", "станок для опороса"),
            Term("모돈", "माउ सुँगुर", "ona cho‘chqa", "свиноматка"),
            Term("자돈", "सुँगुरको बच्चा", "cho‘chqa bolasi", "поросёнок"),
            Term("이유", "दूध छुटाउने", "sutdan ajratish", "отъём"),
            Term("보온등", "तताउने बत्ती", "isitish chirog‘i", "обогревательная лампа"),
            Term("사료", "दाना", "yem", "корм"),
            Term("백신", "खोप", "vaksina", "вакцина"),
            Term("분만", "ब्याउनु", "tug‘ish", "опорос"),
        )
        val SAMPLE_FEED = mapOf(
            "w1" to listOf(
                Msg(1, true, "3번 분만틀 모돈 분만 시작했어. 30분마다 확인해.",
                    "३ नम्बर फ्यारोइङ क्रेटको माउ सुँगुरले ब्याउन सुरु गरेको छ। हरेक ३० मिनेटमा जाँच गर्नुहोस्।", "ne",
                    back = "3번 분만틀의 모돈이 분만을 시작했습니다. 30분마다 확인하세요.", terms = listOf("분만틀", "모돈", "분만"), example = true),
                Msg(2, false, "दुईवटा सुँगुरका बच्चा कमजोर छन्, दूध चुस्न सकेनन्।", "새끼 두 마리가 약해서 젖을 못 빨았습니다.", "ne",
                    terms = listOf("자돈"), example = true),
            ),
        )
    }
}
