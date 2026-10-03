// SPDX-License-Identifier: AGPL-3.0-only
// JNI is control-plane only. Audio never enters Java or this module.
use std::ffi::{c_char, CString};
use anyhow::Result;
use jni::{EnvUnowned, objects::{JClass, JString, JFloatArray}, sys::{jboolean,jint,jlong,jfloat}};
use crate::android::error::ThrowCallException;
unsafe extern "C" {
    fn Rust_MollyCallDenoiseVersion() -> i32;
    fn Rust_MollyCallDenoiseBeginFactory() -> u64;
    fn Rust_MollyCallDenoiseFinishFactory(token:u64) -> i32;
    fn Rust_MollyCallDenoiseNewOwner() -> u64;
    fn Rust_MollyCallDenoiseBegin(owner:u64,factory:u64) -> i32;
    fn Rust_MollyCallDenoiseEnd(owner:u64) -> i32;
    fn Rust_MollyCallDenoiseGate(owner:u64,allowed:i32,reason:i32) -> i32;
    fn Rust_MollyCallDenoiseInvalidate(owner:u64,reason:i32) -> i32;
    fn Rust_MollyCallDenoisePaths(library:*const c_char,library_len:usize,standard:*const c_char,standard_len:usize,low_latency:*const c_char,low_latency_len:usize,mobile_fused:*const c_char,mobile_fused_len:usize) -> i32;
    fn Rust_MollyCallDenoiseConfigure(direction:i32,enabled:i32,model:i32,attenuation:f32,post_filter:i32,beta:f32,minimum:f32,erb:f32,df:f32) -> i32;
    fn Rust_MollyCallDenoiseBypass(direction:i32,bypass:i32);
    fn Rust_MollyCallDenoiseRetry(direction:i32);
    fn Rust_MollyCallDenoiseStatus(direction:i32,values:*mut f32,length:usize) -> i32;
}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_signal_ringrtc_CallDenoiseGate_nativeBeginFactory(_e:EnvUnowned,_c:JClass)->jlong{unsafe{Rust_MollyCallDenoiseBeginFactory() as jlong}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_signal_ringrtc_CallDenoiseGate_nativeFinishFactory(_e:EnvUnowned,_c:JClass,t:jlong)->jboolean{unsafe{(Rust_MollyCallDenoiseFinishFactory(t as u64)!=0) as jboolean}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_signal_ringrtc_CallDenoiseGate_nativeNewOwner(_e:EnvUnowned,_c:JClass)->jlong{unsafe{Rust_MollyCallDenoiseNewOwner() as jlong}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_signal_ringrtc_CallDenoiseGate_nativeBegin(_e:EnvUnowned,_c:JClass,o:jlong,f:jlong)->jboolean{unsafe{(Rust_MollyCallDenoiseBegin(o as u64,f as u64)!=0) as jboolean}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_signal_ringrtc_CallDenoiseGate_nativeEnd(_e:EnvUnowned,_c:JClass,o:jlong)->jboolean{unsafe{(Rust_MollyCallDenoiseEnd(o as u64)!=0) as jboolean}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_signal_ringrtc_CallDenoiseGate_nativeGate(_e:EnvUnowned,_c:JClass,o:jlong,a:jboolean,r:jint)->jboolean{unsafe{(Rust_MollyCallDenoiseGate(o as u64,a as i32,r)!=0) as jboolean}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_signal_ringrtc_CallDenoiseGate_nativeInvalidate(_e:EnvUnowned,_c:JClass,o:jlong,r:jint)->jboolean{unsafe{(Rust_MollyCallDenoiseInvalidate(o as u64,r)!=0) as jboolean}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_thoughtcrime_securesms_webrtc_audio_CallDenoiseBridge_nativeVersion(_e:EnvUnowned,_c:JClass)->jint{unsafe{Rust_MollyCallDenoiseVersion()}}
#[unsafe(no_mangle)] #[allow(non_snake_case,clippy::too_many_arguments)]
pub extern "C" fn Java_org_thoughtcrime_securesms_webrtc_audio_CallDenoiseBridge_nativeApply(_e:EnvUnowned,_c:JClass,d:jint,en:jboolean,m:jint,a:jfloat,p:jboolean,b:jfloat,min:jfloat,erb:jfloat,df:jfloat)->jboolean{unsafe{(Rust_MollyCallDenoiseConfigure(d,en as i32,m,a,p as i32,b,min,erb,df)!=0) as jboolean}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_thoughtcrime_securesms_webrtc_audio_CallDenoiseBridge_nativeBypass(_e:EnvUnowned,_c:JClass,d:jint,b:jboolean){unsafe{Rust_MollyCallDenoiseBypass(d,b as i32)}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub extern "C" fn Java_org_thoughtcrime_securesms_webrtc_audio_CallDenoiseBridge_nativeRetry(_e:EnvUnowned,_c:JClass,d:jint){unsafe{Rust_MollyCallDenoiseRetry(d)}}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub unsafe extern "C" fn Java_org_thoughtcrime_securesms_webrtc_audio_CallDenoiseBridge_nativePaths(mut e:EnvUnowned,_c:JClass,library:JString,standard:JString,low_latency:JString,mobile_fused:JString)->jboolean{
    e.with_env(|env|->Result<jboolean>{
        let library=CString::new(library.try_to_string(env)?)?;
        let standard=CString::new(standard.try_to_string(env)?)?;
        let low_latency=CString::new(low_latency.try_to_string(env)?)?;
        let mobile_fused=CString::new(mobile_fused.try_to_string(env)?)?;
        Ok((unsafe{Rust_MollyCallDenoisePaths(library.as_ptr(),library.as_bytes().len(),standard.as_ptr(),standard.as_bytes().len(),low_latency.as_ptr(),low_latency.as_bytes().len(),mobile_fused.as_ptr(),mobile_fused.as_bytes().len())}!=0) as jboolean)
    }).resolve::<ThrowCallException>()
}
#[unsafe(no_mangle)] #[allow(non_snake_case)]
pub unsafe extern "C" fn Java_org_thoughtcrime_securesms_webrtc_audio_CallDenoiseBridge_nativeStatus(mut e:EnvUnowned,_c:JClass,d:jint,values:JFloatArray)->jboolean{
    e.with_env(|env|->Result<jboolean>{
        if values.len(env)? != 16 {return Ok(false as jboolean);}
        let mut fields=[0f32;16];
        if unsafe{Rust_MollyCallDenoiseStatus(d,fields.as_mut_ptr(),fields.len())}==0{return Ok(false as jboolean);}
        values.set_region(env,0,&fields)?;
        Ok(true as jboolean)
    }).resolve::<ThrowCallException>()
}
