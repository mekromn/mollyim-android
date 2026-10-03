/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.webrtc.audio.mock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.thoughtcrime.securesms.webrtc.audio.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** All file IO and commands are serialized on this controller's executor. Audio
 * callbacks communicate only through the endpoint's existing bounded tap queues.
 * Revocation is a separate immediate path and never waits for that executor. */
class MockCallLabController(
  val token:LabToken, initial:LabConfiguration, private val environment:LabControllerEnvironment
) : CallAudioSession,AutoCloseable {
  data class Source(val take:LabTake,val track:LabTrack) {
    val durationMs:Long get()=track.frames*1000/track.rate
    val label:String get()="${take.label} · ${tapName(track.tap)}"
  }
  data class View(
    val phase:String="Ready",val message:String="Record once, loop, tune, and apply deliberately.",
    val visible:Boolean=false,val busy:Boolean=false,val playing:Boolean=false,val recording:Boolean=false,
    val mode:LabMode=LabMode.RECEIVED,val monitor:Monitor=Monitor.RECEIVED,val requestedRoute:Int=0,
    val routes:List<LabRouteChoice> = emptyList(),val actualRoute:String="Not acquired",val inputRoute:String="Unavailable",
    val backend:String="Not acquired",val sources:List<Source?> = listOf(null,null),val takes:List<LabTake> = emptyList(),
    val selectionStartMs:Long=0,val selectionEndMs:Long=0,val loop:Boolean=false,val original:Boolean=false,
    val stats:LabStatsSnapshot=LabStatsSnapshot(),val frozen:Boolean=false,val snapshot:String="Custom",
    val procedure:String="Fixed position",val retired:Boolean=false,val tapMask:Int=15,
    val benchmark:BenchmarkView=BenchmarkView()
  )
  data class BenchmarkView(
    val running:Boolean=false,
    val direction:Direction=Direction.SENT,
    val currentModel:Model?=null,
    val completed:Int=0,
    val total:Int=3,
    val summary:DenoiseBenchmarkSummary=DenoiseBenchmarkSummary()
  )
  private val executor=Executors.newSingleThreadScheduledExecutor{job->Thread(job,"mock-call-control-io").apply{isDaemon=true}}
  private val retired=AtomicBoolean(false)
  private val closed=AtomicBoolean(false)
  private val captureAllowed=AtomicBoolean(false)
  private val mutableUi=MutableStateFlow(CallAudioUiState(DenoiseUiSnapshot(settings=DenoisePair(initial.received,initial.sent)),initial.effects))
  override val state=mutableUi.asStateFlow()
  private val mutableView=MutableStateFlow(View())
  val view=mutableView.asStateFlow()
  @Volatile private var configuration=initial.copy(effects=initial.effects.copy(eq=initial.effects.eq.toList()))
  @Volatile private var lifetime:LabLifecycle?=null
  @Volatile private var hardware:LabHardwareObjects?=null
  @Volatile private var measured=LabStatsSnapshot()
  private var store:LabTakeStore?=null
  private val stats=LabStatsRepository()
  private val routeState=LabRouteController(token) { captureAllowed.set(false) }
  private var routeTicket:RouteTicket?=null
  private var recording:LabTakeStore.Recording?=null
  private var activeMask=0
  private var snapshotA:LabConfiguration?=null
  private var snapshotB:LabConfiguration?=null
  private data class BenchmarkRun(
    val direction:Direction,
    val savedConfiguration:LabConfiguration,
    val savedMode:LabMode,
    val savedMonitor:Monitor,
    val savedStart:Long,
    val savedEnd:Long,
    val savedLoop:Boolean,
    val savedOriginal:Boolean,
    val models:List<Model> = listOf(Model.STANDARD,Model.MOBILE_FUSED,Model.LOW_LATENCY),
    val results:MutableList<DenoiseBenchmarkResult> = mutableListOf(),
    var index:Int=0,
    var startedAtMs:Long=0,
    var fallbackOccurred:Boolean=false,
    var maxMisses:Long=0
  )
  private var benchmarkRun:BenchmarkRun?=null
  private var modelsReady=false
  private var retiredFactory:CompletionStage<Void>?=null
  private var publishedAt=0L
  private var recordingStartedAt=0L
  private var lastPollAt=0L
  private val rawStats=FloatArray(128)
  private val tapMeta=LongArray(16)
  private val tapPcm=FloatArray(3840)
  override val effectsAvailable:Boolean get()=hardware!=null&&!retired.get()

  init {
    require(initial.valid())
    executor.scheduleWithFixedDelay({
      if(!closed.get())try { poll() } catch(e:Exception) { fail(e.message?:"Local audio operation failed") }
    },10,10,TimeUnit.MILLISECONDS)
  }
  private fun current(){check(!retired.get()&&!closed.get()&&environment.isCurrent()){"Local test was closed or preempted"}}
  private fun storage():LabTakeStore=store?:LabTakeStore(environment.directory,sessionIsolated=true).also{store=it}
  private fun changed(block:(View)->View){
    while(true){
      val before=mutableView.value
      var next=block(before)
      if(retired.get())next=next.copy(retired=true,playing=false,recording=false,busy=false,phase="Ended")
      if(mutableView.compareAndSet(before,next))return
    }
  }
  private fun publishConfiguration(){
    mutableUi.value=CallAudioUiState(DenoiseUiSnapshot(DenoisePair(configuration.received,configuration.sent),hardware!=null,modelsReady),configuration.effects)
  }
  private fun <T> submit(body:()->T):CompletableFuture<T> {
    val future=CompletableFuture<T>()
    if(closed.get()){future.completeExceptionally(IllegalStateException("Lab is closed"));return future}
    try { executor.execute {
      try{current();future.complete(body())}catch(e:Exception){changed{it.copy(message=e.message?:"Local test failed",busy=false)};future.completeExceptionally(e)}
    }}catch(e:Exception){future.completeExceptionally(e)}
    return future
  }
  fun barrier():CompletableFuture<Unit> = submit{}
  fun setVisible(visible:Boolean):CompletableFuture<Unit> = submit {
    if(!visible&&benchmarkRun!=null)finishBenchmark(cancelled=true,reason="Benchmark stopped when the lab left the foreground")
    changed{it.copy(visible=visible)}
    routeState.environment(visible,hardware?.route?.microphonePermission()?:false)
    if(!visible)retireHardware(false)
    else changed{it.copy(takes=storage().list())}
  }
  private fun toWire():FloatArray {
    val wire=FloatArray(41);wire[0]=1f
    for((i,s) in listOf(configuration.received,configuration.sent).withIndex()){
      val o=1+8*i;wire[o]=if(s.enabled)1f else 0f;wire[o+1]=s.model.wireId.toFloat();wire[o+2]=s.attenuationDb
      wire[o+3]=if(s.postFilter)1f else 0f;wire[o+4]=s.beta;wire[o+5]=s.minSnr;wire[o+6]=s.erbSnr;wire[o+7]=s.dfSnr
    }
    configuration.effects.wire().copyInto(wire,17);return wire
  }
  private fun ensureModels(){
    if(modelsReady||(!configuration.sent.enabled&&!configuration.received.enabled))return
    check(recording==null){"Stop recording before preparing previously unavailable models"}
    changed{it.copy(phase="Preparing models",busy=true)}
    mutableUi.value=mutableUi.value.copy(denoise=mutableUi.value.denoise.copy(assetsLoading=true))
    modelsReady=environment.installModels();current()
    check(modelsReady){"Packaged DeepFilterNet models could not be verified"}
    publishConfiguration()
  }
  private fun acquire():LabHardwareObjects {
    current();check(mutableView.value.visible){"Open the lab screen before testing"}
    hardware?.let{return it}
    retiredFactory?.let {
      // Only a new lab acquisition waits for its old factory. Real calls wait
      // for hardware release alone, never model retirement.
      it.toCompletableFuture().get(2,TimeUnit.SECONDS)
      retiredFactory=null
    }
    ensureModels()
    val life=LabLifecycle(environment.disposal);lifetime=life
    val ticket=requireNotNull(life.beginAcquisition())
    if(retired.get()||!environment.isCurrent()){life.failedAcquisition(ticket);throw IllegalStateException("Local acquisition was preempted")}
    val made=try{environment.acquire()}catch(e:Exception){life.failedAcquisition(ticket);throw e}
    val owned=object:LabHardwareLifetime {
      override fun revoke(){captureAllowed.set(false);made.native.revoke()}
      override fun stopHardware():CompletionStage<Void> {
        val result=CompletableFuture<Void>()
        environment.retirement.execute {
          try{
            val status=FloatArray(128);val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(true){
              check(made.native.status(status)){"Native retirement status unavailable"}
              if(status[4]!=0f)break
              check(System.nanoTime()<until){"Audio hardware did not stop before the safety deadline"};Thread.sleep(5)
            }
            made.route.shutdown().toCompletableFuture().get(2,TimeUnit.SECONDS);result.complete(null)
          }catch(e:Exception){result.completeExceptionally(e)}
        }
        return result
      }
      override fun releaseAfterStop(){made.native.close()}
    }
    if(!life.attach(ticket,owned)){throw IllegalStateException("Lab acquisition was preempted")}
    if(!environment.isCurrent()||retired.get()){life.revoke();throw IllegalStateException("Real call took ownership")}
    hardware=made
    changed{it.copy(routes=made.route.choices(),backend=made.backend,phase="Ready",busy=false)}
    publishConfiguration();await(made.native.configure(toWire()),made)
    return made
  }
  private fun read(h:LabHardwareObjects):LabStatsSnapshot {
    check(h.native.status(rawStats)){"Native test endpoint unavailable"}
    return requireNotNull(LabStatsSnapshot.parse(rawStats,environment.nowMs())){"Invalid native status"}
  }
  private fun await(id:Long,h:LabHardwareObjects= requireNotNull(hardware)):LabStatsSnapshot {
    check(id>0){"Native command rejected"};val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
    while(true){
      current();val s=read(h)
      if(s.command>=id){check(s.endpointState!=5){"Native call-path operation failed (${s.error})"};return s}
      check(System.nanoTime()<until){"Audio command timed out"};Thread.sleep(5)
    }
  }
  private fun confirmedRoute(h:LabHardwareObjects,record:Boolean):RouteTicket {
    val choices=h.route.choices();changed{it.copy(routes=choices)}
    val choice=choices.firstOrNull{it.id==mutableView.value.requestedRoute}?:choices.firstOrNull{it.route==CallRoute.HANDSET}?:choices.firstOrNull()
    check(choice!=null){"No communication route is available"}
    routeState.environment(mutableView.value.visible,h.route.microphonePermission())
    val request=requireNotNull(routeState.request(choice.route,choice.id)){"Route request is no longer allowed"}
    routeTicket=request;h.route.start(choice)
    val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
    while(true){
      current();val o=h.route.observe()
      check(!h.route.routeError){"Communication route selection failed"}
      if(o.output?.id==choice.id&&o.mode==3){
        check(routeState.confirm(request,RouteObservation(choice.route,choice.id,o.inputId,0,false)))
        changed{it.copy(requestedRoute=choice.id,actualRoute=choice.label,inputRoute=o.inputDescription)}
        break
      }
      check(System.nanoTime()<until){"The requested communication route was not confirmed"};Thread.sleep(10)
    }
    if(record)check(routeState.recordPressed(request)&&routeState.mayOpenMicrophone(request)){"Microphone permission or recording consent is missing"}
    await(h.native.command(4,request.segment),h)
    return request
  }
  private fun loadSources(h:LabHardwareObjects,live:Boolean){
    val v=mutableView.value
    for(direction in 0..1){
      if((direction==0&&v.mode==LabMode.SENT)||(direction==1&&(live||v.mode==LabMode.RECEIVED)))continue
      val selected=requireNotNull(v.sources[direction]){"Choose the ${if(direction==0)"Received" else "Sent"} source first"}
      val working=LabWaveCodec.readPcm16(storage().trackFile(selected.take,selected.track),true)
      check(working.metadata.rate in listOf(8000,16000,32000,44100,48000)){"This backend recording requires a supported working-rate conversion"}
      await(h.native.load(direction,working.metadata.rate,working.metadata.channels,working.samples),h)
      if(working.convertedFromFloat||working.metadata.rate==44100)changed{it.copy(message="Original file preserved. Call-mixer working PCM conversion is labeled in the take; no source was overwritten.")}
    }
  }
  private fun start(live:Boolean,renderCapture:Boolean=false){
    current();pauseInternal();ensureModels();val h=acquire()
    val v=mutableView.value
    if(live)check(v.mode!=LabMode.RECEIVED){"Select Sent or Both to record the microphone"}
    confirmedRoute(h,live);loadSources(h,live)
    val duration=if(live)120000L else v.sources.mapIndexedNotNull{i,s->s?.takeIf{(i==0&&v.mode!=LabMode.SENT)||(i==1&&v.mode!=LabMode.RECEIVED)}?.durationMs}.minOrNull()?:0L
    val end=(v.selectionEndMs.takeIf{it>v.selectionStartMs}?:duration).coerceAtMost(duration)
    check(live||end>v.selectionStartMs){"Select a nonempty audio interval"}
    await(h.native.command(6,if(live)0 else v.selectionStartMs,if(live)120000 else end))
    await(h.native.configure(toWire()))
    await(h.native.command(5,if(v.original)1 else 0))
    activeMask=when{live->v.tapMask and 15;renderCapture->if(v.mode==LabMode.RECEIVED)16 else if(v.mode==LabMode.SENT)4 else 20;else->0}
    if(activeMask!=0){
      captureAllowed.set(true)
      val props=configuration.toStored().mapKeys{"setting.${it.key}"}.toMutableMap()
      // At most 64 small versioned scalar properties, no account identifiers.
      props["route"]=mutableView.value.actualRoute;props["backend"]=h.backend;props["procedure"]=v.procedure
      recording=storage().begin(if(live)"${mutableView.value.actualRoute} microphone take" else "Processed ${v.snapshot} pass",props){!retired.get()&&environment.isCurrent()}
      recordingStartedAt=environment.nowMs()
    }
    val monitor=if(live)0 else v.monitor.ordinal
    measured=await(h.native.command(1,v.mode.ordinal.toLong(),if(live)1 else 0,monitor.toLong(),activeMask.toLong()))
    if(live){
      val s=read(h);val o=h.route.observe();val choice=requireNotNull(o.output)
      routeState.confirm(requireNotNull(routeTicket),RouteObservation(choice.route,choice.id,o.inputId,s.epoch,true))
    }
    changed{it.copy(playing=true,recording=live,selectionEndMs=if(live)it.selectionEndMs else end,phase=if(live)"Recording" else "Playing / warming up",busy=false)}
  }
  private fun checkedStart(live:Boolean,renderCapture:Boolean=false){
    try{start(live,renderCapture)}catch(e:Exception){fail(e.message?:"Unable to start local audio");throw e}
  }
  fun prepareRoutes():CompletableFuture<Unit> = submit { acquire();Unit }
  fun play():CompletableFuture<Unit> = submit{check(benchmarkRun==null){"Benchmark is running"};checkedStart(false)}
  fun record():CompletableFuture<Unit> = submit{check(benchmarkRun==null){"Benchmark is running"};checkedStart(true)}
  fun renderProcessed():CompletableFuture<Unit> = submit{check(benchmarkRun==null){"Benchmark is running"};checkedStart(false,true)}
  fun pause():CompletableFuture<Unit> = submit{
    if(benchmarkRun!=null)finishBenchmark(cancelled=true,reason="Benchmark stopped") else pauseInternal()
  }
  private fun pauseInternal(){
    val h=hardware
    val wasMicRecording=mutableView.value.recording
    if(h!=null&&mutableView.value.playing){measured=await(h.native.command(2));drain(h)}
    captureAllowed.set(false);routeState.stopCapture()
    recording?.let{r->runCatching{r.finish()}.onSuccess{t->
      changed{view->
        val replayTrack=if(wasMicRecording&&t.complete)t.tracks.firstOrNull{track->track.tap==1}else null
        if(replayTrack!=null){
          val selected=view.sources.toMutableList()
          selected[Direction.SENT.wireId]=Source(t,replayTrack)
          view.copy(
            message="Microphone take ready for Sent replay",
            sources=selected.toList(),
            selectionStartMs=0,
            selectionEndMs=replayTrack.frames*1000/replayTrack.rate
          )
        }else{
          view.copy(message=if(t.complete)"Take captured locally" else "Incomplete take: ${t.reason}")
        }
      }
    }.onFailure{changed{v->v.copy(message="Recording finalization failed")}}}
    recording=null;activeMask=0
    changed{it.copy(playing=false,recording=false,phase="Ready",takes=store?.list()?:it.takes)}
  }

  fun benchmark(direction:Direction):CompletableFuture<Unit> = submit {
    check(benchmarkRun==null){"Benchmark is already running"}
    check(!mutableView.value.recording){"Stop recording before benchmarking"}
    val v=mutableView.value
    val source=requireNotNull(v.sources[direction.wireId]){"Choose a ${if(direction==Direction.SENT)"Sent" else "Received"} replay source first"}
    val end=(v.selectionEndMs.takeIf{it>v.selectionStartMs}?:source.durationMs).coerceAtMost(source.durationMs)
    check(end-v.selectionStartMs>=3000){"Select at least 3 seconds of audio for a meaningful realtime benchmark"}
    pauseInternal()
    benchmarkRun=BenchmarkRun(
      direction=direction,
      savedConfiguration=configuration,
      savedMode=v.mode,
      savedMonitor=v.monitor,
      savedStart=v.selectionStartMs,
      savedEnd=end,
      savedLoop=v.loop,
      savedOriginal=v.original
    )
    beginBenchmarkPass()
  }

  fun useBenchmarkPick():CompletableFuture<Unit> = submit {
    check(benchmarkRun==null){"Wait for the benchmark to finish"}
    val b=mutableView.value.benchmark
    val pick=requireNotNull(b.summary.realtimePick){"No stable realtime model was found"}
    configuration=if(b.direction==Direction.RECEIVED){
      configuration.copy(received=configuration.received.copy(model=pick))
    }else{
      configuration.copy(sent=configuration.sent.copy(model=pick))
    }
    publishConfiguration();applyLiveConfiguration()
    changed{it.copy(message="Realtime speed pick selected for this lab. Use Apply to calls to save it.")}
  }

  private fun beginBenchmarkPass(){
    val run=requireNotNull(benchmarkRun)
    val model=run.models[run.index]
    pauseInternal()
    configuration=if(run.direction==Direction.RECEIVED){
      run.savedConfiguration.copy(
        received=run.savedConfiguration.received.copy(enabled=true,model=model),
        sent=run.savedConfiguration.sent.copy(enabled=false)
      )
    }else{
      run.savedConfiguration.copy(
        received=run.savedConfiguration.received.copy(enabled=false),
        sent=run.savedConfiguration.sent.copy(enabled=true,model=model)
      )
    }
    publishConfiguration()
    changed{it.copy(
      mode=if(run.direction==Direction.RECEIVED)LabMode.RECEIVED else LabMode.SENT,
      monitor=if(run.direction==Direction.RECEIVED)Monitor.RECEIVED else Monitor.SENT,
      selectionStartMs=run.savedStart,selectionEndMs=run.savedEnd,loop=false,original=false,
      benchmark=BenchmarkView(true,run.direction,model,run.index,run.models.size,DenoiseBenchmarkJudge.summarize(run.results)),
      message="Benchmarking ${model.name.replace('_',' ')} through the real call path"
    )}
    run.fallbackOccurred=false;run.maxMisses=0
    checkedStart(false)
    run.startedAtMs=environment.nowMs()
  }

  private fun benchmarkPoll(s:LabStatsSnapshot):Boolean {
    val run=benchmarkRun?:return false
    val d=if(run.direction==Direction.RECEIVED)s.received else s.sent
    run.fallbackOccurred=run.fallbackOccurred||d.state==5
    run.maxMisses=maxOf(run.maxMisses,d.misses)
    val elapsed=environment.nowMs()-run.startedAtMs
    val enough=d.inferenceValid&&d.processed>=200
    val timedOut=elapsed>=6000
    if(!enough&&!s.finished&&!timedOut)return true

    run.results+=DenoiseBenchmarkResult(
      model=run.models[run.index],
      meanMs=d.meanMs,
      p95Ms=d.p95Ms,
      misses=run.maxMisses,
      processed=d.processed,
      snr=d.snr,
      fallbackOccurred=run.fallbackOccurred,
      complete=enough
    )
    pauseInternal()
    if(run.index+1<run.models.size){
      ++run.index
      beginBenchmarkPass()
    }else{
      finishBenchmark(cancelled=false,reason="")
    }
    return true
  }

  private fun finishBenchmark(cancelled:Boolean,reason:String){
    val run=benchmarkRun?:return
    if(mutableView.value.playing)pauseInternal()
    configuration=run.savedConfiguration
    publishConfiguration()
    val summary=DenoiseBenchmarkJudge.summarize(run.results)
    benchmarkRun=null
    changed{it.copy(
      mode=run.savedMode,monitor=run.savedMonitor,
      selectionStartMs=run.savedStart,selectionEndMs=run.savedEnd,
      loop=run.savedLoop,original=run.savedOriginal,
      benchmark=BenchmarkView(false,run.direction,null,run.results.size,run.models.size,summary),
      message=if(cancelled)reason else if(summary.realtimePick!=null)
        "Benchmark complete. Realtime speed pick: ${summary.realtimePick.name.replace('_',' ')}"
      else "Benchmark complete. No model met the stable realtime deadline."
    )}
  }

  fun setMode(mode:LabMode):CompletableFuture<Unit> = submit{check(benchmarkRun==null){"Benchmark is running"};pauseInternal();changed{it.copy(mode=mode,monitor=if(mode==LabMode.SENT)Monitor.SENT else Monitor.RECEIVED)}}
  fun selectRoute(id:Int):CompletableFuture<Unit> = submit {
    val wasRecording=mutableView.value.recording;val wasPlaying=mutableView.value.playing
    pauseInternal();changed{it.copy(requestedRoute=id)}
    if(wasRecording)checkedStart(true)
    else if(wasPlaying)checkedStart(false)
    else confirmedRoute(acquire(),false)
  }
  fun setMonitor(monitor:Monitor):CompletableFuture<Unit> = submit {
    if(mutableView.value.recording){pauseInternal();changed{it.copy(message="Microphone stopped. Select a recorded Sent take to audition it.",monitor=monitor)};return@submit}
    hardware?.let{if(mutableView.value.playing)await(it.native.command(10,monitor.ordinal.toLong()))}
    changed{it.copy(monitor=monitor)}
  }
  fun selection(startMs:Long,endMs:Long):CompletableFuture<Unit> = submit{pauseInternal();require(startMs>=0&&endMs>startMs&&endMs<=120000);changed{it.copy(selectionStartMs=startMs,selectionEndMs=endMs)}}
  fun setLoop(value:Boolean):CompletableFuture<Unit> = submit{changed{it.copy(loop=value)}}
  fun original(value:Boolean):CompletableFuture<Unit> = submit{hardware?.let{await(it.native.command(5,if(value)1 else 0))};changed{it.copy(original=value)}}
  fun setTapMask(mask:Int):CompletableFuture<Unit> = submit{check(!mutableView.value.playing);require(mask in 1..15);changed{it.copy(tapMask=mask)}}
  fun procedure(value:String):CompletableFuture<Unit> = submit{require(value=="Fixed position"||value=="Typical use");changed{it.copy(procedure=value)}}
  fun setMuted(value:Boolean):CompletableFuture<Unit> {
    if(value)captureAllowed.set(false)
    return submit{
      val h=hardware?:return@submit
      await(h.native.command(3,if(value)1 else 0))
      if(!value&&recording!=null&&mutableView.value.recording&&h.route.microphonePermission())captureAllowed.set(true)
    }
  }
  fun importWave(open:()->InputStream,label:String):CompletableFuture<LabTake> = submit {
    pauseInternal();val take=storage().importWave(open(),label.take(96));current();changed{it.copy(takes=storage().list(),message="Imported original WAV; no microphone was opened")};take
  }
  fun selectSource(direction:Direction,takeId:String,trackIndex:Int,acceptProcessed:Boolean=false):CompletableFuture<Unit> = submit {
    pauseInternal();val take=storage().get(takeId);val track=take.tracks.getOrNull(trackIndex)?:throw IllegalArgumentException("Track unavailable")
    require(track.tap !in listOf(2,3,4)||acceptProcessed){"Confirm using a processed recording as a new source"}
    val selected=mutableView.value.sources.toMutableList();selected[direction.wireId]=Source(take,track)
    changed{it.copy(sources=selected.toList(),selectionStartMs=0,selectionEndMs=track.frames*1000/track.rate)}
  }
  fun saveTake(id:String):CompletableFuture<LabTake> = submit{pauseInternal();storage().save(id).also{changed{v->v.copy(takes=storage().list())}}}
  fun deleteTake(id:String):CompletableFuture<Unit> = submit{pauseInternal();storage().delete(id);changed{it.copy(takes=storage().list(),sources=it.sources.map{s->s?.takeUnless{v->v.take.id==id}})}}
  fun exportTrack(takeId:String,index:Int,open:()->OutputStream):CompletableFuture<Unit> = submit{
    pauseInternal();val take=storage().get(takeId);val source=storage().trackFile(take,take.tracks[index]);open().use{out->source.inputStream().use{it.copyTo(out)}}
  }
  fun exportReport(json:Boolean,open:()->OutputStream):CompletableFuture<Unit> = submit{
    val v=mutableView.value;val text=if(json)LabReport.json(measured,v.actualRoute,v.backend)else LabReport.text(measured,v.actualRoute,v.backend)
    open().bufferedWriter().use{it.write(text)}
  }
  fun snapshot(slot:Int,store:Boolean):CompletableFuture<Unit> = submit {
    require(slot in 0..1)
    if(store){if(slot==0)snapshotA=configuration else snapshotB=configuration;changed{it.copy(message="Saved temporary settings ${if(slot==0)"A" else "B"}")}}
    else {val chosen=requireNotNull(if(slot==0)snapshotA else snapshotB){"Save this settings snapshot first"};val playing=mutableView.value.playing&&!mutableView.value.recording;pauseInternal();configuration=chosen;publishConfiguration();changed{it.copy(snapshot=if(slot==0)"A" else "B")};if(playing)checkedStart(false)}
  }
  fun freezeStats(value:Boolean):CompletableFuture<Unit> = submit{stats.freeze(value);changed{it.copy(frozen=value,stats=stats.display())}}
  fun resetStats():CompletableFuture<Unit> = submit{stats.reset();changed{it.copy(message="Display window reset; native overload and session faults retained")}}
  fun applyToCalls(sections:Set<LabSection>):CompletableFuture<ApplyResult> = submit{environment.apply(configuration,sections).also{result->changed{it.copy(message=if(result.applied)"Selected settings applied to calls" else result.reason)}}}

  override fun updateDenoise(direction:Direction,settings:DenoiseSettings){submit{
    val s=settings.sanitized();configuration=if(direction==Direction.RECEIVED)configuration.copy(received=s)else configuration.copy(sent=s)
    publishConfiguration();applyLiveConfiguration()
  }}
  override fun updateEffects(settings:IncomingAudioSettings){submit{configuration=configuration.copy(effects=settings.sanitized());publishConfiguration();applyLiveConfiguration()}}
  private fun applyLiveConfiguration(){
    if(hardware!=null){ensureModels();check(requireNotNull(hardware).native.configure(toWire())>0){"Native settings rejected"}}
    changed{it.copy(snapshot="Custom")}
  }
  override fun setBypassed(direction:Direction,bypassed:Boolean){submit{hardware?.native?.command(8,direction.wireId.toLong(),if(bypassed)1 else 0)}}
  override fun retry(direction:Direction){submit{hardware?.native?.command(9,direction.wireId.toLong())}}
  override fun reset(direction:Direction)=updateDenoise(direction,DenoiseSettings())
  override fun resetEffects()=updateEffects(IncomingAudioSettings())
  override fun save(){/* Deliberately no global preference write. Explicit Apply is separate. */}
  override fun readStatus()=CallAudioStatus(measured.received.toDenoiseStatus(),measured.sent.toDenoiseStatus())
  override fun readEffectsMeters(values:FloatArray):Long {
    if(values.size!=6||hardware==null||retired.get())return -1
    measured.effectsMeters.forEachIndexed{i,v->values[i]=v};return measured.effectCallbacks
  }
  private fun LabDirectionStats.toDenoiseStatus()=DenoiseStatus(state,rate,channels,configuredDelayMs,misses.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),inputPeak,outputPeak,snr,meanMs*1000,p95Ms*1000,levelsValid,inferenceValid,bypassed)
  private fun drain(h:LabHardwareObjects){
    if(recording==null||activeMask==0)return
    for(tap in 0..4){
      if(activeMask and (1 shl tap)==0)continue
      for(block in 0 until 32){
        if(retired.get()||!environment.isCurrent())return
        val count=h.native.drain(tap,tapMeta,tapPcm);check(count>=0){"Invalid native recording tap"};if(count==0)break
        check(tapMeta[0]==1L)
        val packet=LabAudioPacket(tapMeta[1],tapMeta[2],tapMeta[3],tap,tapMeta[5].toInt(),tapMeta[6].toInt(),tapMeta[7].toInt(),tapMeta[8],tapMeta[9],tapMeta[10].toInt())
        if(packet.epoch!=measured.epoch&&measured.epoch!=0L)continue
        if(!captureAllowed.get())return
        if(!requireNotNull(recording).append(packet,tapPcm,count)){activeMask=0;h.native.command(2);changed{v->v.copy(message="Recording interrupted; retained take is marked incomplete")};return}
      }
    }
  }
  private fun poll(){
    val h=hardware?:return
    if(retired.get()||!environment.isCurrent())return
    val s=read(h);measured=s
    val v=mutableView.value
    if(v.playing){
      val route=h.route.observe()
      if(route.output?.label!=v.actualRoute||route.inputDescription!=v.inputRoute){
        changed{current->current.copy(
          actualRoute=route.output?.label?:current.actualRoute,
          inputRoute=route.inputDescription
        )}
      }
      if(route.output?.id!=v.requestedRoute||route.mode!=3||(v.recording&&(!h.route.microphonePermission()||route.silenced))){
        recording?.incomplete("Route, microphone privacy, or communication mode changed");retireHardware(false)
        changed{it.copy(message="Test stopped after route or microphone authorization changed")};return
      }
      // Route identity does not prove physical mic internals; retain known/unknown separately.
      if(v.recording&&s.epoch>0&&routeTicket!=null){
        val out=requireNotNull(route.output)
        routeState.confirm(requireNotNull(routeTicket),RouteObservation(out.route,out.id,route.inputId,s.epoch,true))
        if(route.inputDescription!=v.inputRoute){
          changed{it.copy(inputRoute=route.inputDescription)}
        }
      }
      drain(h)
      if(s.incomplete||s.dropped>0)recording?.incomplete("Native recording queue lost blocks")
      if(s.endpointState==5){fail("Native audio error ${s.error}");return}
      if(s.finished||v.recording&&environment.nowMs()-recordingStartedAt>=120000){
        val loop=v.loop&&!v.recording&&recording==null;pauseInternal();if(loop)start(false)
      }
    }
    val now=environment.nowMs()
    if(now-publishedAt>=100){
      publishedAt=now;stats.publish(s);changed{it.copy(stats=stats.display(),phase=when{it.recording->"Recording";it.playing&&s.warming->"Warming up";it.playing->"Playing";else->it.phase})}
    }
    lastPollAt=now
  }
  private fun fail(message:String){
    recording?.incomplete(message)
    retireHardware(false);changed{it.copy(message=message,phase="Stopped",busy=false)}
  }
  private fun retireHardware(permanent:Boolean):CompletionStage<Void> {
    captureAllowed.set(false)
    val future=lifetime?.revoke()?:CompletableFuture.completedFuture<Void>(null)
    retiredFactory=lifetime?.disposed()
    hardware=null;routeTicket=null
    if(permanent)routeState.revoke()else routeState.routeLost()
    recording?.let{runCatching{it.incomplete(if(permanent)"Local session ended" else "Capture or route stopped");it.finish()}}
    recording=null;activeMask=0
    measured=LabStatsSnapshot(endpointState=if(permanent)4 else 3,collectedAtMs=environment.nowMs())
    stats.publish(measured);publishConfiguration()
    changed{it.copy(playing=false,recording=false,busy=false,retired=permanent,stats=measured,phase=if(permanent)"Ended" else "Paused",actualRoute="Released")}
    return future
  }
  /** May run on a real-call control thread: no queue, writer or model wait. */
  fun preempt():CompletionStage<Void> {
    retired.set(true);captureAllowed.set(false)
    val future=lifetime?.revoke()?:CompletableFuture.completedFuture<Void>(null)
    changed{it.copy(retired=true,playing=false,recording=false,phase="Ended",message="Local test stopped; real call has priority")}
    return future
  }
  override fun close(){
    if(closed.compareAndSet(false,true)){
      preempt()
      executor.execute { runCatching{recording?.incomplete("Lab closed");recording?.finish()};store?.discardUnsaved();environment.onClose() }
      executor.shutdown()
    }
  }
  companion object {
    fun tapName(tap:Int)=listOf("Call-backend microphone input","Before sent DeepFilterNet","After sent DeepFilterNet","Playback reference","Received result").getOrElse(tap){"Unavailable tap"}
  }
}
