/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.thoughtcrime.securesms.webrtc.audio.*

private class FakeEngine : LabNativePort {
  val data=FloatArray(128).also{it[0]=1f;it[1]=1f;it[4]=1f;it[5]=1f;it[6]=1f;it[116]=-1f;it[117]=-1f}
  var queued=0;var sourceStart=0L
  var microphoneStarts=0;var configureCount=0;var revoked=false;var closed=false
  private var serial=0L
  private fun ack():Long=(++serial).also{data[3]=it.toFloat()}
  override fun configure(values:FloatArray):Long {check(values.size==41);configureCount++;return ack()}
  override fun command(op:Int,a:Long,b:Long,c:Long,d:Long):Long {
    check(!closed)
    when(op){1->{if(b==1L)microphoneStarts++;data[1]=2f;data[4]=0f;data[5]++;data[7]=a.toFloat()};2->{data[1]=3f;data[4]=1f};3->{data[13]=a.toFloat();data[5]++};4->data[6]=a.toFloat()}
    return ack()
  }
  override fun load(direction:Int,rate:Int,channels:Int,pcm:ShortArray):Long {check(data[4]==1f);return ack()}
  override fun status(values:FloatArray):Boolean {data.copyInto(values);return !closed}
  override fun drain(tap:Int,metadata:LongArray,pcm:FloatArray):Int {
    if(tap!=1||queued==0)return 0
    val m=longArrayOf(1,data[5].toLong(),data[6].toLong(),1,1,8000,1,80,sourceStart,1000000,1,0,0,0,0,0)
    m.copyInto(metadata);pcm.fill(0.125f);queued--;sourceStart+=80;return 80
  }
  override fun revoke(){revoked=true;data[1]=4f;data[4]=1f}
  override fun close(){check(data[4]==1f);closed=true}
}
private class FakeRoute : LabRoutePort {
  var selected:LabRouteChoice?=null;var permission=true;var closed=false
  override fun choices()=listOf(LabRouteChoice(1,CallRoute.HANDSET,"Earpiece"),LabRouteChoice(2,CallRoute.SPEAKERPHONE,"Speakerphone"))
  override fun start(choice:LabRouteChoice){selected=choice}
  override fun observe()=LabRouteObservation(selected,9,"Input metadata",!permission,3)
  override fun microphonePermission()=permission
  override val routeError=false
  override fun shutdown()=CompletableFuture.completedFuture<Void>(null).also{closed=true}
}
fun main(){
 val root=Files.createTempDirectory("mock-controller").toFile()
 val allowed=AtomicBoolean(true);val engine=FakeEngine();val route=FakeRoute();var opens=0;var applies=0
 val direct=Executor{it.run()}
 val env=LabControllerEnvironment(root,{LabHardwareObjects(engine,route,"Fixture backend").also{opens++}},{true},{allowed.get()}, {_,_->applies++;ApplyResult(true,1)}, {},direct,direct)
 val c=MockCallLabController(LabToken(1,1),LabConfiguration(),env)
 try{
  c.setVisible(true).get();c.barrier().get()
  check(opens==0 && engine.microphoneStarts==0)
  c.updateDenoise(Direction.SENT,DenoiseSettings(enabled=true));c.save();c.barrier().get()
  check(opens==0&&applies==0) {"Editing lab settings must not acquire hardware or save production"}
  val wav=File(root,"test.wav");LabWaveCodec.Writer(wav,8000,1,WaveEncoding.PCM16).use{it.append(FloatArray(800){0.125f})}
  val take=c.importWave({wav.inputStream()},"Original").get()
  c.selectSource(Direction.RECEIVED,take.id,0).get()
  c.play().get();check(opens==1&&engine.microphoneStarts==0)
  c.pause().get();check(engine.data[4]==1f)
  c.selectRoute(2).get()
  check(route.selected?.id==2 && c.view.value.actualRoute=="Speakerphone") {"Idle native route selection must change the actual communication device, not only the requested ID"}
  check(engine.microphoneStarts==0) {"Selecting a route must not open the microphone"}
  c.setMode(LabMode.SENT).get();c.record().get();check(engine.microphoneStarts==1)
  engine.queued=2;engine.sourceStart=0
  c.pause().get()
  check(c.view.value.takes.any{it.label.endsWith("microphone take")&&it.complete&&it.tracks.any{track->track.frames==160L}}) {"Stopping must finalize current-epoch accepted samples as complete"}
  val replay=c.view.value.sources[Direction.SENT.wireId]
  check(replay!=null && replay.track.tap==1 && replay.track.frames==160L) {"A completed microphone take must become the Sent replay source immediately"}
  check(c.view.value.selectionStartMs==0L && c.view.value.selectionEndMs==20L) {"Auto-selected replay must expose its whole recorded interval"}
  c.applyToCalls(setOf(LabSection.SENT_DENOISE)).get();check(applies==1)
  allowed.set(false);c.preempt().toCompletableFuture().get();check(engine.revoked)
  check(runCatching{c.record().get()}.isFailure);check(engine.microphoneStarts==1)
  check(runCatching{c.applyToCalls(setOf(LabSection.SENT_DENOISE)).get()}.isFailure);check(applies==1)
  println("PASS controller: no mic on open/edit/import/play; explicit recording; isolated Apply and irreversible preemption")
 }finally{c.close();root.deleteRecursively()}
}
