#!/usr/bin/env python3
"""Compile production packet headers with the Android warning policy, unmasked."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class HeaderPolicyTests(unittest.TestCase):
    def test_clip_header_has_no_hidden_indentation_warning(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            (work / 'audio').mkdir()
            (work / 'audio/molly_denoise').symlink_to(ROOT / 'native/call-denoise', target_is_directory=True)
            unit = work / 'header.cc'
            unit.write_text('#include "native/mock-call/packet.h"\nint main(){molly_mock::Clip clip;return clip.Rate();}\n')
            result = subprocess.run([
                os.environ.get('CXX', 'clang++'), '-std=c++20', '-Wall', '-Wextra',
                '-Werror', '-Wmisleading-indentation', '-fno-exceptions', '-fno-rtti',
                '-I', str(ROOT), '-I', str(work), '-fsyntax-only', str(unit)
            ], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

if __name__ == '__main__':
    unittest.main()
