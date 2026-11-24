package com.kp.recapstudio

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale

/**
 * Dedicated arm64 whisper.cpp bridge. Model supplied separately from app.
 * No microphone trickery or simulated ASR: Whisper receives real decoded media WAV.
 */
object OfflineWhisper {
    interface Callback {
        fun onMessage(message: String)
        fun onResult(text: String, srt: String)
        fun onError(error: String)
    }

    @JvmStatic
    fun transcribe(context: Context, model: File, audio: File, callback: Callback) {
        Thread({
            try {
                if (!model.isFile || model.length() < 20_000_000L)
                    throw IllegalStateException("Whisper multilingual model missing or incomplete")
                if (!audio.isFile || audio.length() < 44L)
                    throw IllegalStateException("Decoded WAV audio is missing")
                callback.onMessage("Loading Whisper speech model...")
                val handle = runBlocking { Whisper.loadModel(context.applicationContext, model.absolutePath) }
                try {
                    callback.onMessage("Recognizing speech offline. Long videos take time...")
                    val result = runBlocking {
                        Whisper.transcribe(handle, audio.absolutePath, WhisperConfig(language = "auto"))
                    }
                    val transcript = result.text.trim()
                    val srt = buildString {
                        result.segments.forEachIndexed { index, segment ->
                            val beginning = segment.startMs.coerceAtLeast(0)
                            val ending = segment.endMs.coerceAtLeast(beginning + 1)
                            append(index + 1).append('\n')
                            append(timestamp(beginning)).append(" --> ").append(timestamp(ending)).append('\n')
                            append(segment.text.trim()).append("\n\n")
                        }
                    }
                    if (transcript.isEmpty()) throw IllegalStateException("No recognizable speech found")
                    callback.onResult(transcript, srt)
                } finally {
                    Whisper.releaseModel(handle)
                }
            } catch (e: Throwable) {
                callback.onError(e.javaClass.simpleName + ": " + (e.message ?: "Unknown speech error"))
            } finally {
                audio.delete()
            }
        }, "kp-offline-whisper").start()
    }

    private fun timestamp(milliseconds: Long): String {
        val hours = milliseconds / 3600000
        val minutes = (milliseconds / 60000) % 60
        val seconds = (milliseconds / 1000) % 60
        val ms = milliseconds % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, ms)
    }
}
