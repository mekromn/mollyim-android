#!/usr/bin/env python3
"""Build-driver behavior only; mocks downloads/compilers, not native validation."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

DRIVER = Path(__file__).with_name('build_native.sh')
DEPOT = '57d50b8656bfed9e42adf8de1d549ba4229cc909'
RING = '8d81a87adcb08b77446b691be3692ff7ed53d580'
TAG = 'e05d1475a96806acad749da8030ea4b73cc5fdb4'

class DriverTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.project = self.root / 'project'
        self.work = self.root / 'work'
        self.tools = self.root / 'tools'
        self.tools.mkdir()
        self.log = self.root / 'events'
        self.log.write_text('')
        for p in ('depot_tools/.git', 'depot_tools/python-bin', 'ringrtc/.git', 'ringrtc/bin', 'ringrtc/src/webrtc/src', 'ringrtc/src/android'):
            (self.work / p).mkdir(parents=True)
        target = self.project / 'tools/incoming-audio'
        target.mkdir(parents=True)
        shutil.copy2(DRIVER, target / 'build_native.sh')
        self.driver = target / 'build_native.sh'
        sdk = self.root / 'sdk'
        (sdk / 'ndk/28.0.13004108').mkdir(parents=True)
        self.env = dict(os.environ, PATH=f'{self.tools}:'+os.environ['PATH'], INCOMING_AUDIO_WORK=str(self.work), ANDROID_HOME=str(sdk), EVENTS=str(self.log), FIXTURE_WORK=str(self.work))
        self.executable(self.tools / 'git', f'''#!/bin/sh
case "$*" in
 *refs/tags/v2.69.5-1*) echo {TAG};;
 *depot_tools*rev-parse*) echo {DEPOT};;
 *webrtc*rev-parse*) echo fixture-webrtc-head;;
 *rev-parse*) echo {RING};;
esac
''')
        for tool in ('rustup', 'java', 'protoc'):
            self.executable(self.tools / tool, '#!/bin/sh\nexit 0\n')
        self.executable(self.work / 'depot_tools/ensure_bootstrap', '''#!/bin/sh
[ "${FAIL_BOOTSTRAP:-0}" = 0 ] || exit 73
echo bootstrap >> "$EVENTS"
touch "$FIXTURE_WORK/depot_tools/python3_bin_reldir.txt"
''')
        self.executable(self.work / 'depot_tools/python-bin/python3', '''#!/bin/sh
[ -f "$FIXTURE_WORK/depot_tools/python3_bin_reldir.txt" ] || exit 71
exec /usr/bin/python3 "$@"
''')
        self.executable(self.work / 'depot_tools/gn', '#!/bin/sh\nexit 0\n')
        self.executable(self.work / 'ringrtc/bin/prepare-workspace', '#!/bin/sh\necho prepare >> "$EVENTS"\n')
        self.executable(self.work / 'ringrtc/bin/build-aar', '''#!/bin/sh
[ -f "$FIXTURE_WORK/depot_tools/python3_bin_reldir.txt" ] || { echo 'python3_bin_reldir.txt not found' >&2; exit 71; }
echo build >> "$EVENTS"
mkdir -p out/gradle/outputs/aar
printf fixture > out/gradle/outputs/aar/ringrtc-android-release.aar
''')
        for name, event, output in [('patch_ringrtc.py', 'patch', ''), ('verify_aar.py', 'verify', '{}')]:
            (target / name).write_text(f"import os\nwith open(os.environ['EVENTS'], 'a') as f: f.write('{event}\\n')\nprint({output!r})\n")

    @staticmethod
    def executable(path, text):
        path.write_text(text)
        path.chmod(0o755)

    def run_driver(self, phase, **environment):
        return subprocess.run(['bash', str(self.driver), phase], env=dict(self.env, **environment), capture_output=True, text=True, timeout=10)

    def events(self):
        return self.log.read_text().splitlines()

    def test_prepare_bootstraps_before_sync_and_does_not_compile(self):
        result = self.run_driver('prepare')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.events(), ['bootstrap', 'prepare'])

    def test_build_reuses_prepared_checkout_without_resync(self):
        self.assertEqual(self.run_driver('prepare').returncode, 0)
        self.log.write_text('')
        result = self.run_driver('build')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.events(), ['bootstrap', 'patch', 'build', 'verify'])
        self.assertTrue((self.project / 'out/incoming-audio/ringrtc-incoming-audio-arm64.aar').is_file())

    def test_build_requires_a_prepared_checkpoint(self):
        result = self.run_driver('build')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Run prepare first', result.stderr)
        self.assertNotIn('build', self.events())

    def test_failed_bootstrap_stops_before_expensive_sync(self):
        result = self.run_driver('prepare', FAIL_BOOTSTRAP='1')
        self.assertEqual(result.returncode, 73, result.stderr)
        self.assertEqual(self.events(), [])

if __name__ == '__main__':
    unittest.main()
