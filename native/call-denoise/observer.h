/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_DENOISE_OBSERVER_H_
#define MOLLY_DENOISE_OBSERVER_H_
#include "worker.h"
namespace molly_denoise {
// Non-owning, optional local-lab taps. Installed before hardware starts and
// removed after callbacks stop. No observer exists on ordinary call paths.
class PathObserver {
 public:
  virtual ~PathObserver()=default;
  virtual void Before(bool received,const OriginalPacket&)noexcept=0;
  virtual void After(bool received,const ProcessResult&,std::span<const float>)noexcept=0;
};
}
#endif
