/* SPDX-License-Identifier: AGPL-3.0-only */
use crate::{DfConfig,DfMeta,Error};
use df::tract::{DfParams,DfTract,RuntimeParams,ReduceMask};
use ndarray::{ArrayView2,ArrayViewMut2};
use sha2::{Digest,Sha256};

pub struct Engine {
    params: DfParams,
    model: Option<DfTract>,
    config: DfConfig,
    pub meta: DfMeta,
}
impl Engine {
    pub fn new(bytes: &[u8], config: DfConfig) -> Result<Self,Error> {
        if !config.valid() { return Err(Error::Invalid); }
        // Only the audited bundled archives enter the graph loader. Hash before parsing.
        let hash = format!("{:x}", Sha256::digest(bytes));
        let lookahead = match (bytes.len(),hash.as_str()) {
            (7_983_136,"c94d91f70911001c946e0fabb4aa9adc37045f45a03b56008cb0c8244cb63616") => 2,
            (36_359_660,"5998e58e8ba0e09bb76986ef97b84afa065a571ef282d4a1222f341e3251cf3a") => 0,
            _ => return Err(Error::Model),
        };
        let params = DfParams::from_bytes(bytes).map_err(|_|Error::Model)?;
        let model = DfTract::new(params.clone(), &Self::runtime_params(config)).map_err(|_|Error::Model)?;
        if model.sr!=48000 || model.hop_size!=480 || model.fft_size!=960 || model.ch!=1 || model.lookahead!=lookahead
            || model.df_order!=5 || model.nb_erb!=32 || model.nb_df!=96 {
            return Err(Error::Unsupported);
        }
        let meta=DfMeta { abi_version:1,sample_rate:48000,hop:480,fft:960,lookahead:lookahead as u32,df_order:5,intrinsic_delay:480+480*lookahead as u32 };
        Ok(Self {params,model:Some(model),config,meta})
    }
    fn runtime_params(c: DfConfig) -> RuntimeParams {
        RuntimeParams::new(1,c.effective_beta(),c.attenuation_db,c.min_snr,c.erb_snr,c.df_snr,ReduceMask::NONE)
    }
    pub fn configure(&mut self, config: DfConfig) -> Result<(),Error> {
        if !config.valid() { return Err(Error::Invalid); }
        let m=self.model.as_mut().ok_or(Error::Poisoned)?;
        m.set_atten_lim(config.attenuation_db);
        m.set_pf_beta(config.effective_beta());
        m.min_db_thresh=config.min_snr;m.max_db_erb_thresh=config.erb_snr;m.max_db_df_thresh=config.df_snr;
        self.config=config;Ok(())
    }
    pub fn process(&mut self, input: &[f32], output: &mut [f32]) -> Result<f32,Error> {
        if input.len()!=480 || output.len()!=480 {return Err(Error::Invalid);}
        if !input.iter().all(|x|x.is_finite()) {output.fill(0.);return Err(Error::Audio);}
        let m=self.model.as_mut().ok_or(Error::Poisoned)?;
        let input=ArrayView2::from_shape((1,480),input).map_err(|_|Error::Invalid)?;
        let mut output=ArrayViewMut2::from_shape((1,480),output).map_err(|_|Error::Invalid)?;
        let snr=m.process(input,output.view_mut()).map_err(|_|Error::Model)?;
        if !snr.is_finite() || !output.iter().all(|x|x.is_finite()) {output.fill(0.);return Err(Error::Audio);}
        Ok(snr)
    }
    pub fn reset(&mut self) -> Result<(),Error> {
        // Release the old context before constructing its replacement: no doubled live model state.
        self.clear_audio();self.model=None;
        self.model=Some(DfTract::new(self.params.clone(),&Self::runtime_params(self.config)).map_err(|_|Error::Model)?);
        Ok(())
    }
    pub fn clear_audio(&mut self) {
        if let Some(model)=&mut self.model {for state in &mut model.df_states {state.reset();}}
    }
}
impl Drop for Engine { fn drop(&mut self) { self.clear_audio(); } }
