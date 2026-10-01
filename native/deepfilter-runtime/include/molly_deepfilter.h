/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_DEEPFILTER_H_
#define MOLLY_DEEPFILTER_H_
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#define MOLLY_DF_NOEXCEPT noexcept
#else
#define MOLLY_DF_NOEXCEPT
#endif

typedef struct DfConfig {
  uint32_t abi_version;
  float attenuation_db;
  uint32_t post_filter_enabled;
  float beta;
  float min_snr;
  float erb_snr;
  float df_snr;
  uint32_t reserved;
} DfConfig;
typedef struct DfMeta {
  uint32_t abi_version, sample_rate, hop, fft, lookahead, df_order, intrinsic_delay;
} DfMeta;
typedef struct DfHandle DfHandle;
/* A handle belongs to one worker. Never share mutable state or call concurrently.
 * Return codes: 0 success, 1 invalid args, 2 load/process, 3 panic/poison,
 * 4 unsupported metadata, 5 nonfinite PCM. Caller owns all live pointer ranges. */
uint32_t molly_df_abi_version(void) MOLLY_DF_NOEXCEPT;
int32_t molly_df_create(const uint8_t*,size_t,const DfConfig*,DfHandle**,DfMeta*) MOLLY_DF_NOEXCEPT;
int32_t molly_df_configure(DfHandle*,const DfConfig*) MOLLY_DF_NOEXCEPT;
int32_t molly_df_process(DfHandle*,const float*,size_t,float*,size_t,float*) MOLLY_DF_NOEXCEPT;
int32_t molly_df_reset(DfHandle*) MOLLY_DF_NOEXCEPT;
void molly_df_destroy(DfHandle*) MOLLY_DF_NOEXCEPT;
#ifdef __cplusplus
}
static_assert(sizeof(DfConfig)==32);
static_assert(sizeof(DfMeta)==28);
#endif
#undef MOLLY_DF_NOEXCEPT
#endif
