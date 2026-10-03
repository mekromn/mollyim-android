/* SPDX-License-Identifier: AGPL-3.0-only */
import org.thoughtcrime.securesms.webrtc.audio.mock.*
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.file.Files

private fun expectFailure(f: () -> Unit) { check(runCatching(f).isFailure) }
fun main() {
  val root = Files.createTempDirectory("mock-store-tests").toFile()
  try {
    val input = File(root,"input.wav")
    LabWaveCodec.Writer(input,8000,1,WaveEncoding.PCM16).use { it.append(FloatArray(80){i -> (i-40)/128f}) }
    val store = LabTakeStore(File(root,"private"),4096)
    val imported = store.importWave(ByteArrayInputStream(input.readBytes()),"Handset test")
    check(!imported.saved && imported.complete && imported.tracks.size==1)
    check(store.trackFile(imported,imported.tracks.single()).readBytes().contentEquals(input.readBytes()))
    val saved = store.save(imported.id)
    check(saved.saved && store.list().single().id==saved.id)
    val reopened = LabTakeStore(File(root,"private"),4096)
    check(reopened.list().single().tracks.single().firstSourceFrame==0L)
    val before = reopened.usedBytes()
    expectFailure { reopened.importWave(ByteArrayInputStream(ByteArray(9000)),"bad") }
    check(reopened.usedBytes()==before)
    check(reopened.list().single().id==saved.id)
    expectFailure { reopened.delete("../input.wav") };check(input.exists())
    val temporary = reopened.importWave(ByteArrayInputStream(input.readBytes()),"Temporary")
    check(!temporary.saved)
    val startup = LabTakeStore(File(root,"private"),4096)
    check(startup.list().size==1 && startup.list().single().saved)
    check(!File(File(root,"private/temporary"),temporary.id).exists())
    val recording = startup.begin("Speakerphone",mapOf("route" to "Speakerphone"))
    val samples = FloatArray(80){0.25f}
    val packet = LabAudioPacket(2,3,1,1,8000,1,80,0,1000000,1)
    recording.append(packet,samples,80)
    recording.append(packet.copy(sourceStart=80,timeNs=11000000),samples,80)
    val take = recording.finish()
    check(take.complete && take.tracks.single().frames==160L)
    check(take.tracks.single().firstSourceFrame==0L && take.tracks.single().firstTimeNs==1000000L)
    check(LabWaveCodec.readFloat(startup.trackFile(take,take.tracks.single())).all{it==0.25f})
    val changed = startup.begin("Changed route")
    changed.append(packet,samples,80)
    changed.append(packet.copy(epoch=3,segment=4,sourceStart=80),samples,80)
    val split=changed.finish();check(split.tracks.size==2)
    val gap = startup.begin("Gap")
    gap.append(packet,samples,80)
    gap.append(packet.copy(sourceStart=160),samples,80)
    val missing=gap.finish();check(!missing.complete && missing.tracks.size==2 && missing.reason.isNotEmpty())
    var allowed=true
    val cancel = startup.begin("Cancelled",allowed={allowed})
    cancel.append(packet,samples,80);allowed=false
    check(!cancel.append(packet.copy(sourceStart=80),samples,80))
    val aborted=cancel.finish();check(!aborted.complete)
    expectFailure { startup.save(aborted.id+"/../bad") }
    startup.delete(take.id);startup.delete(split.id);startup.delete(missing.id);startup.delete(aborted.id)
    startup.delete(saved.id);check(startup.list().isEmpty())
    val failed=startup.begin("Bad writer")
    check(failed.append(packet,samples,80))
    check(!failed.append(packet.copy(sourceStart=80),FloatArray(80){Float.NaN},80))
    val failure=failed.finish()
    check(!failure.complete&&failure.tracks.isEmpty()) {"An aborted writer must not publish a nonexistent track"}
    check(startup.get(failure.id).tracks.isEmpty())
    val shared=File(root,"shared")
    val oldSession=LabTakeStore(shared,sessionIsolated=true)
    val oldTake=oldSession.importWave(ByteArrayInputStream(input.readBytes()),"Old")
    val newSession=LabTakeStore(shared,sessionIsolated=true)
    val newTake=newSession.importWave(ByteArrayInputStream(input.readBytes()),"New")
    oldSession.discardUnsaved()
    check(newSession.get(newTake.id).complete) {"Retired session cleanup deleted a newer session's recording"}
    check(newSession.list().none{it.id==oldTake.id})
    println("PASS: private quota, exact imports, saved/temp lifetime, route segments, gaps and revocation")
  } finally { root.deleteRecursively() }
}
