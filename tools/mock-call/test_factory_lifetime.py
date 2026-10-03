#!/usr/bin/env python3
"""Compile the patched lifecycle fragments with a lazy media-reference fixture.
The snippets are from pinned 7778d; the fixture models refcount Init/Terminate,
not Android hardware. test_patch.py separately applies to the full upstream.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import patch_ringrtc

GN = '''rtc_library("peer_connection_factory") {
  sources = [
    "peer_connection_factory.cc",
    "peer_connection_factory.h",
  ]
  deps = [
    ":connection_context",
  ]
}
'''
CPP = '''#include "pc/peer_connection_factory.h"
PeerConnectionFactory::PeerConnectionFactory()
      encode_metronome_(std::move(dependencies->encode_metronome)) {}
PeerConnectionFactory::~PeerConnectionFactory() {
  RTC_DCHECK_RUN_ON(signaling_thread());
  worker_thread()->BlockingCall([this] {
    RTC_DCHECK_RUN_ON(worker_thread());
    decode_metronome_ = nullptr;
    encode_metronome_ = nullptr;
    StopAecDump();
  });
}
'''
HEADER = '''class PeerConnectionFactory {
  // While AEC dump is ongoing, we retain a reference to the media engine.
  std::unique_ptr<ConnectionContext::MediaEngineReference> media_engine_ref_
      RTC_GUARDED_BY(worker_thread());
};
'''
class FactoryLifetimeTests(unittest.TestCase):
    def test_actual_constructor_fragment_initializes_only_local_factory(self):
        with tempfile.TemporaryDirectory() as directory:
            web = Path(directory); (web/'pc').mkdir()
            (web/'pc/peer_connection_factory.cc').write_text(CPP)
            (web/'pc/peer_connection_factory.h').write_text(HEADER)
            (web/'pc/BUILD.gn').write_text(GN)
            patch = getattr(patch_ringrtc, 'patch_factory_lifetime', lambda root: None)
            patch(web)
            c = (web/'pc/peer_connection_factory.cc').read_text()
            h = (web/'pc/peer_connection_factory.h').read_text()
            patch(web)
            self.assertEqual(c, (web/'pc/peer_connection_factory.cc').read_text())
            self.assertEqual(h, (web/'pc/peer_connection_factory.h').read_text())
            init = c.split('encode_metronome_(std::move(dependencies->encode_metronome))',1)[1].split('PeerConnectionFactory::~',1)[0].strip()
            destroy = c.split('PeerConnectionFactory::~PeerConnectionFactory()',1)[1].strip()
            declaration = h[h.index('{')+1:h.rindex('};')]
            program = r'''
#include <cassert>
#include <memory>
#define RTC_GUARDED_BY(x)
#define RTC_DCHECK_RUN_ON(x) ((void)0)
namespace molly_mock { bool local=false; bool AcquireConstructionSession(){return local;} }
struct Context {
 int refs=0, init=0, terminate=0, input_starts=0; bool worker=false;
 struct MediaEngineReference {
  Context& c;
  explicit MediaEngineReference(Context* p):c(*p){assert(c.worker); if(++c.refs==1)++c.init;}
  ~MediaEngineReference(){assert(c.worker); if(--c.refs==0)++c.terminate;}
 };
};
using ConnectionContext=Context;
struct Thread {
 Context& c; template<class F> void BlockingCall(F f){ assert(!c.worker); c.worker=true; f(); c.worker=false; }
};
struct Factory {
 Context* context_; Thread thread_; std::nullptr_t decode_metronome_=nullptr, encode_metronome_=nullptr;
 Thread* worker_thread(){return &thread_;} Thread* signaling_thread(){return &thread_;}
 void StopAecDump(){assert(context_->worker); media_engine_ref_.reset();}
''' + declaration + '\nFactory(Context& c):context_(&c),thread_{c} ' + init + '\n~Factory() '+destroy + r'''
};
int main(){
 Context ordinary;
 { Factory f(ordinary); assert(ordinary.init==0); }
 assert(ordinary.terminate==0);
 molly_mock::local=true; Context lab;
 { Factory f(lab); assert(lab.init==1 && "mock factory never initialized the media engine"); assert(lab.refs==1); assert(lab.input_starts==0); }
 assert(lab.refs==0 && lab.terminate==1 && "media lifetime must end on worker");
}
'''
            source=web/'test.cc';source.write_text(program)
            subprocess.run([os.environ.get('CXX','g++'),'-std=c++20','-Wall','-Wextra','-Werror',str(source),'-o',str(web/'test')],check=True)
            result=subprocess.run([str(web/'test')],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn('molly_mock_media_engine_ref_',h)
            self.assertEqual((web/'pc/BUILD.gn').read_text().count('"../audio"'), 1)
            self.assertNotIn('CreatePeerConnection',init)
    def test_unexpected_upstream_lifecycle_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            web=Path(directory);(web/'pc').mkdir()
            (web/'pc/peer_connection_factory.cc').write_text('upstream changed')
            (web/'pc/peer_connection_factory.h').write_text(HEADER)
            (web/'pc/BUILD.gn').write_text(GN)
            patch=getattr(patch_ringrtc,'patch_factory_lifetime',lambda root:None)
            with self.assertRaises(RuntimeError): patch(web)
if __name__=='__main__':unittest.main()
