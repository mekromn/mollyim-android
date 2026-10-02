// SPDX-License-Identifier: AGPL-3.0-only
// All calls are control/writer-plane. No audio callback enters JNI or Rust here.
use anyhow::Result;
use jni::{EnvUnowned, objects::{JClass,JFloatArray,JLongArray,JShortArray}, sys::{jboolean,jint,jlong}};
use crate::android::error::ThrowCallException;
unsafe extern "C" {
 fn Rust_MollyMockVersion()->i32;
 fn Rust_MollyMockPrepare()->u64;
 fn Rust_MollyMockFinish(id:u64)->i32;
 fn Rust_MollyMockRevoke(id:u64);
 fn Rust_MollyMockPreempt()->i32;
 fn Rust_MollyMockRelease(id:u64)->i32;
 fn Rust_MollyMockCommand(id:u64,op:i32,a:i64,b:i64,c:i64,d:i64)->u64;
 fn Rust_MollyMockConfigure(id:u64,values:*const f32,len:usize)->u64;
 fn Rust_MollyMockLoad(id:u64,direction:i32,rate:u32,channels:u32,values:*const i16,len:usize)->u64;
 fn Rust_MollyMockStatus(id:u64,values:*mut f32,len:usize)->i32;
 fn Rust_MollyMockDrain(id:u64,tap:i32,meta:*mut i64,meta_len:usize,pcm:*mut f32,pcm_len:usize)->i32;
}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_signal_ringrtc_MockCallSession_nativeVersion(_e:EnvUnowned,_c:JClass)->jint{unsafe{Rust_MollyMockVersion()}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_signal_ringrtc_MockCallSession_nativePrepare(_e:EnvUnowned,_c:JClass)->jlong{unsafe{Rust_MollyMockPrepare() as jlong}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_signal_ringrtc_MockCallSession_nativeFinish(_e:EnvUnowned,_c:JClass,id:jlong)->jboolean{unsafe{(id>0&&Rust_MollyMockFinish(id as u64)!=0)as jboolean}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_signal_ringrtc_MockCallSession_nativeRevoke(_e:EnvUnowned,_c:JClass,id:jlong){if id>0{unsafe{Rust_MollyMockRevoke(id as u64)}}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_signal_ringrtc_MockCallSession_nativePreempt(_e:EnvUnowned,_c:JClass)->jboolean{unsafe{(Rust_MollyMockPreempt()!=0)as jboolean}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_signal_ringrtc_MockCallSession_nativeRelease(_e:EnvUnowned,_c:JClass,id:jlong)->jboolean{unsafe{(id>0&&Rust_MollyMockRelease(id as u64)!=0)as jboolean}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_signal_ringrtc_MockCallSession_nativeCommand(_e:EnvUnowned,_c:JClass,id:jlong,op:jint,a:jlong,b:jlong,c:jlong,d:jlong)->jlong{if id<=0{return 0;}unsafe{Rust_MollyMockCommand(id as u64,op,a,b,c,d)as jlong}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub unsafe extern "C" fn Java_org_signal_ringrtc_MockCallSession_nativeConfigure(mut e:EnvUnowned,_c:JClass,id:jlong,values:JFloatArray)->jlong{
 e.with_env(|env|->Result<jlong>{
  if id<=0||values.len(env)?!=41{return Ok(0);}
  let mut data=[0f32;41];values.get_region(env,0,&mut data)?;
  Ok(unsafe{Rust_MollyMockConfigure(id as u64,data.as_ptr(),data.len())as jlong})
 }).resolve::<ThrowCallException>()
}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub unsafe extern "C" fn Java_org_signal_ringrtc_MockCallSession_nativeLoad(mut e:EnvUnowned,_c:JClass,id:jlong,direction:jint,rate:jint,channels:jint,values:JShortArray)->jlong{
 e.with_env(|env|->Result<jlong>{
  let len=values.len(env)?;
  if id<=0||direction<0||direction>1||!(rate==8000||rate==16000||rate==32000||rate==48000)||!(channels==1||channels==2)||len<=0||len as usize>(rate as usize)*(channels as usize)*120{return Ok(0);}
  let mut data=vec![0i16;len as usize];values.get_region(env,0,&mut data)?;
  Ok(unsafe{Rust_MollyMockLoad(id as u64,direction,rate as u32,channels as u32,data.as_ptr(),data.len())as jlong})
 }).resolve::<ThrowCallException>()
}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub unsafe extern "C" fn Java_org_signal_ringrtc_MockCallSession_nativeStatus(mut e:EnvUnowned,_c:JClass,id:jlong,values:JFloatArray)->jboolean{
 e.with_env(|env|->Result<jboolean>{
  if id<=0||values.len(env)?!=128{return Ok(false as jboolean);}
  let mut data=[0f32;128];if unsafe{Rust_MollyMockStatus(id as u64,data.as_mut_ptr(),data.len())}==0{return Ok(false as jboolean);}
  values.set_region(env,0,&data)?;Ok(true as jboolean)
 }).resolve::<ThrowCallException>()
}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub unsafe extern "C" fn Java_org_signal_ringrtc_MockCallSession_nativeDrain(mut e:EnvUnowned,_c:JClass,id:jlong,tap:jint,metadata:JLongArray,pcm:JFloatArray)->jint{
 e.with_env(|env|->Result<jint>{
  if id<=0||tap<0||tap>4||metadata.len(env)?!=16||pcm.len(env)?!=3840{return Ok(-1);}
  let mut meta=[0i64;16];let mut data=[0f32;3840];
  let n=unsafe{Rust_MollyMockDrain(id as u64,tap,meta.as_mut_ptr(),meta.len(),data.as_mut_ptr(),data.len())};
  if n>0&&n<=3840{metadata.set_region(env,0,&meta)?;pcm.set_region(env,0,&data[..n as usize])?;}
  Ok(n)
 }).resolve::<ThrowCallException>()
}
