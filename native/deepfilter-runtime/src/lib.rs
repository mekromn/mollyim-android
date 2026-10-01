/* SPDX-License-Identifier: AGPL-3.0-only */
//! Worker-only C boundary. Calls for one handle must be serialized by its owner.
//! Catchable Rust panics never unwind across C. Invalid/dangling foreign allocations,
//! OOM and process abort are not recoverable failures.
mod engine;
mod settings;
pub use settings::{DfConfig,DfMeta};
use engine::Engine;
use std::{mem,ptr,slice,panic::{catch_unwind,AssertUnwindSafe}};

#[derive(Clone, Copy, Debug)]
#[repr(i32)]
pub enum Error { Invalid=1,Model=2,Poisoned=3,Unsupported=4,Audio=5 }
pub struct DfHandle { engine: Engine, poisoned: bool }
fn aligned<T>(p: *const T) -> bool { !p.is_null() && (p as usize)%mem::align_of::<T>()==0 }
fn overlap(a: usize, alen: usize, b: usize, blen: usize) -> bool {
    match (a.checked_add(alen),b.checked_add(blen)) { (Some(ae),Some(be))=> a<be && b<ae, _=>true }
}
fn handle_call(h: &mut DfHandle, operation: impl FnOnce(&mut Engine)->Result<(),Error>) -> i32 {
    if h.poisoned { return Error::Poisoned as i32; }
    match catch_unwind(AssertUnwindSafe(||operation(&mut h.engine))) {
        Ok(Ok(()))=>0,
        Ok(Err(e))=>{if matches!(e,Error::Model|Error::Audio|Error::Poisoned) {h.poisoned=true;} e as i32},
        Err(_)=>{h.poisoned=true;Error::Poisoned as i32}
    }
}
#[no_mangle]
pub extern "C" fn molly_df_abi_version() -> u32 {1}

/// # Safety
/// All non-null pointers must reference live, aligned allocations of the specified
/// sizes. Output pointers must be distinct from each other and the model/config.
#[no_mangle]
pub unsafe extern "C" fn molly_df_create(bytes:*const u8,len:usize,config:*const DfConfig,out_handle:*mut *mut DfHandle,out_meta:*mut DfMeta)->i32 {
    if !aligned(out_handle) || !aligned(out_meta) {return Error::Invalid as i32;}
    *out_handle=ptr::null_mut();*out_meta=DfMeta::default();
    if bytes.is_null() || len==0 || len>40*1024*1024 || !aligned(config) || !(*config).valid() {return Error::Invalid as i32;}
    match catch_unwind(AssertUnwindSafe(||Engine::new(slice::from_raw_parts(bytes,len),*config))) {
        Ok(Ok(engine))=>{
            *out_meta=engine.meta;*out_handle=Box::into_raw(Box::new(DfHandle{engine,poisoned:false}));0
        },
        Ok(Err(e))=>e as i32,
        Err(_)=>Error::Poisoned as i32
    }
}
/// # Safety
/// A valid live handle created by molly_df_create, called only by its owning worker.
#[no_mangle]
pub unsafe extern "C" fn molly_df_configure(handle:*mut DfHandle,config:*const DfConfig)->i32 {
    if !aligned(handle) || !aligned(config) || !(*config).valid() {return Error::Invalid as i32;}
    handle_call(&mut *handle,|engine|engine.configure(*config))
}
/// # Safety
/// Live owned handle; non-overlapping input/output/SNR allocations; exactly one
/// model hop of normalized mono float PCM. Never call concurrently for a handle.
#[no_mangle]
pub unsafe extern "C" fn molly_df_process(handle:*mut DfHandle,input:*const f32,input_len:usize,output:*mut f32,output_len:usize,out_snr:*mut f32)->i32 {
    if !aligned(handle)||!aligned(input)||!aligned(output)||!aligned(out_snr)||input_len!=480||output_len!=480
        ||overlap(input as usize,480*4,output as usize,480*4)
        ||overlap(output as usize,480*4,out_snr as usize,4)
        ||overlap(input as usize,480*4,out_snr as usize,4) {return Error::Invalid as i32;}
    *out_snr=f32::NAN;
    let input=slice::from_raw_parts(input,input_len);let output=slice::from_raw_parts_mut(output,output_len);
    output.fill(0.);
    if !input.iter().all(|x|x.is_finite()) {return Error::Audio as i32;}
    let result=handle_call(&mut *handle,|engine|{
        *out_snr=engine.process(input,output)?;Ok(())
    });
    if result!=0 { output.fill(0.);*out_snr=f32::NAN; }
    result
}
/// # Safety
/// Live owned handle, no concurrent calls. May allocate and is never an audio-callback operation.
#[no_mangle]
pub unsafe extern "C" fn molly_df_reset(handle:*mut DfHandle)->i32 {
    if !aligned(handle) {return Error::Invalid as i32;}
    let h=&mut *handle;
    match catch_unwind(AssertUnwindSafe(||h.engine.reset())) {
        Ok(Ok(()))=>{h.poisoned=false;0},
        Ok(Err(e))=>{h.poisoned=true;e as i32},
        Err(_)=>{h.poisoned=true;Error::Poisoned as i32}
    }
}
/// # Safety
/// Null or an owned live handle not previously destroyed; worker-only and serialized.
#[no_mangle]
pub unsafe extern "C" fn molly_df_destroy(handle:*mut DfHandle) {
    if !aligned(handle) {return;}
    let _=catch_unwind(AssertUnwindSafe(||drop(Box::from_raw(handle))));
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn catchable_panic_poison_and_explicit_reset() {
        let path=std::path::PathBuf::from(std::env::var("DF_TEST_MODELS").unwrap()).join("DeepFilterNet3_onnx.tar.gz");
        let bytes=std::fs::read(path).unwrap();
        let engine=Engine::new(&bytes,DfConfig::default()).unwrap();let mut h=DfHandle{engine,poisoned:false};
        assert_eq!(handle_call(&mut h,|_|panic!("Injected worker panic")),3);
        assert_eq!(handle_call(&mut h,|_|Ok(())),3);
        assert_eq!(unsafe{molly_df_reset(&mut h)},0);
        assert_eq!(handle_call(&mut h,|_|Ok(())),0);
    }
    #[test]
    fn disabled_post_filter_has_zero_effective_beta() {
        assert_eq!(DfConfig::default().effective_beta(),0.);
        assert_eq!(DfConfig{post_filter_enabled:1,..DfConfig::default()}.effective_beta(),0.02);
        assert!(!DfConfig{abi_version:2,..DfConfig::default()}.valid());
        assert!(!DfConfig{post_filter_enabled:2,..DfConfig::default()}.valid());
    }
}
