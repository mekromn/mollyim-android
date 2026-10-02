// Use the real portable WebRTC convolution implementation in host tests.
#include "rtc_base/cpu_info.h"
namespace webrtc::cpu_info {bool Supports(ISA){return false;}}
