package com.seongjin.interpreter

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** 16kHz 모노 마이크. 통화용 입력이라 휴대폰의 에코 제거·소음 제거가 같이 켜진다. */
class Mic {
    private var rec: AudioRecord? = null
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200 * 8))
        }.getOrNull() ?: return false
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        val sid = r.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sid)?.let { it.enabled = true; effects += it }
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sid)?.let { it.enabled = true; effects += it }
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sid)?.let { it.enabled = true; effects += it }
        runCatching { r.startRecording() }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); return false }
        rec = r
        return true
    }

    /** 0.1초(3200바이트)를 읽는다. 멈췄으면 음수 */
    fun read(buf: ByteArray): Int = rec?.read(buf, 0, buf.size) ?: -1

    fun stop() {
        effects.forEach { runCatching { it.release() } }; effects.clear()
        rec?.let { runCatching { it.stop() }; it.release() }
        rec = null
    }
}

/** 24kHz 통역 음성 재생. 재생이 끝나는 시각을 계산해 '지금 말하는 중인지' 알려준다. */
class Player(private val voiceCall: Boolean) {
    private var track: AudioTrack? = null
    private val queue = Channel<ByteArray>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writer: Job? = null
    @Volatile private var playUntil = 0L

    private fun ensure() {
        if (track != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(if (voiceCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val fmt = AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min, 48000)).setTransferMode(AudioTrack.MODE_STREAM).build()
        t.play()
        track = t
        writer = scope.launch { for (chunk in queue) { track?.write(chunk, 0, chunk.size) } }
    }

    /** 16비트 24kHz PCM 조각을 줄 세운다. 반환값: 이 조각이 끝나는 시각(ms) */
    @Synchronized
    fun enqueue(pcm: ByteArray): Long {
        ensure()
        val now = SystemClock.elapsedRealtime()
        playUntil = maxOf(now + 60, playUntil) + pcm.size / 48L
        queue.trySend(pcm)
        return playUntil
    }

    /** 통역 음성이 나오던 중에 새로 말하기 시작하면 바로 끊는다 */
    @Synchronized
    fun stopNow() {
        while (queue.tryReceive().isSuccess) { /* 대기 중인 조각 버림 */ }
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
        playUntil = 0
    }

    fun speaking(tailMs: Long = 250): Boolean = SystemClock.elapsedRealtime() < playUntil + tailMs
    fun remainingMs(): Long = maxOf(0L, playUntil - SystemClock.elapsedRealtime())

    fun release() {
        writer?.cancel(); writer = null
        track?.let { runCatching { it.pause(); it.flush(); it.stop() }; it.release() }
        track = null
        while (queue.tryReceive().isSuccess) { /* 남은 조각 버림 */ }
        playUntil = 0
    }
}

/** 통화 모드로 바꾸고, 이어폰·블루투스가 있으면 그쪽, 없으면 스피커로 소리를 보낸다. */
class Routing(ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var oldMode = AudioManager.MODE_NORMAL

    fun begin(): String {
        oldMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val devs = am.availableCommunicationDevices
            val pref = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            val pick = pref.firstNotNullOfOrNull { t -> devs.firstOrNull { it.type == t } }
            if (pick != null) runCatching { am.setCommunicationDevice(pick) }
            return when (pick?.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null -> "스피커"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "블루투스"
                else -> "이어폰"
            }
        } else {
            @Suppress("DEPRECATION")
            val headset = am.isWiredHeadsetOn || am.isBluetoothScoOn
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = !headset
            return if (headset) "이어폰" else "스피커"
        }
    }

    fun end() {
        if (Build.VERSION.SDK_INT >= 31) runCatching { am.clearCommunicationDevice() }
        else { @Suppress("DEPRECATION") am.isSpeakerphoneOn = false }
        am.mode = oldMode
    }
}
