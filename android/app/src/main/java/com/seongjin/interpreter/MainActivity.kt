package com.seongjin.interpreter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/* ---------------- 색 (웹앱과 같은 귀표 디자인) ---------------- */

data class Palette(
    val bg: Color, val surface: Color, val surface2: Color, val ink: Color, val muted: Color, val line: Color,
    val tag: Color, val tagInk: Color, val tagEdge: Color, val staff: Color, val staffInk: Color, val staffMuted: Color,
    val ok: Color, val warn: Color,
)

private val Light = Palette(Color(0xFFE7EBE4), Color(0xFFF7F8F4), Color(0xFFEEF1EA), Color(0xFF16221D), Color(0xFF5A675F), Color(0xFFC9D0C5),
    Color(0xFFF2C230), Color(0xFF2B2200), Color(0xFFC99A10), Color(0xFF2C5B4E), Color(0xFFF1F6F2), Color(0xFFB9D0C6), Color(0xFF2D7A4C), Color(0xFFA85A16))
private val Dark = Palette(Color(0xFF111714), Color(0xFF1A231F), Color(0xFF202B26), Color(0xFFE5ECE6), Color(0xFF93A198), Color(0xFF2E3A34),
    Color(0xFFF2C230), Color(0xFF2B2200), Color(0xFFB88A0A), Color(0xFF2F6355), Color(0xFFEEF5F0), Color(0xFFA9C6BA), Color(0xFF5DBB82), Color(0xFFE39A55))

val LocalP = staticCompositionLocalOf { Light }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as App
        setContent {
            val p = if (isSystemInDarkTheme()) Dark else Light
            CompositionLocalProvider(LocalP provides p) {
                MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = p.staff) else lightColorScheme(primary = p.staff)) {
                    Surface(color = p.bg, contentColor = p.ink, modifier = Modifier.fillMaxSize()) {
                        AppScreen(app)
                    }
                }
            }
        }
    }
}

/* ---------------- 메인 화면 ---------------- */

@Composable
fun AppScreen(app: App) {
    val repo = app.repo
    val engine = app.engine
    val p = LocalP.current
    val ctx = LocalContext.current
    val workers by repo.workers.collectAsStateWithLifecycle()
    val currentId by repo.currentId.collectAsStateWithLifecycle()
    val feeds by repo.feeds.collectAsStateWithLifecycle()
    val terms by repo.terms.collectAsStateWithLifecycle()
    val settings by repo.settings.collectAsStateWithLifecycle()
    val live by engine.ui.collectAsStateWithLifecycle()
    val playingId by engine.playingId.collectAsStateWithLifecycle()
    val current = workers.find { it.id == currentId } ?: workers.first()

    var tab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var editWorker by remember { mutableStateOf<Worker?>(null) }
    var addingWorker by remember { mutableStateOf(false) }
    var pendingGuest by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) { OpenRouter.loadModels() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val g = pendingGuest; pendingGuest = null
        if (res[Manifest.permission.RECORD_AUDIO] == true && g != null) engine.start(g)
    }
    fun startLive(guest: Boolean) {
        if (settings.geminiKey.isBlank()) { showSettings = true; return }
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) engine.start(guest) else {
            pendingGuest = guest
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            permLauncher.launch(perms.toTypedArray())
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 머리글
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("성진 현장통역", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = p.ink)
                Text("SEONGJIN FARM INTERPRETER", fontSize = 11.sp, letterSpacing = 1.5.sp, color = p.muted)
            }
            val ready = settings.geminiKey.isNotBlank()
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (ready) p.ok else p.warn))
            Spacer(Modifier.width(6.dp))
            Text(if (ready) "준비됨" else "설정 필요", fontSize = 12.sp, color = p.muted)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, p.line, RoundedCornerShape(12.dp))
                .background(p.surface).clickable { showSettings = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Settings, contentDescription = "설정", tint = p.ink)
            }
        }

        // 귀표: 대화 상대
        Text("대화 상대 · 귀표를 누르세요 · 길게 누르면 수정", fontSize = 11.sp, color = p.muted, letterSpacing = 0.5.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            item {
                EarTag("처음 보는 사람", "언어 자동 감지", selected = false, dashed = true, accent = p.staff,
                    onClick = { if (live.on) engine.stop(); startLive(true) }, onLong = {})
            }
            items(workers, key = { it.id }) { w ->
                EarTag(w.name, Langs.get(w.lang).name + if (w.example) " · 예시" else "", selected = w.id == current.id,
                    onClick = { if (w.id == current.id) editWorker = w else { if (live.on) engine.stop(); repo.select(w.id) } },
                    onLong = { editWorker = w })
            }
            item { EarTag("+ 직원", "", selected = false, dashed = true, accent = p.muted, onClick = { addingWorker = true }, onLong = {}) }
        }

        // 탭
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf("대화", "축산 용어집").forEachIndexed { i, t ->
                Column(Modifier.clickable { tab = i }.padding(vertical = 6.dp)) {
                    Text(t, fontWeight = FontWeight.SemiBold, color = if (tab == i) p.ink else p.muted, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(if (tab == i) 32.dp else 0.dp).height(3.dp).background(p.ink))
                }
            }
        }
        HorizontalDivider(color = p.line)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) Feed(feeds[current.id].orEmpty(), current, playingId,
                onRead = { engine.readAloud(it) }, onDelete = { repo.deleteMsg(current.id, it.id) })
            else Glossary(terms, onChange = { repo.setTerms(it) })
        }

        if (tab == 0) Dock(live, settings, current,
            onToggleLive = { if (live.on) engine.stop() else startLive(false) },
            onPressDown = { engine.pressDown(it) },
            onPressUp = { owner, ms -> engine.pressUp(owner, ms) },
            onAccurate = { repo.setSettings(settings.copy(accurate = it)) },
            onSend = { engine.translateTyped(it) },
            onDismissNote = { engine.clearNote() })
        Spacer(Modifier.height(4.dp))
    }

    if (showSettings) SettingsDialog(settings, onSave = { repo.setSettings(it); showSettings = false },
        onClose = { showSettings = false }, onClearFeed = { repo.clearFeed(current.id); showSettings = false })

    val guestToName = live.nameGuestId?.let { id -> workers.find { it.id == id } }
    when {
        guestToName != null -> WorkerDialog(guestToName.copy(name = ""), title = "새 상대 이름 저장", canDelete = true,
            onSave = { n, l -> repo.upsertWorker(guestToName.copy(name = n.ifBlank { guestToName.name }, lang = l, guest = false)); engine.clearGuestPrompt() },
            onDelete = { repo.removeWorker(guestToName.id); engine.clearGuestPrompt() },
            onClose = { repo.upsertWorker(guestToName.copy(guest = false)); engine.clearGuestPrompt() })
        editWorker != null -> {
            val w = editWorker!!
            WorkerDialog(w, title = "직원 수정", canDelete = true,
                onSave = { n, l ->
                    repo.upsertWorker(w.copy(name = n, lang = l, example = false, guest = false)); editWorker = null
                    if (l != w.lang) engine.languageChanged(w.id)
                },
                onDelete = { repo.removeWorker(w.id); editWorker = null }, onClose = { editWorker = null })
        }
        addingWorker -> WorkerDialog(null, title = "직원 추가", canDelete = false,
            onSave = { n, l -> val w = Worker(repo.newWorkerId(), n, l); repo.upsertWorker(w); repo.select(w.id); addingWorker = false },
            onDelete = {}, onClose = { addingWorker = false })
    }
}

/* ---------------- 귀표 ---------------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EarTag(title: String, sub: String, selected: Boolean, dashed: Boolean = false, accent: Color? = null,
           onClick: () -> Unit, onLong: () -> Unit) {
    val p = LocalP.current
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
    val bg = if (selected) p.tag else p.surface
    val edge = when { selected -> p.tagEdge; dashed -> accent ?: p.line; else -> p.line }
    val fg = when { selected -> p.tagInk; dashed && accent != null -> accent; else -> p.ink }
    Box(
        Modifier.widthIn(min = 92.dp).clip(shape).background(bg)
            .then(if (dashed) Modifier.drawBehind {
                drawRoundRect(edge, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
                    cornerRadius = CornerRadius(16.dp.toPx()))
            } else Modifier.border(2.dp, edge, shape))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 9.dp),
    ) {
        if (!dashed) Box(Modifier.align(Alignment.TopCenter).offset(y = (-10).dp).size(9.dp).clip(CircleShape).background(p.bg).border(2.dp, edge, CircleShape))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = fg)
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = fg.copy(alpha = 0.72f))
        }
    }
}

/* ---------------- 대화 기록 ---------------- */

@Composable
fun Feed(list: List<Msg>, w: Worker, playingId: Long?, onRead: (Msg) -> Unit, onDelete: (Msg) -> Unit) {
    val p = LocalP.current
    val state = rememberLazyListState()
    LaunchedEffect(list.size) { if (list.isNotEmpty()) state.animateScrollToItem(list.size - 1) }
    if (list.isEmpty()) {
        Text("${w.name} 님과의 대화가 여기에 쌓입니다.\n아래 노란 버튼을 누르고 말하세요.", color = p.muted, fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        return
    }
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        if (list.any { it.example }) item {
            Text("예시 대화 · 첫 통역을 하면 사라집니다", fontSize = 11.sp, color = p.muted, modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        items(list, key = { it.id }) { m -> Bubble(m, w, playingId == m.id, onRead, onDelete) }
    }
}

@Composable
fun Bubble(m: Msg, w: Worker, playing: Boolean, onRead: (Msg) -> Unit, onDelete: (Msg) -> Unit) {
    val p = LocalP.current
    val owner = m.fromOwner
    val bg = if (owner) p.surface else p.staff
    val fg = if (owner) p.ink else p.staffInk
    val sub = if (owner) p.muted else p.staffMuted
    val l = Langs.get(m.lang)
    val shape = if (owner) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (owner) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(0.9f).clip(shape).background(bg).then(if (owner) Modifier.border(1.dp, p.line, shape) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(if (owner) "대표 · 한국어 → ${l.name}" else "${w.name} · ${l.native} → 한국어", fontSize = 11.sp, color = sub, letterSpacing = 0.6.sp)
            Text(m.orig, fontSize = 14.sp, color = sub)
            Text(if (m.pending) "통역 중…" else m.tr, fontSize = if (m.pending) 15.sp else 21.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.Medium, color = if (m.pending) sub else fg)
            if (m.error.isNotEmpty()) Text(m.error, fontSize = 13.sp, color = p.warn)
            if (m.back.isNotEmpty()) {
                HorizontalDivider(color = sub.copy(alpha = 0.35f))
                Text("역번역 확인  ${m.back}", fontSize = 13.sp, color = sub)
            }
            if (!m.pending && m.tr.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (m.voice) Chip(if (m.accurate) "정확 모드" else "음성 통역", sub)
                    m.terms.forEach { Chip("용어 · $it", sub) }
                    if (m.check.isNotEmpty()) Chip(m.check, p.warn)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    DeleteButton(sub) { onDelete(m) }
                    ReadButton(playing, owner) { onRead(m) }
                }
            }
        }
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Text(text, fontSize = 11.sp, color = color, modifier = Modifier.border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(99.dp))
        .padding(horizontal = 8.dp, vertical = 2.dp))
}

/** 읽어주기: 누르는 순간 강조색으로 바뀌고, 소리가 나오는 동안 '읽는 중'으로 빛난다 */
@Composable
fun ReadButton(playing: Boolean, onOwnerBubble: Boolean, onClick: () -> Unit) {
    val p = LocalP.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val hot = pressed || playing
    val bg by animateColorAsState(if (hot) p.tag else Color.Transparent, label = "readBg")
    val fg by animateColorAsState(if (hot) p.tagInk else if (onOwnerBubble) p.ink else p.staffInk, label = "readFg")
    val edge by animateColorAsState(if (hot) p.tagEdge else if (onOwnerBubble) p.line else p.staffMuted, label = "readEdge")
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, label = "readScale")
    val pulse = rememberInfinitePulse(playing)
    Row(
        Modifier.scale(scale).clip(RoundedCornerShape(99.dp)).background(bg).border(if (playing) 2.dp else 1.dp, edge, RoundedCornerShape(99.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .heightIn(min = 40.dp).padding(horizontal = 14.dp).semantics { contentDescription = if (playing) "읽는 중" else "읽어주기" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SpeakerIcon(fg, waves = if (playing) pulse else 1f)
        Text(if (playing) "읽는 중…" else "읽어주기", fontSize = 13.sp, fontWeight = if (hot) FontWeight.Bold else FontWeight.Normal, color = fg)
    }
}

@Composable
fun rememberInfinitePulse(active: Boolean): Float {
    var v by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(active) {
        if (!active) { v = 1f; return@LaunchedEffect }
        var up = false
        while (true) { v = if (up) 1f else 0.35f; up = !up; delay(380) }
    }
    val a by animateFloatAsState(v, label = "pulse")
    return a
}

/** 삭제: 한 번 누르면 '삭제할까요?'로 바뀌고, 3초 안에 한 번 더 누르면 지운다 */
@Composable
fun DeleteButton(color: Color, onConfirm: () -> Unit) {
    val p = LocalP.current
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3000); armed = false } }
    val bg by animateColorAsState(if (armed) p.warn else Color.Transparent, label = "delBg")
    Text(if (armed) "삭제할까요?" else "삭제", fontSize = 13.sp, color = if (armed) Color.White else color,
        fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(bg).border(1.dp, if (armed) p.warn else color.copy(alpha = 0.6f), RoundedCornerShape(99.dp))
            .clickable { if (armed) { armed = false; onConfirm() } else armed = true }
            .heightIn(min = 40.dp).wrapContentHeight().padding(horizontal = 14.dp, vertical = 10.dp))
}

@Composable
fun SpeakerIcon(color: Color, waves: Float) {
    Box(Modifier.size(16.dp).drawBehind {
        val w = size.width; val h = size.height
        val s = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        drawRect(color, topLeft = Offset(w * 0.05f, h * 0.36f), size = Size(w * 0.22f, h * 0.28f))
        drawLine(color, Offset(w * 0.27f, h * 0.36f), Offset(w * 0.52f, h * 0.14f), 1.8.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(w * 0.27f, h * 0.64f), Offset(w * 0.52f, h * 0.86f), 1.8.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(w * 0.52f, h * 0.14f), Offset(w * 0.52f, h * 0.86f), 1.8.dp.toPx(), StrokeCap.Round)
        drawArc(color.copy(alpha = waves), -45f, 90f, false, topLeft = Offset(w * 0.45f, h * 0.25f), size = Size(w * 0.4f, h * 0.5f), style = s)
    })
}

/* ---------------- 아래쪽: 대화 버튼 ---------------- */

private val QUICK = listOf("분만실 온도 확인해", "보온등 켜", "이유 자돈 사료 급여해", "백신 몇 마리 남았어?", "오늘 작업 끝나면 말해줘", "다친 데 없어? 괜찮아?")

@Composable
fun Dock(live: LiveUi, settings: AppSettings, w: Worker, onToggleLive: () -> Unit,
         onPressDown: (Boolean) -> Unit, onPressUp: (Boolean, Long) -> Unit, onAccurate: (Boolean) -> Unit,
         onSend: (String) -> Unit, onDismissNote: () -> Unit) {
    val p = LocalP.current
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = p.line)
        if (live.note.isNotEmpty()) Text(live.note, fontSize = 13.sp, color = p.warn,
            modifier = Modifier.fillMaxWidth().border(1.dp, p.warn, RoundedCornerShape(10.dp)).clickable { onDismissNote() }.padding(10.dp))

        if (live.on && live.detecting && live.ready) Greeting()
        if (live.on && live.capShown) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(14.dp)).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val who = if (live.capWho.isEmpty()) "듣는 중" else live.capWho
            Text(who, fontSize = 11.sp, color = if (live.capOwner) p.tagInk else p.staffInk,
                modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(if (live.capOwner) p.tag else p.staff).padding(horizontal = 8.dp, vertical = 2.dp))
            Column(Modifier.weight(1f)) {
                Text(live.capText.ifEmpty { "말씀하세요" }, fontSize = 14.sp)
                if (live.capSub.isNotEmpty()) Text(live.capSub, fontSize = 12.sp, color = p.muted)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(checked = settings.accurate, onCheckedChange = onAccurate,
                colors = SwitchDefaults.colors(checkedTrackColor = p.staff, checkedThumbColor = p.staffInk))
            Column(Modifier.weight(1f)) {
                Text("정확 모드", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (settings.accurate) p.staff else p.ink)
                Text(if (settings.accurate) (if (settings.orKey.isBlank()) "OpenRouter 키가 필요합니다 (설정)" else "대표님 지시: 용어집 적용 번역 → 음성 · 직원 말은 즉시 통역")
                    else "작업 지시에 축산 용어집 적용 · 2~4초 느림", fontSize = 12.sp, color = p.muted)
            }
        }

        if (!live.on) StartButton(w, onToggleLive)
        else {
            // 누르고 말하기: 누르고 있는 동안 녹음, 손 떼면 통역 · 짧게 톡 누르면 다시 누를 때까지 녹음
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TalkButton(Modifier.weight(1f), owner = true, name = "대표", langLabel = "한국어",
                    enabled = live.ready && !live.detecting, live = live, onDown = { onPressDown(true) }, onUp = { onPressUp(true, it) })
                TalkButton(Modifier.weight(1f), owner = false, name = w.name, langLabel = Langs.get(w.lang).native,
                    enabled = live.ready, live = live, onDown = { onPressDown(false) }, onUp = { onPressUp(false, it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (live.diag.isNotEmpty()) live.diag else "연결 중…", fontSize = 12.sp, color = p.muted, modifier = Modifier.weight(1f))
                Text("대화 끝내기", fontSize = 13.sp, color = p.ink, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(99.dp)).border(1.dp, p.line, RoundedCornerShape(99.dp))
                        .clickable(onClick = onToggleLive).heightIn(min = 40.dp).wrapContentHeight().padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }

        if (!live.on) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(QUICK) { q ->
                Text(q, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(p.surface)
                    .border(1.dp, p.line, RoundedCornerShape(99.dp)).clickable { onSend(q) }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("글로 입력 · 언어 자동 판별") },
                maxLines = 3, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { onSend(text.trim()); text = "" } }))
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).background(p.ink)
                .clickable { if (text.isNotBlank()) { onSend(text.trim()); text = "" } }, contentAlignment = Alignment.Center) {
                Text("→", color = p.bg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun StartButton(w: Worker, onClick: () -> Unit) {
    val p = LocalP.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 84.dp).clip(RoundedCornerShape(20.dp)).background(p.tag)
            .border(2.dp, p.tagEdge, RoundedCornerShape(20.dp)).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MicIcon(p.tagInk)
        Column(Modifier.weight(1f)) {
            Text("대화 시작", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = p.tagInk)
            Text("${w.name} · ${Langs.get(w.lang).native} ↔ 한국어 · 미리 연결해 두고 바로 말합니다", fontSize = 13.sp, color = p.tagInk.copy(alpha = 0.82f))
        }
    }
}

/** 말하기 버튼: 누르는 순간 녹음 시작(진동), 누르고 있는 동안 듣고, 떼면 통역 */
@Composable
fun TalkButton(modifier: Modifier, owner: Boolean, name: String, langLabel: String, enabled: Boolean, live: LiveUi,
               onDown: () -> Unit, onUp: (Long) -> Unit) {
    val p = LocalP.current
    val haptic = LocalHapticFeedback.current
    val me = if (owner) Talker.OWNER else Talker.STAFF
    val rec = live.recording == me
    val busy = live.translating == me
    val base = if (owner) p.tag else p.staff
    val fg = if (owner) p.tagInk else p.staffInk
    val bg by animateColorAsState(if (rec) base else base.copy(alpha = if (enabled) 0.92f else 0.4f), label = "talkBg")
    val ring by animateDpAsState(if (rec) 5.dp else 0.dp, label = "talkRing")
    val scale by animateFloatAsState(if (rec) 1.03f else 1f, label = "talkScale")
    val label = when {
        rec && live.toggled -> "듣는 중 · 다시 누르면 통역"
        rec -> "듣는 중 · 손 떼면 통역"
        busy -> "통역 중…"
        !enabled && owner && live.detecting -> "상대 언어 감지 후"
        !enabled -> "연결 중…"
        else -> "누르고 말하기"
    }
    Column(
        modifier.scale(scale).heightIn(min = 118.dp).clip(RoundedCornerShape(22.dp)).background(bg)
            .border(ring, Color(0xFFD8442B), RoundedCornerShape(22.dp))
            .pointerInput(enabled) {
                detectTapGestures(onPress = {
                    if (!enabled) return@detectTapGestures
                    val t0 = System.currentTimeMillis()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDown()
                    tryAwaitRelease()
                    onUp(System.currentTimeMillis() - t0)
                })
            }
            .semantics { contentDescription = "$name 말하기" }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MicIcon(fg)
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
                Text(langLabel, fontSize = 12.sp, color = fg.copy(alpha = 0.8f), maxLines = 1)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, fontWeight = if (rec) FontWeight.Bold else FontWeight.Normal, color = fg, modifier = Modifier.weight(1f))
            if (rec) Meter(live.level, fg)
        }
    }
}

@Composable
fun MicIcon(color: Color) {
    Box(Modifier.size(30.dp).drawBehind {
        val w = size.width; val h = size.height; val sw = 2.4.dp.toPx()
        drawRoundRect(color, topLeft = Offset(w * 0.36f, h * 0.1f), size = Size(w * 0.28f, h * 0.48f),
            cornerRadius = CornerRadius(w * 0.14f), style = Stroke(sw))
        drawArc(color, 0f, 180f, false, topLeft = Offset(w * 0.2f, h * 0.22f), size = Size(w * 0.6f, h * 0.52f), style = Stroke(sw, cap = StrokeCap.Round))
        drawLine(color, Offset(w / 2, h * 0.74f), Offset(w / 2, h * 0.9f), sw, StrokeCap.Round)
    })
}

@Composable
fun Meter(level: Float, color: Color) {
    val h = (level * 14f).coerceIn(0f, 1f)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(26.dp)) {
        listOf(.5f, .8f, 1f, .8f, .5f).forEach { k ->
            val bar by animateDpAsState((4 + 22 * h * k).dp, label = "bar")
            Box(Modifier.width(5.dp).height(bar).clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.85f)))
        }
    }
}

@Composable
fun Greeting() {
    val p = LocalP.current
    val lines = listOf("Iltimos, o‘z tilingizda gapiring.", "Пожалуйста, говорите на своём языке.", "कृपया आफ्नो भाषामा बोल्नुहोस्।",
        "Vui lòng nói bằng ngôn ngữ của bạn.", "សូមនិយាយជាភាសារបស់អ្នក។", "ကျေးဇူးပြု၍ သင့်ဘာသာစကားဖြင့် ပြောပါ။",
        "กรุณาพูดเป็นภาษาของคุณ", "Please speak in your own language.")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).drawBehind {
        drawRoundRect(p.staff, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))), cornerRadius = CornerRadius(16.dp.toPx()))
    }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("상대에게 이 화면을 보여 주세요 · 자기 나라 말로 말하면 언어를 알아냅니다", fontSize = 12.sp, color = p.muted)
        lines.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { pair.forEach { Text(it, fontSize = 15.sp, modifier = Modifier.weight(1f)) } }
        }
    }
}

/* ---------------- 용어집 ---------------- */

@Composable
fun Glossary(terms: List<Term>, onChange: (List<Term>) -> Unit) {
    val p = LocalP.current
    var ko by remember { mutableStateOf("") }; var ne by remember { mutableStateOf("") }
    var uz by remember { mutableStateOf("") }; var ru by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("번역할 때 이 용어를 반드시 그대로 씁니다. 직원에게 확인받은 뒤 상태를 눌러 ‘검수완료’로 바꾸세요.", fontSize = 13.sp, color = p.muted)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(terms.indices.toList()) { i ->
                val t = terms[i]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(12.dp))
                    .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.ko, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(listOf(t.ne, t.uz, t.ru).filter { it.isNotBlank() }.joinToString("  ·  "), fontSize = 13.sp, color = p.muted)
                    }
                    Text(if (t.ok) "검수완료" else "검수대기", fontSize = 12.sp, color = if (t.ok) p.ok else p.warn,
                        modifier = Modifier.clip(RoundedCornerShape(99.dp)).border(1.dp, if (t.ok) p.ok else p.warn, RoundedCornerShape(99.dp))
                            .clickable { onChange(terms.mapIndexed { j, x -> if (j == i) x.copy(ok = !x.ok) else x }) }
                            .padding(horizontal = 10.dp, vertical = 6.dp))
                    Spacer(Modifier.width(6.dp))
                    DeleteButton(p.muted) { onChange(terms.filterIndexed { j, _ -> j != i }) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(ko, { ko = it }, Modifier.weight(1f), label = { Text("한국어") }, singleLine = true)
            OutlinedTextField(ne, { ne = it }, Modifier.weight(1f), label = { Text("네팔어") }, singleLine = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(uz, { uz = it }, Modifier.weight(1f), label = { Text("우즈베크어") }, singleLine = true)
            OutlinedTextField(ru, { ru = it }, Modifier.weight(1f), label = { Text("러시아어") }, singleLine = true)
            Button(onClick = {
                if (ko.isNotBlank()) { onChange(terms + Term(ko.trim(), ne.trim(), uz.trim(), ru.trim())); ko = ""; ne = ""; uz = ""; ru = "" }
            }, colors = ButtonDefaults.buttonColors(containerColor = p.ink, contentColor = p.bg)) { Text("추가") }
        }
    }
}

/* ---------------- 직원 추가·수정 ---------------- */

@Composable
fun WorkerDialog(w: Worker?, title: String, canDelete: Boolean, onSave: (String, String) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    val p = LocalP.current
    var name by remember(w) { mutableStateOf(w?.name.orEmpty()) }
    var lang by remember(w) { mutableStateOf(w?.lang?.takeIf { it != "auto" } ?: "ne") }
    var open by remember { mutableStateOf(false) }
    val choices = remember(lang) { (Langs.staffChoices() + Langs.get(lang)).distinctBy { it.code } }
    AlertDialog(onDismissRequest = onClose, containerColor = p.surface,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Box {
                    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("사용 언어: ${Langs.get(lang).name} (${Langs.get(lang).native})", color = p.ink)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        choices.forEach { l -> DropdownMenuItem(text = { Text("${l.name} (${l.native})") }, onClick = { lang = l.code; open = false }) }
                    }
                }
                if (canDelete) DeleteButton(p.warn, onDelete)
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank() || w?.guest == true) onSave(name.trim(), lang) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onClose) { Text("닫기") } })
}

/* ---------------- 설정 ---------------- */

@Composable
fun SettingsDialog(s: AppSettings, onSave: (AppSettings) -> Unit, onClose: () -> Unit, onClearFeed: () -> Unit) {
    val p = LocalP.current
    val scope = rememberCoroutineScope()
    var gk by remember { mutableStateOf(s.geminiKey) }
    var ok by remember { mutableStateOf(s.orKey) }
    var model by remember { mutableStateOf(s.orModel) }
    var auto by remember { mutableStateOf(s.autoSpeak) }
    var test by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var openModel by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = p.surface, contentColor = p.ink, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("설정", fontSize = 24.sp, fontWeight = FontWeight.Bold)

                Field("Gemini API 키 · 실시간 음성 대화", "aistudio.google.com/apikey 에서 발급. 말하면 바로 상대 언어 음성으로 통역합니다(Gemini 3.5 Live Translate). 키는 이 휴대폰에만 저장됩니다.") {
                    OutlinedTextField(gk, { gk = it.trim() }, Modifier.fillMaxWidth(), placeholder = { Text("AIza…") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
                Field("OpenRouter API 키 · 글 입력·정확 모드·역번역", "openrouter.ai → Keys. 없어도 음성 대화는 됩니다.") {
                    OutlinedTextField(ok, { ok = it.trim() }, Modifier.fillMaxWidth(), placeholder = { Text("sk-or-v1-…") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
                Field("글 통역 모델", if (OpenRouter.freeModels.isEmpty()) "무료 모델 목록을 받지 못해 기본 목록을 씁니다." else
                    "자동은 ${OpenRouter.chain("auto").joinToString(" → ") { it.substringAfter('/').removeSuffix(":free") }} 순서로 시도합니다.") {
                    Box {
                        OutlinedButton(onClick = { openModel = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (model == "auto") "자동 · 무료 모델 추천순" else model, color = p.ink)
                        }
                        DropdownMenu(expanded = openModel, onDismissRequest = { openModel = false }) {
                            DropdownMenuItem(text = { Text("자동 · 무료 모델 추천순") }, onClick = { model = "auto"; openModel = false })
                            OpenRouter.freeModels.forEach { id -> DropdownMenuItem(text = { Text("무료 · " + id.removeSuffix(":free")) }, onClick = { model = id; openModel = false }) }
                            OpenRouter.paidModels.forEach { m -> DropdownMenuItem(text = { Text("유료 · ${m.id} · 100문장 약 ${m.wonPer100}원") }, onClick = { model = m.id; openModel = false }) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("글 통역 결과 자동으로 읽어주기", fontWeight = FontWeight.SemiBold)
                        Text("Gemini 음성, 안 되면 휴대폰 음성", fontSize = 12.sp, color = p.muted)
                    }
                    Switch(auto, { auto = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(enabled = !testing, onClick = {
                        testing = true; test = "확인 중…"
                        scope.launch { test = connectionTest(gk, ok, model); testing = false }
                    }) { Text("연결 테스트", color = p.ink) }
                    Text(test, fontSize = 13.sp, color = if (test.contains("실패") || test.contains("않") || test.contains("없")) p.warn else p.ok, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onSave(s.copy(geminiKey = gk, orKey = ok, orModel = model, autoSpeak = auto)) },
                        colors = ButtonDefaults.buttonColors(containerColor = p.ink, contentColor = p.bg)) { Text("저장") }
                    OutlinedButton(onClick = onClose) { Text("닫기", color = p.ink) }
                }
                Text("대화 기록·용어집·키는 이 휴대폰에만 저장됩니다. Gemini 무료 등급과 OpenRouter 무료 모델은 제공사가 대화 내용을 학습에 쓸 수 있으니 개인정보(주민번호·계좌 등)는 말하지 마세요.",
                    fontSize = 12.sp, color = p.muted)
                Text("현재 직원 대화 기록 모두 지우기", color = p.warn, modifier = Modifier.clickable { onClearFeed() }.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
fun Field(label: String, help: String, content: @Composable () -> Unit) {
    val p = LocalP.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        content()
        Text(help, fontSize = 12.sp, color = p.muted)
    }
}

/** 설정의 연결 테스트: Gemini 실시간 연결 + OpenRouter 번역 */
suspend fun connectionTest(gk: String, ok: String, model: String): String {
    val out = mutableListOf<String>()
    if (gk.isNotBlank()) {
        val done = CompletableDeferred<String>()
        val s = LiveSession(gk, "ne", object : LiveSession.Listener {
            override fun onReady(s: LiveSession) { done.complete("음성 대화 연결 성공") }
            override fun onContent(s: LiveSession, content: JSONObject) {}
            override fun onGoAway(s: LiveSession) {}
            override fun onClosed(s: LiveSession, code: Int, reason: String) { done.complete("음성 대화 실패: " + LiveSession.closeMessage(code, reason, "네팔어")) }
        })
        s.open()
        out += withTimeoutOrNull(10_000) { done.await() } ?: "음성 대화 응답 없음 (인터넷 확인)"
        s.close()
    }
    if (ok.isNotBlank()) {
        val t0 = System.currentTimeMillis()
        out += runCatching {
            val r = OpenRouter.chat(ok, model, "Translate into Nepali and reply with only JSON {\"t\":\"...\"}: \"보온등 켜\"")
            val t = Prompts.parseJson(r)?.optString("t") ?: r.take(40)
            "글 통역 성공 · ${(System.currentTimeMillis() - t0) / 100 / 10.0}초 · $t"
        }.getOrElse { "글 통역 실패: ${it.message}" }
    }
    return out.joinToString("\n").ifEmpty { "키를 먼저 넣어 주세요." }
}
