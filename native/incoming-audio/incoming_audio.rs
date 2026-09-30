// SPDX-License-Identifier: AGPL-3.0-only
// Control-plane JNI only: no Java calls from the realtime receive callback.
use jni::{EnvUnowned, objects::JClass, sys::{jboolean, jfloat, jint, jlong}};
unsafe extern "C" {
    fn Rust_MollyIncomingAudioVersion() -> i32;
    fn Rust_MollyIncomingAudioConfigure(values: *const f32, length: usize) -> i32;
    fn Rust_MollyIncomingAudioReset();
    fn Rust_MollyIncomingAudioMeter(index: i32) -> f32;
    fn Rust_MollyIncomingAudioFrames() -> u32;
}
#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub extern "C" fn Java_org_thoughtcrime_securesms_webrtc_audio_IncomingAudioBridge_nativeVersion(_env: EnvUnowned, _class: JClass) -> jint {
    unsafe { Rust_MollyIncomingAudioVersion() }
}
#[unsafe(no_mangle)]
#[allow(non_snake_case, clippy::too_many_arguments)]
pub extern "C" fn Java_org_thoughtcrime_securesms_webrtc_audio_IncomingAudioBridge_nativeApply(
    _env: EnvUnowned, _class: JClass,
    p0: jfloat, p1: jfloat, p2: jfloat, p3: jfloat,
    p4: jfloat, p5: jfloat, p6: jfloat, p7: jfloat,
    p8: jfloat, p9: jfloat, p10: jfloat, p11: jfloat,
    p12: jfloat, p13: jfloat, p14: jfloat, p15: jfloat,
    p16: jfloat, p17: jfloat, p18: jfloat, p19: jfloat,
    p20: jfloat, p21: jfloat, p22: jfloat, p23: jfloat,
) -> jboolean {
    let values = [p0,p1,p2,p3,p4,p5,p6,p7,p8,p9,p10,p11,p12,p13,p14,p15,p16,p17,p18,p19,p20,p21,p22,p23];
    unsafe { (Rust_MollyIncomingAudioConfigure(values.as_ptr(), values.len()) != 0) as jboolean }
}
#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub extern "C" fn Java_org_thoughtcrime_securesms_webrtc_audio_IncomingAudioBridge_nativeReset(_env: EnvUnowned, _class: JClass) {
    unsafe { Rust_MollyIncomingAudioReset() }
}
#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub extern "C" fn Java_org_thoughtcrime_securesms_webrtc_audio_IncomingAudioBridge_nativeMeter(_env: EnvUnowned, _class: JClass, index: jint) -> jfloat {
    unsafe { Rust_MollyIncomingAudioMeter(index) }
}
#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub extern "C" fn Java_org_thoughtcrime_securesms_webrtc_audio_IncomingAudioBridge_nativeFrames(_env: EnvUnowned, _class: JClass) -> jlong {
    unsafe { Rust_MollyIncomingAudioFrames() as jlong }
}
