/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MOLLY_MOCK_REGISTRY_H_
#define MOLLY_MOCK_REGISTRY_H_
#include "scope.h"
#include "state.h"
#include "audio/molly_denoise/service.h"
#include <memory>
#include <mutex>
#include <span>
namespace webrtc {class TaskQueueBase;}
namespace molly_mock {
class LabEndpoint;
struct LabSession {
 explicit LabSession(uint64_t token):id(token),service(molly_denoise::GlobalFactory(),&scope.denoise,&scope.effects,true){}
 const uint64_t id;
 AudioScope scope;
 LabState state;
 molly_denoise::NativeService service;
 uint64_t factory=0,owner=0;
 std::mutex command_lock;
 LabEndpoint* endpoint=nullptr; // access under command_lock, and on worker queue
 webrtc::TaskQueueBase* queue=nullptr;
 std::atomic<uint64_t>next_command{1},ack{0};
 std::atomic<int>status{0},error{0},mode{0};
 std::atomic<bool>hardware_stopped{true},finished{false},warming{false};
 std::atomic<int64_t>position_ms{0};
 ScopeConfiguration requested;
 std::array<Clip,2>clips;
};
std::shared_ptr<LabSession> AcquireConstructionSession();
std::shared_ptr<LabSession> FindSession(uint64_t);
uint64_t PrepareSession();
bool FinishSession(uint64_t);
void RevokeSession(uint64_t);
bool ReleaseSession(uint64_t);
bool PreemptForProduction();
uint64_t CommandSession(uint64_t,int,int64_t,int64_t,int64_t,int64_t);
uint64_t ConfigureSession(uint64_t,std::span<const float>);
uint64_t LoadSession(uint64_t,int,uint32_t,uint32_t,std::vector<int16_t>);
bool ReadSession(uint64_t,std::span<float>);
int DrainSession(uint64_t,int,std::span<int64_t>,std::span<float>);
}
#endif
