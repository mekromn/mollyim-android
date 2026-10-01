/* SPDX-License-Identifier: AGPL-3.0-only */
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct DfConfig {
    pub abi_version: u32,
    pub attenuation_db: f32,
    pub post_filter_enabled: u32,
    pub beta: f32,
    pub min_snr: f32,
    pub erb_snr: f32,
    pub df_snr: f32,
    pub reserved: u32,
}
impl Default for DfConfig {
    fn default() -> Self {
        Self { abi_version: 1, attenuation_db: 30., post_filter_enabled: 0, beta: 0.02,
               min_snr: -10., erb_snr: 30., df_snr: 20., reserved: 0 }
    }
}
impl DfConfig {
    pub fn valid(&self) -> bool {
        self.abi_version == 1 && self.reserved == 0 && self.post_filter_enabled <= 1
        && [self.attenuation_db,self.beta,self.min_snr,self.erb_snr,self.df_snr].iter().all(|v| v.is_finite())
        && (0. ..=100.).contains(&self.attenuation_db) && (0. ..=0.05).contains(&self.beta)
        && (-30. ..=60.).contains(&self.min_snr) && self.min_snr <= self.df_snr
        && self.df_snr <= self.erb_snr && self.erb_snr <= 60.
    }
    pub fn effective_beta(&self) -> f32 { if self.post_filter_enabled == 0 { 0. } else { self.beta } }
}
#[repr(C)]
#[derive(Clone, Copy, Default, Debug)]
pub struct DfMeta {
    pub abi_version: u32,
    pub sample_rate: u32,
    pub hop: u32,
    pub fft: u32,
    pub lookahead: u32,
    pub df_order: u32,
    pub intrinsic_delay: u32,
}
const _: [();32] = [();std::mem::size_of::<DfConfig>()];
const _: [();28] = [();std::mem::size_of::<DfMeta>()];
