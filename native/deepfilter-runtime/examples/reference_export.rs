//! CI-only golden samples generated directly by unmodified upstream, never our wrapper.
use df::tract::{DfParams,DfTract,RuntimeParams,ReduceMask};
use ndarray::{ArrayView2,ArrayViewMut2};
use std::{fs,path::PathBuf};
fn write(path:PathBuf, samples:&[f32]) {fs::write(path,samples.iter().flat_map(|s|s.to_le_bytes()).collect::<Vec<_>>()).unwrap();}
fn main() {
 let models=PathBuf::from(std::env::var("DF_TEST_MODELS").unwrap());
 let mut wav=hound::WavReader::open(std::env::var("DF_TEST_FIXTURE").unwrap()).unwrap();
 assert_eq!(wav.spec().sample_rate,48000);assert_eq!(wav.spec().channels,1);
 let samples:Vec<f32>=match wav.spec().sample_format {
  hound::SampleFormat::Float=>wav.samples::<f32>().map(Result::unwrap).take(48000).collect(),
  hound::SampleFormat::Int=>{let scale=2f32.powi(wav.spec().bits_per_sample as i32-1);wav.samples::<i32>().map(|v|v.unwrap() as f32/scale).take(48000).collect()}
 };
 assert_eq!(samples.len(),48000);
 let out=PathBuf::from(std::env::args().nth(1).unwrap());fs::create_dir_all(&out).unwrap();
 write(out.join("input.f32"),&samples);
 for (file,name) in [("DeepFilterNet3_onnx.tar.gz","standard.f32"),("DeepFilterNet3_ll_onnx.tar.gz","low_latency.f32")] {
  let bytes=fs::read(models.join(file)).unwrap();
  let params=DfParams::from_bytes(&bytes).unwrap();
  let mut model=DfTract::new(params,&RuntimeParams::new(1,0.,30.,-10.,30.,20.,ReduceMask::NONE)).unwrap();
  let mut result=Vec::new();
  for input in samples.chunks_exact(480){let mut output=[0f32;480];model.process(ArrayView2::from_shape((1,480),input).unwrap(),ArrayViewMut2::from_shape((1,480),&mut output).unwrap()).unwrap();result.extend(output);}
  write(out.join(name),&result);
 }
}
