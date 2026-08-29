package com.example.qasidatosong

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavData(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val samples: ShortArray
)

object WavUtils {

    fun readWav(file: File): WavData {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") {
                "ملف WAV غير صالح"
            }

            var sampleRate = 22050
            var channels = 1
            var bitsPerSample = 16
            var dataBytes: ByteArray? = null

            while (raf.filePointer < raf.length()) {
                val chunkId = ByteArray(4)
                if (raf.read(chunkId) < 4) break
                val sizeBytes = ByteArray(4)
                raf.readFully(sizeBytes)
                val chunkSize = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).int

                val id = String(chunkId)
                if (id == "fmt ") {
                    val fmt = ByteArray(chunkSize)
                    raf.readFully(fmt)
                    val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    bb.short
                    channels = bb.short.toInt()
                    sampleRate = bb.int
                    bb.int
                    bb.short
                    bitsPerSample = bb.short.toInt()
                } else if (id == "data") {
                    dataBytes = ByteArray(chunkSize)
                    raf.readFully(dataBytes)
                } else {
                    raf.skipBytes(chunkSize)
                }
                if (chunkSize % 2 != 0 && raf.filePointer < raf.length()) {
                    raf.skipBytes(1)
                }
            }

            val bytes = dataBytes ?: ByteArray(0)
            val shortCount = bytes.size / 2
            val samples = ShortArray(shortCount)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until shortCount) {
                samples[i] = bb.short
            }

            return WavData(sampleRate, channels, bitsPerSample, samples)
        }
    }

    fun writeWav(file: File, samples: ShortArray, sampleRate: Int, channels: Int = 1) {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = samples.size * 2
        val chunkSize = 36 + dataSize

        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(chunkSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(dataSize)
            raf.write(header.array())

            val dataBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                dataBuffer.putShort(s)
            }
            raf.write(dataBuffer.array())
        }
    }
}
