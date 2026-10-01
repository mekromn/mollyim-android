use molly_deepfilter::*;
use std::{fs, ptr, path::PathBuf};

fn model_bytes(low: bool) -> Vec<u8> {
    let dir=PathBuf::from(std::env::var("DF_TEST_MODELS").expect("Real model fixtures required"));
    fs::read(dir.join(if low {"DeepFilterNet3_ll_onnx.tar.gz"} else {"DeepFilterNet3_onnx.tar.gz"})).unwrap()
}
unsafe fn create(low: bool, config: DfConfig) -> (*mut DfHandle, DfMeta) {
    let bytes=model_bytes(low); let mut handle=ptr::null_mut(); let mut meta=DfMeta::default();
    assert_eq!(molly_df_create(bytes.as_ptr(),bytes.len(),&config,&mut handle,&mut meta),0);
    assert!(!handle.is_null()); (handle,meta)
}
#[test]
fn abi_and_invalid_arguments() { unsafe {
    assert_eq!(std::mem::size_of::<DfConfig>(),32); assert_eq!(std::mem::size_of::<DfMeta>(),28);
    assert_eq!(molly_df_abi_version(),1);
    let mut h=ptr::null_mut(); let mut m=DfMeta::default(); let c=DfConfig::default();
    assert_eq!(molly_df_create(ptr::null(),0,&c,&mut h,&mut m),1);
    assert!(h.is_null());
    let bad=[0u8;40]; assert_eq!(molly_df_create(bad.as_ptr(),bad.len(),&c,&mut h,&mut m),2);
    assert!(h.is_null());
    let invalid=DfConfig {abi_version:99,..c};
    assert_eq!(molly_df_create(bad.as_ptr(),bad.len(),&invalid,&mut h,&mut m),1);
    assert_eq!(molly_df_configure(ptr::null_mut(),&c),1);
    assert_eq!(molly_df_reset(ptr::null_mut()),1);
    molly_df_destroy(ptr::null_mut());
}}
#[test]
fn both_real_models_metadata_and_finite_output() { unsafe {
    for low in [false,true] {
        let (h,m)=create(low,DfConfig::default());
        assert_eq!((m.sample_rate,m.hop,m.fft,m.lookahead,m.df_order,m.intrinsic_delay),(48000,480,960,if low {0}else{2},5,if low{480}else{1440}));
        let mut out=[0f32;480]; let mut snr=f32::NAN;
        for n in 0..24 {
            let input:Vec<f32>=(0..480).map(|i| 0.2*((i+n*480) as f32*0.071).sin()+0.02*((i+n*480) as f32*1.413).cos()).collect();
            assert_eq!(molly_df_process(h,input.as_ptr(),480,out.as_mut_ptr(),480,&mut snr),0);
            assert!(out.iter().all(|v|v.is_finite())); assert!(snr.is_finite());
        }
        assert!(out.iter().any(|v|v.abs()>1e-5));
        assert_eq!(molly_df_reset(h),0);
        molly_df_destroy(h);
    }
}}
#[test]
fn invalid_audio_sizes_and_configuration_do_not_escape() { unsafe {
    let (h,_)=create(false,DfConfig::default()); let input=[0.1f32;480]; let mut out=[7f32;482]; let mut snr=0f32;
    assert_eq!(molly_df_process(h,input.as_ptr(),479,out.as_mut_ptr(),480,&mut snr),1);
    assert_eq!(out[480],7f32); assert_eq!(out[481],7f32);
    let mut input=input; input[47]=f32::NAN;
    assert_eq!(molly_df_process(h,input.as_ptr(),480,out.as_mut_ptr(),480,&mut snr),5);
    assert!(out[..480].iter().all(|x|*x==0.));
    let bad=DfConfig {min_snr:40.,df_snr:20.,..DfConfig::default()};
    assert_eq!(molly_df_configure(h,&bad),1);
    let bad=DfConfig {beta:f32::INFINITY,..DfConfig::default()};
    assert_eq!(molly_df_configure(h,&bad),1);
    input[47]=0.1;
    assert_eq!(molly_df_process(h,input.as_ptr(),480,out.as_mut_ptr(),480,&mut snr),0);
    assert_eq!(molly_df_process(h,out.as_ptr(),480,out.as_mut_ptr(),480,&mut snr),1);
    molly_df_destroy(h);
}}
#[test]
fn independent_streams_and_reset_remove_prior_samples() { unsafe {
    let config=DfConfig {min_snr:-30.,erb_snr:-30.,df_snr:-30.,attenuation_db:100.,..DfConfig::default()};
    let (a,_)=create(false,config); let (b,_)=create(false,config); let (fresh,_)=create(false,config);
    let input=[0.2f32;480]; let silence=[0f32;480]; let mut out_a=[0f32;480];let mut out_b=[0f32;480];let mut out_f=[0f32;480];let mut snr=0.;
    for _ in 0..12 {
        assert_eq!(molly_df_process(a,input.as_ptr(),480,out_a.as_mut_ptr(),480,&mut snr),0);
        assert_eq!(molly_df_process(b,silence.as_ptr(),480,out_b.as_mut_ptr(),480,&mut snr),0);
        assert!(out_b.iter().all(|x|x.abs()<1e-8));
    }
    assert_eq!(molly_df_reset(a),0);
    for _ in 0..12 {
        assert_eq!(molly_df_process(a,silence.as_ptr(),480,out_a.as_mut_ptr(),480,&mut snr),0);
        assert_eq!(molly_df_process(fresh,silence.as_ptr(),480,out_f.as_mut_ptr(),480,&mut snr),0);
        assert_eq!(out_a,out_f);
    }
    molly_df_destroy(a);molly_df_destroy(b);molly_df_destroy(fresh);
}}
#[test]
fn actual_model_intrinsic_delay_matches_impulse() { unsafe {
    let config=DfConfig {min_snr:-30.,erb_snr:-30.,df_snr:-30.,attenuation_db:100.,..DfConfig::default()};
    for low in [false,true] {
        let (h,meta)=create(low,config);
        let mut result=Vec::new();let mut snr=0.;
        for frame in 0..24 {
            let mut input=[0.001f32;480];if frame==6 {input[230]=0.5;}
            let mut output=[0f32;480];assert_eq!(molly_df_process(h,input.as_ptr(),480,output.as_mut_ptr(),480,&mut snr),0);
            result.extend(output);
        }
        let peak=result.iter().enumerate().max_by(|(_,a),(_,b)|a.abs().partial_cmp(&b.abs()).unwrap()).unwrap().0;
        assert_eq!(peak,6*480+230+meta.intrinsic_delay as usize);
        assert!((result[peak]-0.5).abs()<1e-4);
        molly_df_destroy(h);
    }
}}
