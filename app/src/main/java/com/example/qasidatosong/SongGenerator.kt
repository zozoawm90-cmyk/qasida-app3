package com.example.qasidatosong

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI

class SongGenerator(private val context: Context) {

    companion object {
        const val TARGET_DURATION_SECONDS = 180
        const val GAP_SECONDS = 1.2
    }

    interface Callback {
        fun onProgress(message: String)
        fun onSuccess(outputFile: File)
        fun onError(message: String)
    }

    private var tts: TextToSpeech? = null

    fun generateSong(poemText: String, callback: Callback) {
        if (poemText.isBlank()) {
            callback.onError("الرجاء إدخال نص القصيدة أولاً")
            return
        }

        val ttsReadyLatch = CountDownLatch(1)
        var ttsOk = false

        tts = TextToSpeech(context) { status ->
            ttsOk = status == TextToSpeech.SUCCESS
            ttsReadyLatch.countDown()
        }

        ttsReadyLatch.await(10, TimeUnit.SECONDS)
        val engine = tts
        if (!ttsOk || engine == null) {
            callback.onError("تعذّر تشغيل محرك تحويل النص إلى صوت على هذا الجهاز")
            return
        }

        val arabicResult = engine.setLanguage(Locale("ar"))
        if (arabicResult == TextToSpeech.LANG_MISSING_DATA || arabicResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            callback.onError("لا تتوفر حزمة اللغة العربية لمحرك النطق على هذا الجهاز. الرجاء تثبيتها من إعدادات النظام (تحويل النص إلى كلام).")
            engine.shutdown()
            return
        }
        engine.setSpeechRate(0.85f)

        callback.onProgress("جارٍ تسجيل الإنشاد الصوتي...")

        val rawFile = File(context.cacheDir, "raw_narration_${UUID.randomUUID()}.wav")
        val synthLatch = CountDownLatch(1)
        var synthError: String? = null
        val utteranceId = "qasida_utt"

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                synthLatch.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                synthError = "فشل تحويل النص إلى صوت"
                synthLatch.countDown()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                synthError = "فشل تحويل النص إلى صوت (رمز الخطأ: $errorCode)"
                synthLatch.countDown()
            }
        })

        val params = Bundle()
        val result = engine.synthesizeToFile(poemText, params, rawFile, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            callback.onError("تعذّر بدء عملية التحويل الصوتي")
            engine.shutdown()
            return
        }

        synthLatch.await(120, TimeUnit.SECONDS)
        engine.shutdown()

        if (synthError != null || !rawFile.exists() || rawFile.length() == 0L) {
            callback.onError(synthError ?: "لم يتم إنشاء ملف الصوت")
            return
        }

        try {
            callback.onProgress("جارٍ تحليل التسجيل الصوتي...")
            val narrationWav = WavUtils.readWav(rawFile)
            if (narrationWav.samples.isEmpty()) {
                callback.onError("التسجيل الصوتي فارغ، حاول بنص أطول")
                return
            }
            val sampleRate = narrationWav.sampleRate

            callback.onProgress("جارٍ تكرار الإنشاد لملء ٣ دقائق...")
            val targetSamples = TARGET_DURATION_SECONDS * sampleRate
            val gapSamples = (GAP_SECONDS * sampleRate).toInt()
            val loopedNarration = loopToLength(narrationWav.samples, targetSamples, gapSamples)

            callback.onProgress("جارٍ تأليف اللحن الموسيقي...")
            val melody = MelodyGenerator.generate(TARGET_DURATION_SECONDS, sampleRate)

            callback.onProgress("جارٍ دمج الصوت مع اللحن...")
            val mixed = mix(loopedNarration, melody)

            val outputDir = File(context.getExternalFilesDir(null), "Songs").apply { mkdirs() }
            val outputFile = File(outputDir, "قصيدة_أغنية_${System.currentTimeMillis()}.wav")
            WavUtils.writeWav(outputFile, mixed, sampleRate, channels = 1)

            rawFile.delete()
            callback.onSuccess(outputFile)
        } catch (e: Exception) {
            callback.onError("حدث خطأ أثناء المعالجة: ${e.message}")
        }
    }

    private fun loopToLength(source: ShortArray, targetLength: Int, gapSamples: Int): ShortArray {
        if (source.isEmpty()) return ShortArray(targetLength)

        val result = ShortArray(targetLength)
        var writeIndex = 0
        while (writeIndex < targetLength) {
            val remaining = targetLength - writeIndex
            val copyLen = min(source.size, remaining)
            System.arraycopy(source, 0, result, writeIndex, copyLen)
            writeIndex += copyLen
            if (writeIndex >= targetLength) break
            val silenceLen = min(gapSamples, targetLength - writeIndex)
            writeIndex += silenceLen
        }
        return result
    }

    private fun mix(narration: ShortArray, melody: ShortArray): ShortArray {
        val length = min(narration.size, melody.size)
        val result = ShortArray(length)
        val narrationGain = 0.85
        val melodyGain = 0.30
        for (i in 0 until length) {
            var sample = (narration[i] * narrationGain) + (melody[i] * melodyGain)
            if (sample > Short.MAX_VALUE) sample = Short.MAX_VALUE.toDouble()
            if (sample < Short.MIN_VALUE) sample = Short.MIN_VALUE.toDouble()
            result[i] = sample.toInt().toShort()
        }
        return result
    }
}

object MelodyGenerator {

    private val scaleHz = doubleArrayOf(
        220.00,
        246.94,
        261.63,
        293.66,
        329.63,
        349.23,
        392.00
    )

    fun generate(durationSeconds: Int, sampleRate: Int): ShortArray {
        val totalSamples = durationSeconds * sampleRate
        val result = ShortArray(totalSamples)

        val noteDurationSeconds = 0.9
        val noteSamples = (noteDurationSeconds * sampleRate).toInt()
        val fadeSamples = (0.05 * sampleRate).toInt()

        var index = 0
        var noteCursor = 0
        while (index < totalSamples) {
            val patternPos = noteCursor % (scaleHz.size * 2 - 2)
            val noteIndex = if (patternPos < scaleHz.size) patternPos
                             else scaleHz.size * 2 - 2 - patternPos
            val freq = scaleHz[noteIndex]

            val thisNoteLen = min(noteSamples, totalSamples - index)
            for (i in 0 until thisNoteLen) {
                val t = i.toDouble() / sampleRate
                var amplitude = 0.5
                var value = sin(2.0 * PI * freq * t) * amplitude
                value += sin(2.0 * PI * (freq * 1.25) * t) * (amplitude * 0.35)

                val fadeFactor = when {
                    i < fadeSamples -> i.toDouble() / fadeSamples
                    i > thisNoteLen - fadeSamples -> (thisNoteLen - i).toDouble() / fadeSamples
                    else -> 1.0
                }
                value *= fadeFactor.coerceIn(0.0, 1.0)

                val sampleValue = (value * Short.MAX_VALUE * 0.6).toInt()
                result[index + i] = sampleValue.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            index += thisNoteLen
            noteCursor++
        }

        return result
    }
}
