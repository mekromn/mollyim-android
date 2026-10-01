//! Independent upstream Rust/tract driver; never calls our wrapper to obtain reference samples.
use df::tract::{DfParams,DfTract,RuntimeParams,ReduceMask};
use molly_deepfilter::*;
use ndarray::{ArrayView2,ArrayViewMut2};
use std::{fs,ptr,path::PathBuf};
#[test]
fn both_models_match_unmodified_upstream_on_real_noisy_speech() { unsafe {
    let dir=PathBuf::from(std::env::var("DF_TEST_MODELS").expect("Real model directory required"));
    let fixture=std::env::var("DF_TEST_FIXTURE").expect("Real noisy speech fixture required");
    let mut reader=hound::WavReader::open(&fixture).unwrap();
    assert_eq!(reader.spec().sample_rate,48000);assert_eq!(reader.spec().channels,1);
    let samples:Vec<f32>=match reader.spec().sample_format {
        hound::SampleFormat::Float => reader.samples::<f32>().map(Result::unwrap).collect(),
        hound::SampleFormat::Int => { let scale=2f32.powi(reader.spec().bits_per_sample as i32-1);reader.samples::<i32>().map(|v|v.unwrap() as f32/scale).collect() }
    };
    assert!(samples.len()>=480*100);
    for file in ["DeepFilterNet3_onnx.tar.gz","DeepFilterNet3_ll_onnx.tar.gz"] {
        let bytes=fs::read(dir.join(file)).unwrap();
        let c=DfConfig::default();
        let mut h=ptr::null_mut();let mut meta=DfMeta::default();
        assert_eq!(molly_df_create(bytes.as_ptr(),bytes.len(),&c,&mut h,&mut meta),0);
        let params=DfParams::from_bytes(&bytes).unwrap();
        let rp=RuntimeParams::new(1,0.,30.,-10.,30.,20.,ReduceMask::NONE);
        let mut reference=DfTract::new(params,&rp).unwrap();
        let mut max_error=0f32;let mut changed=0f32;let mut output_all=Vec::new();
        for block in samples[..480*100].chunks_exact(480) {
            let mut expected=[0f32;480];let mut actual=[0f32;480];let mut snr=f32::NAN;
            let expected_snr=reference.process(ArrayView2::from_shape((1,480),block).unwrap(),ArrayViewMut2::from_shape((1,480),&mut expected).unwrap()).unwrap();
            assert_eq!(molly_df_process(h,block.as_ptr(),480,actual.as_mut_ptr(),480,&mut snr),0);
            for (a,b) in actual.iter().zip(expected.iter()) { assert!(a.is_finite());max_error=max_error.max((a-b).abs()); }
            assert!((snr-expected_snr).abs()<=1e-5);output_all.extend(actual);
        }
        for (out,input) in output_all[meta.intrinsic_delay as usize..].iter().zip(&samples) { changed=changed.max((out-input).abs()); }
        assert!(max_error<=1e-5,"{file}: error {max_error}");assert!(changed>1e-4,"A dry passthrough is not denoising");
        println!("REAL MODEL {file}: max reference error={max_error}, changed amplitude={changed}, intrinsic delay={}",meta.intrinsic_delay);
        molly_df_destroy(h);
    }
}}
