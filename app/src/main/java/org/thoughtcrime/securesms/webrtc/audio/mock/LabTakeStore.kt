/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A borrowed native tap block; its samples must be consumed before the next JNI drain. */
data class LabAudioPacket(
  val epoch: Long, val segment: Long, val configuration: Long, val tap: Int,
  val rate: Int, val channels: Int, val frames: Int, val sourceStart: Long,
  val timeNs: Long, val flags: Int
) {
  fun valid(count: Int): Boolean = epoch > 0 && segment > 0 && configuration >= 0 && tap in 0..4 &&
    channels in 1..2 && frames > 0 && count == frames * channels && count <= 3840 &&
    (if (tap == 0) rate in 8000..192000 && rate % 100 == 0 else rate in listOf(8000,16000,32000,48000)) &&
    frames == rate / 100
}
data class LabTrack(
  val fileName: String, val tap: Int, val epoch: Long, val segment: Long,
  val configuration: Long, val rate: Int, val channels: Int, val encoding: WaveEncoding,
  val frames: Long, val firstSourceFrame: Long, val firstTimeNs: Long, val fallbackBlocks: Long,
  val configurationChanged: Boolean
)
data class LabTake(
  val id: String, val label: String, val saved: Boolean, val complete: Boolean,
  val reason: String, val properties: Map<String,String>, val tracks: List<LabTrack>
)

/** Private no-backup storage. One IO executor owns recordings; quota checks also
 * serialize imports/saves. Manifests use a bounded versioned binary encoding,
 * never Java object deserialization or caller-supplied paths. JSON is export-only.
 */
class LabTakeStore(private val root: File, private val quotaBytes: Long = 256L * 1024 * 1024, sessionIsolated:Boolean=false) {
  private val temporaryRoot = File(root,"temporary")
  private val temporary = if(sessionIsolated) File(temporaryRoot,UUID.randomUUID().toString()) else temporaryRoot
  private val saved = File(root,"saved")
  private val identifier = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
  init {
    require(quotaBytes in 1024..256L*1024*1024)
    require(!Files.isSymbolicLink(root.toPath()))
    check(root.mkdirs() || root.isDirectory)
    // Production sessions own separate directories. Only the first open after
    // process startup removes abandoned temporary data; late old cleanup cannot
    // remove a new session's recordings. Saved recordings are shared explicitly.
    synchronized(isolationLock){
      if(!sessionIsolated || initializedRoots.add(root.canonicalPath)){
        if(temporaryRoot.exists())check(temporaryRoot.deleteRecursively()){ "Unable to retire abandoned lab recordings" }
      }
      check(temporary.mkdirs() || temporary.isDirectory)
    }
    check(saved.mkdirs() || saved.isDirectory)
  }
  companion object { private val isolationLock=Any();private val initializedRoots=HashSet<String>() }
  @Synchronized fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
  @Synchronized private fun budget(bytes: Long, operation: () -> Unit) {
    require(bytes >= 0 && bytes <= quotaBytes && usedBytes() <= quotaBytes - bytes) { "Private lab storage limit reached" }
    operation()
  }
  private fun safeId(id: String) { require(identifier.matches(id)) { "Invalid take identifier" } }
  private fun directory(id: String): File {
    safeId(id)
    val retained=File(saved,id)
    return if(retained.isDirectory) retained else File(temporary,id)
  }
  private fun safeFile(directory: File, name: String): File {
    require(Regex("track-[0-9]{1,3}\\.wav|source\\.wav|take\\.meta").matches(name)) { "Invalid private file" }
    val file=File(directory,name)
    require(!Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(file.toPath()))
    require(file.canonicalFile.parentFile==directory.canonicalFile)
    return file
  }
  @Synchronized fun list(): List<LabTake> = listOf(temporary,saved).flatMap { parent ->
    parent.listFiles()?.filter { it.isDirectory && identifier.matches(it.name) && !Files.isSymbolicLink(it.toPath()) }
      ?.mapNotNull { runCatching { read(it) }.getOrNull() } ?: emptyList()
  }.sortedBy { it.label }
  @Synchronized fun get(id: String): LabTake = read(directory(id))
  @Synchronized fun trackFile(take: LabTake, track: LabTrack): File {
    val current=get(take.id)
    require(track in current.tracks) { "Track is not part of this take" }
    return safeFile(directory(take.id),track.fileName).also { require(it.isFile) }
  }
  @Synchronized fun delete(id: String) {
    val file=directory(id)
    require(!Files.isSymbolicLink(file.toPath()))
    if(file.exists() && !file.deleteRecursively()) throw IOException("Unable to delete lab take")
  }
  @Synchronized fun discardUnsaved() { temporary.listFiles()?.forEach { require(!Files.isSymbolicLink(it.toPath()));it.deleteRecursively() } }
  @Synchronized fun save(id: String): LabTake {
    val current=get(id)
    if(current.saved)return current
    val destination=File(saved,id)
    require(!destination.exists())
    Files.move(directory(id).toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE)
    return current.copy(saved=true)
  }
  @Synchronized fun begin(label: String, properties: Map<String,String> = emptyMap(), allowed: () -> Boolean = {true}): Recording {
    require(label.length<=96 && properties.size<=64 && properties.all { it.key.length<=80&&it.value.length<=2048 })
    require(allowed()) { "Recording permission expired" }
    val id=UUID.randomUUID().toString();val dir=File(temporary,id)
    budget(1024) { check(dir.mkdir()) }
    return Recording(id,label,dir,properties.toMap(),allowed)
  }
  @Synchronized fun importWave(input: InputStream, label: String): LabTake {
    val record=begin(label,mapOf("source" to "Imported original WAV"))
    val target=File(record.dir,"source.wav")
    try {
      input.use { source ->
        FileOutputStream(target).use { output ->
          val bytes=ByteArray(16384);var total=0L
          while(true){val n=source.read(bytes);if(n<0)break;if(n==0)continue
            total+=n;require(total<=quotaBytes)
            budget(n.toLong()){output.write(bytes,0,n)}
          }
          output.fd.sync()
        }
      }
      val meta=LabWaveCodec.validate(target)
      val track=LabTrack("source.wav",1,1,1,0,meta.rate,meta.channels,meta.encoding,meta.frames,0,-1,0,false)
      val take=LabTake(record.id,label,false,true,"",mapOf("source" to "Imported original WAV"),listOf(track))
      write(record.dir,take)
      return take
    } catch(e:Exception){record.dir.deleteRecursively();throw e}
  }

  private fun write(dir: File,take: LabTake) {
    val partial=File(dir,"take.meta.partial")
    try {
      budget(1024L + take.tracks.size*160L + take.properties.entries.sumOf { (it.key.length+it.value.length)*3L }) {
        FileOutputStream(partial).use { raw ->
          val out=DataOutputStream(raw)
          out.writeInt(0x4D434C31);out.writeUTF(take.id);out.writeUTF(take.label);out.writeBoolean(take.complete);out.writeUTF(take.reason.take(256))
          out.writeInt(take.properties.size)
          take.properties.forEach { (k,v)->out.writeUTF(k);out.writeUTF(v) }
          out.writeInt(take.tracks.size)
          take.tracks.forEach { t ->
            out.writeUTF(t.fileName);out.writeInt(t.tap);out.writeLong(t.epoch);out.writeLong(t.segment);out.writeLong(t.configuration)
            out.writeInt(t.rate);out.writeInt(t.channels);out.writeInt(t.encoding.code);out.writeLong(t.frames)
            out.writeLong(t.firstSourceFrame);out.writeLong(t.firstTimeNs);out.writeLong(t.fallbackBlocks);out.writeBoolean(t.configurationChanged)
          }
          out.flush();raw.fd.sync()
        }
      }
      Files.move(partial.toPath(),File(dir,"take.meta").toPath(),StandardCopyOption.ATOMIC_MOVE)
    } catch(e:Exception){partial.delete();throw e}
  }
  private fun read(dir: File): LabTake {
    safeId(dir.name)
    val manifest=safeFile(dir,"take.meta");require(manifest.length() in 16..524288)
    return DataInputStream(FileInputStream(manifest)).use { input ->
      require(input.readInt()==0x4D434C31)
      val id=input.readUTF();safeId(id);require(id==dir.name)
      val label=input.readUTF();require(label.length<=96)
      val complete=input.readBoolean();val reason=input.readUTF();require(reason.length<=256)
      val count=input.readInt();require(count in 0..64)
      val properties=linkedMapOf<String,String>()
      repeat(count){val k=input.readUTF();val v=input.readUTF();require(k.length<=80&&v.length<=2048&&k !in properties);properties[k]=v}
      val tracks=input.readInt();require(tracks in 0..256)
      val result=ArrayList<LabTrack>(tracks)
      repeat(tracks){
        val name=input.readUTF();val tap=input.readInt();val epoch=input.readLong();val segment=input.readLong();val configuration=input.readLong()
        val rate=input.readInt();val channels=input.readInt();val code=input.readInt()
        val encoding=WaveEncoding.entries.singleOrNull{it.code==code} ?: throw IOException("Invalid track encoding")
        val frames=input.readLong();val first=input.readLong();val time=input.readLong();val fallback=input.readLong();val changed=input.readBoolean()
        require(tap in 0..4&&epoch>0&&segment>0&&configuration>=0&&frames>0&&fallback>=0)
        val wave=LabWaveCodec.inspect(safeFile(dir,name),true)
        require(wave.rate==rate&&wave.channels==channels&&wave.encoding==encoding&&wave.frames==frames)
        require(result.none{it.fileName==name})
        result+=LabTrack(name,tap,epoch,segment,configuration,rate,channels,encoding,frames,first,time,fallback,changed)
      }
      require(input.read()==-1)
      LabTake(id,label,dir.parentFile.canonicalFile==saved.canonicalFile,complete,reason,properties,result)
    }
  }

  inner class Recording internal constructor(
    val id:String,private val label:String,internal val dir:File,
    private val properties:Map<String,String>,private val allowed:()->Boolean
  ):Closeable {
    private inner class Active(val first:LabAudioPacket,val file:String,val encoding:WaveEncoding,val writer:LabWaveCodec.Writer) {
      var frames=0L;var expected=first.sourceStart;var fallback=0L;var changed=false
      fun track()=LabTrack(file,first.tap,first.epoch,first.segment,first.configuration,first.rate,first.channels,encoding,frames,first.sourceStart,first.timeNs,fallback,changed)
    }
    private val active=HashMap<Int,Active>()
    private val completed=ArrayList<LabTrack>()
    private var serial=0;private var reason="";private var finished:LabTake?=null
    private val totalFrames=LongArray(5)
    fun incomplete(message:String){if(reason.isEmpty())reason=message.take(256)}
    private fun finishTrack(tap:Int) {
      val current=active.remove(tap)?:return
      current.writer.close()
      if(current.frames>0){
        if(safeFile(dir,current.file).isFile)completed+=current.track()
        else incomplete("Recording writer aborted before finalization")
      }
    }
    fun append(packet:LabAudioPacket,pcm:FloatArray,count:Int):Boolean = synchronized(this@LabTakeStore) {
      check(finished==null)
      if(!allowed()){incomplete("Recording was revoked");return false}
      require(packet.valid(count)&&count<=pcm.size)
      if(packet.flags and 2 != 0)return true // Explicit pre-roll is not part of a selected export.
      val encoding=if(packet.flags and 1 != 0)WaveEncoding.PCM16 else WaveEncoding.FLOAT32
      try {
        var a=active[packet.tap]
        if(a!=null && (a.first.epoch!=packet.epoch||a.first.segment!=packet.segment||a.first.rate!=packet.rate||a.first.channels!=packet.channels||a.encoding!=encoding||a.expected!=packet.sourceStart)){
          if(a.first.epoch==packet.epoch&&a.first.segment==packet.segment&&a.expected!=packet.sourceStart)incomplete("Missing or non-contiguous audio interval")
          finishTrack(packet.tap);a=null
        }
        // Duration is enforced in nanoseconds across native-rate changes, not by
        // interpreting old-rate sample counts with a new rate.
        val duration=packet.frames.toLong()*1_000_000_000L/packet.rate
        require(totalFrames[packet.tap]+duration<=120_000_000_000L){"Recording exceeds 120 seconds"}
        if(a==null){
          require(serial<256){"Too many recording segments"}
          val file="track-${serial++}.wav"
          budget(44){a=Active(packet,file,encoding,LabWaveCodec.Writer(safeFile(dir,file),packet.rate,packet.channels,encoding,true))}
          active[packet.tap]=requireNotNull(a)
        }
        val current=requireNotNull(a)
        budget(count.toLong()*encoding.bytes){current.writer.append(pcm,count)}
        current.frames+=packet.frames;current.expected=Math.addExact(packet.sourceStart,packet.frames.toLong())
        current.changed=current.changed||packet.configuration!=current.first.configuration
        if(packet.flags and 4 != 0)current.fallback++
        totalFrames[packet.tap]+=duration
        true
      } catch(e:Exception){incomplete(e.message?:"Recording write failed");false}
    }
    fun finish():LabTake = synchronized(this@LabTakeStore) {
      finished?.let{return it}
      if(!allowed())incomplete("Recording was revoked")
      for(tap in active.keys.toList())runCatching{finishTrack(tap)}.onFailure{incomplete("Track finalization failed")}
      if(completed.isEmpty())incomplete("No audio was captured")
      val take=LabTake(id,label,false,reason.isEmpty(),reason,properties,completed.toList())
      write(dir,take);finished=take;take
    }
    override fun close(){finish()}
  }
}
