/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.round

enum class WaveEncoding(val code: Int, val bytes: Int) { PCM16(1, 2), FLOAT32(3, 4) }
data class WaveMetadata(val rate: Int, val channels: Int, val encoding: WaveEncoding, val frames: Long, val dataOffset: Long, val dataBytes: Long)
data class WorkingPcm(val metadata: WaveMetadata, val samples: ShortArray, val saturatedSamples: Long, val convertedFromFloat: Boolean)

/** Only control/IO workers call this codec. The original file is never modified. */
object LabWaveCodec {
  const val MAX_SECONDS = 120L
  private val importRates = setOf(8000, 16000, 32000, 44100, 48000)
  private const val MAX_BYTES = 256L * 1024 * 1024
  private fun validRate(rate: Int, backend: Boolean) = rate in importRates || (backend && rate in 8000..192000 && rate % 100 == 0)
  private fun u16(file: RandomAccessFile): Int = java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(file.readShort()))
  private fun u32(file: RandomAccessFile): Long = java.lang.Integer.toUnsignedLong(java.lang.Integer.reverseBytes(file.readInt()))
  private fun tag(file: RandomAccessFile): String = ByteArray(4).also(file::readFully).toString(Charsets.US_ASCII)

  fun inspect(file: File, backendCapture: Boolean = false): WaveMetadata = RandomAccessFile(file, "r").use { input ->
    require(input.length() in 44..MAX_BYTES) { "WAV size is outside the supported limit" }
    require(tag(input) == "RIFF") { "Not a RIFF WAV file" }
    val end = u32(input) + 8
    require(end == input.length() && tag(input) == "WAVE") { "Truncated WAV or invalid RIFF size" }
    var rate = 0; var channels = 0; var encoding: WaveEncoding? = null
    var dataOffset = -1L; var dataBytes = -1L; var chunks = 0
    while (input.filePointer < end) {
      require(++chunks <= 4096 && end - input.filePointer >= 8) { "Invalid WAV chunk list" }
      val id = tag(input); val size = u32(input); val offset = input.filePointer
      require(size <= end - offset) { "Truncated WAV chunk" }
      val next = offset + size + (size and 1)
      require(next <= end) { "Missing odd-chunk padding" }
      when (id) {
        "fmt " -> {
          require(encoding == null && size in 16..4096) { "Duplicate or unsupported format chunk" }
          val code = u16(input); channels = u16(input)
          val sampleRate = u32(input); val byteRate = u32(input)
          val align = u16(input); val bits = u16(input)
          require(channels in 1..2 && sampleRate <= Int.MAX_VALUE) { "Unsupported channel layout" }
          rate = sampleRate.toInt()
          encoding = when {
            code == 1 && bits == 16 -> WaveEncoding.PCM16
            code == 3 && bits == 32 -> WaveEncoding.FLOAT32
            else -> throw IllegalArgumentException("Import PCM16 or float32 WAV")
          }
          require(validRate(rate, backendCapture)) { "Unsupported WAV sample rate" }
          require(align == channels * encoding.bytes && byteRate == rate.toLong() * align) { "Inconsistent WAV frame size" }
        }
        "data" -> {
          require(dataOffset < 0 && size > 0) { "Duplicate or empty audio payload" }
          dataOffset = offset; dataBytes = size
        }
      }
      input.seek(next)
    }
    val format = requireNotNull(encoding) { "WAV format missing" }
    require(dataOffset >= 0 && dataBytes % (channels * format.bytes) == 0L) { "WAV payload is not whole frames" }
    val frames = dataBytes / (channels * format.bytes)
    require(frames <= rate * MAX_SECONDS) { "Audio exceeds 120 seconds" }
    WaveMetadata(rate, channels, format, frames, dataOffset, dataBytes)
  }

  private inline fun samples(file: File, info: WaveMetadata, consumer: (Int, Float) -> Unit) {
    RandomAccessFile(file, "r").use { input ->
      input.seek(info.dataOffset)
      val bytes = ByteArray(16384)
      var left = info.dataBytes; var index = 0
      while (left > 0) {
        val count = minOf(bytes.size.toLong(), left).toInt()
        input.readFully(bytes, 0, count)
        val buffer = ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count / info.encoding.bytes) {
          val sample = if (info.encoding == WaveEncoding.PCM16) buffer.short / 32768f else buffer.float
          require(sample.isFinite()) { "Non-finite WAV sample" }
          consumer(index++, sample)
        }
        left -= count
      }
    }
  }

  fun validate(file: File, backendCapture: Boolean = false): WaveMetadata {
    val info = inspect(file, backendCapture)
    samples(file, info) { _, _ -> }
    return info
  }

  fun readPcm16(file: File, backendCapture: Boolean = false): WorkingPcm {
    val info = inspect(file, backendCapture)
    val result = ShortArray(Math.toIntExact(info.frames * info.channels))
    var saturated = 0L
    samples(file, info) { index, sample ->
      if (sample < -1f || sample > 32767f / 32768) saturated++
      result[index] = round(sample.coerceIn(-1f, 32767f / 32768) * 32768).toInt().toShort()
    }
    return WorkingPcm(info, result, saturated, info.encoding == WaveEncoding.FLOAT32)
  }

  fun readFloat(file: File, backendCapture: Boolean = false): FloatArray {
    val info = inspect(file, backendCapture)
    val result = FloatArray(Math.toIntExact(info.frames * info.channels))
    samples(file, info) { index, sample -> result[index] = sample }
    return result
  }

  /** One IO worker owns the writer. Only a completed close publishes the WAV. */
  class Writer(
    private val destination: File,
    private val rate: Int,
    private val channels: Int,
    private val encoding: WaveEncoding,
    backendCapture: Boolean = false
  ) : Closeable {
    private val partial = File(destination.parentFile, destination.name + ".partial")
    private val output: RandomAccessFile
    private var written = 0L
    private var closed = false
    private var failed = false
    private val scratch = ByteBuffer.allocate(3840 * 4).order(ByteOrder.LITTLE_ENDIAN)
    init {
      require(validRate(rate, backendCapture) && channels in 1..2) { "Invalid capture format" }
      require(!destination.exists()) { "Never overwrite a source WAV" }
      if (!partial.createNewFile()) throw IOException("Recording destination is busy")
      output = try { RandomAccessFile(partial, "rw").also { it.write(ByteArray(44)) } } catch (e: Exception) { partial.delete(); throw e }
    }
    fun append(pcm: FloatArray, count: Int = pcm.size) {
      check(!closed && !failed) { "Writer is not active" }
      try {
        require(count in 1..3840 && count <= pcm.size && count % channels == 0) { "Invalid recording block" }
        require(written / (encoding.bytes * channels) + count / channels <= rate * MAX_SECONDS) { "Recording exceeds 120 seconds" }
        for (i in 0 until count) require(pcm[i].isFinite()) { "Non-finite recording sample" }
        scratch.clear()
        for (i in 0 until count) {
          if (encoding == WaveEncoding.FLOAT32) scratch.putFloat(pcm[i])
          else scratch.putShort(round(pcm[i].coerceIn(-1f,32767f/32768)*32768).toInt().toShort())
        }
        output.write(scratch.array(),0,scratch.position()); written += scratch.position()
      } catch (e: Exception) { failed = true; throw e }
    }
    fun abort() {
      if (!closed) { closed = true; try { output.close() } finally { partial.delete() } }
    }
    override fun close() {
      if (closed) return
      if (failed || written == 0L) { abort(); return }
      try {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((written+36).toInt()).put("WAVEfmt ".toByteArray())
          .putInt(16).putShort(encoding.code.toShort()).putShort(channels.toShort()).putInt(rate)
          .putInt(rate*channels*encoding.bytes).putShort((channels*encoding.bytes).toShort())
          .putShort((encoding.bytes*8).toShort()).put("data".toByteArray()).putInt(written.toInt())
        output.seek(0); output.write(header.array()); output.fd.sync(); output.close()
        check(!destination.exists()) { "Destination was created during recording" }
        Files.move(partial.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE)
        closed = true
      } catch (e: Exception) { abort(); throw e }
    }
  }
}
