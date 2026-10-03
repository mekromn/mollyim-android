/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

private fun expectRejected(body: () -> Unit) {
  var failed = false
  try { body() } catch (_: IllegalArgumentException) { failed = true } catch (_: java.io.IOException) { failed = true }
  check(failed) { "Malformed or excessive WAV accepted" }
}
private fun bytes(value: Int, count: Int = 4) = ByteArray(count) { (value ushr (it * 8)).toByte() }
private fun chunk(id: String, payload: ByteArray): ByteArray = id.toByteArray() + bytes(payload.size) + payload + if (payload.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
private fun wave(rate: Int = 48000, channels: Int = 1, code: Int = 1, bits: Int = 16, payload: ByteArray = byteArrayOf(0,0), odd: Boolean = false, dataFirst: Boolean = false): ByteArray {
  val align = channels * bits / 8
  val fmt = bytes(code,2)+bytes(channels,2)+bytes(rate)+bytes(rate*align)+bytes(align,2)+bytes(bits,2)
  val chunks = if (dataFirst) chunk("data",payload)+chunk("fmt ",fmt) else chunk("fmt ",fmt)+chunk("data",payload)
  val body = "WAVE".toByteArray() + (if (odd) chunk("JUNK",byteArrayOf(1,2,3)) else byteArrayOf()) + chunks
  return "RIFF".toByteArray()+bytes(body.size)+body
}
fun main() {
  val root = Files.createTempDirectory("molly-wave-test").toFile()
  try {
    val file = File(root,"test.wav")
    val pcm = shortArrayOf(Short.MIN_VALUE,-1234,0,1234,Short.MAX_VALUE)
    val payload = ByteBuffer.allocate(pcm.size*2).order(ByteOrder.LITTLE_ENDIAN).also { b -> pcm.forEach { b.putShort(it) } }.array()
    file.writeBytes(wave(payload=payload,odd=true,dataFirst=true))
    val sourceBytes=file.readBytes()
    val meta=LabWaveCodec.inspect(file)
    check(meta.frames==5L && meta.rate==48000 && meta.channels==1 && meta.encoding==WaveEncoding.PCM16)
    val working=LabWaveCodec.readPcm16(file)
    check(working.samples.contentEquals(pcm) && working.saturatedSamples==0L)
    check(file.readBytes().contentEquals(sourceBytes))
    val roundtrip=File(root,"roundtrip.wav")
    LabWaveCodec.Writer(roundtrip,48000,1,WaveEncoding.PCM16).use { it.append(FloatArray(pcm.size) { i -> pcm[i]/32768f }) }
    check(LabWaveCodec.readPcm16(roundtrip).samples.contentEquals(pcm))
    val floats=floatArrayOf(-1.25f,-0.5f,0f,0.125f,1.25f)
    val fp=ByteBuffer.allocate(floats.size*4).order(ByteOrder.LITTLE_ENDIAN).also { b -> floats.forEach { b.putFloat(it) } }.array()
    file.writeBytes(wave(code=3,bits=32,payload=fp))
    check(LabWaveCodec.inspect(file).encoding==WaveEncoding.FLOAT32)
    val converted=LabWaveCodec.readPcm16(file)
    check(converted.saturatedSamples==2L && converted.convertedFromFloat)
    val floatOutput=File(root,"float.wav")
    LabWaveCodec.Writer(floatOutput,48000,1,WaveEncoding.FLOAT32).use { it.append(floats) }
    check(LabWaveCodec.readFloat(floatOutput).contentEquals(floats))
    for (rate in listOf(8000,16000,32000,44100,48000)) {
      file.writeBytes(wave(rate=rate,channels=2,payload=byteArrayOf(1,0,2,0)))
      check(LabWaveCodec.inspect(file).rate==rate && LabWaveCodec.inspect(file).frames==1L)
    }
    file.writeBytes(wave(channels=3,payload=ByteArray(6)));expectRejected { LabWaveCodec.inspect(file) }
    file.writeBytes(wave(rate=22050));expectRejected { LabWaveCodec.inspect(file) }
    file.writeBytes(wave(bits=24,payload=ByteArray(3)));expectRejected { LabWaveCodec.inspect(file) }
    file.writeBytes(wave(payload=ByteArray(3)));expectRejected { LabWaveCodec.inspect(file) }
    file.writeBytes(wave().copyOf(30));expectRejected { LabWaveCodec.inspect(file) }
    file.writeBytes(wave().dropLast(1).toByteArray());expectRejected { LabWaveCodec.inspect(file) }
    file.writeBytes(wave().also { bytes(Int.MAX_VALUE).copyInto(it,16) });expectRejected { LabWaveCodec.inspect(file) }
    for (invalid in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
      val bad=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(invalid).array()
      file.writeBytes(wave(code=3,bits=32,payload=bad));expectRejected { LabWaveCodec.readPcm16(file) }
    }
    file.writeBytes(wave(rate=8000,payload=ByteArray((8000*120+1)*2)));expectRejected { LabWaveCodec.inspect(file) }
    // No successfully finalized-looking file after explicit abort or bad samples.
    val aborted=File(root,"aborted.wav")
    val writer=LabWaveCodec.Writer(aborted,48000,1,WaveEncoding.FLOAT32)
    expectRejected { writer.append(floatArrayOf(Float.NaN)) };writer.abort();check(!aborted.exists())
    expectRejected { LabWaveCodec.Writer(roundtrip,48000,1,WaveEncoding.PCM16) }
    val backend=File(root,"backend.wav")
    LabWaveCodec.Writer(backend,96000,2,WaveEncoding.PCM16,backendCapture=true).use { it.append(floatArrayOf(0f,0.25f)) }
    expectRejected { LabWaveCodec.inspect(backend) }
    check(LabWaveCodec.inspect(backend,backendCapture=true).rate==96000)
    println("PASS WAV PCM16/float32 identity, odd padding/order, explicit saturation, invalid values, sizes/rates, backend format and abort safety")
  } finally { root.deleteRecursively() }
}
