#!/usr/bin/env python3
"""Host regressions. Runs real enum/palette logic; not an Android UI test."""
import re
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'app/src/main'
CORE = ROOT / 'core/ui/src/main'
SURFACES = ['background', 'surface', 'surfaceDim', 'surfaceBright', 'surfaceContainerLowest',
            'surfaceContainerLow', 'surfaceContainer', 'surfaceContainerHigh', 'surfaceContainerHighest', 'surfaceTint']
PRESERVED = ['primary', 'onPrimary', 'primaryContainer', 'onPrimaryContainer', 'secondary',
             'onSecondary', 'secondaryContainer', 'onSecondaryContainer', 'tertiary', 'onTertiary',
             'onSurface', 'onSurfaceVariant', 'onBackground', 'error', 'onError', 'errorContainer',
             'onErrorContainer', 'outline', 'outlineVariant', 'inverseSurface', 'inverseOnSurface', 'surfaceVariant']

class ThemeTests(unittest.TestCase):
    def test_existing_and_new_theme_serialization(self):
        source = (APP / 'java/org/thoughtcrime/securesms/keyvalue/SettingsValues.java').read_text()
        enum = source[source.index('  public enum Theme {'):source.index('  public enum NotificationDeliveryMethod')]
        enum = enum.replace('@NonNull ', '')
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / 'EnumTest.java').write_text('public class EnumTest {\n' + enum + '''
              public static void main(String[] args) {
                for (String s : new String[]{"system","light","dark","amoled"}) {
                  if (!Theme.deserialize(s).serialize().equals(s)) throw new AssertionError(s);
                }
                try {Theme.deserialize("invalid"); throw new AssertionError("Must reject invalid values");}
                catch (IllegalArgumentException expected) {}
              }
            }''')
            subprocess.run(['javac', str(d / 'EnumTest.java')], check=True, capture_output=True)
            r = subprocess.run(['java', '-cp', str(d), 'EnumTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)

    def test_theme_picker_keeps_old_modes_and_adds_amoled(self):
        for variant, expected in [('values', ['light','dark','amoled']), ('values-v29', ['system','light','dark','amoled'])]:
            xml = ET.parse(APP / 'res' / variant / 'arrays.xml').getroot()
            values = [n.text for n in xml.find("string-array[@name='pref_theme_values']")]
            entries = [n.text for n in xml.find("string-array[@name='pref_theme_entries']")]
            self.assertEqual(values, expected)
            self.assertEqual(len(values), len(entries))
            self.assertEqual(entries[-1], '@string/preferences__amoled_black_theme')

    def test_overlay_is_black_without_overwriting_accents_or_bubbles(self):
        path = APP / 'res/values/amoled_theme.xml'
        self.assertTrue(path.exists(), 'AMOLED overlay is missing')
        style = ET.parse(path).find("style[@name='ThemeOverlay.Molly.Amoled']")
        attrs = {n.attrib['name']: n.text for n in style}
        for name in ['colorSurface','colorSurfaceDim','colorSurfaceBright','colorSurfaceContainerLowest',
                     'colorSurfaceContainerLow','colorSurfaceContainer','colorSurfaceContainerHigh',
                     'colorSurfaceContainerHighest','android:colorBackground','android:windowBackground',
                     'navbar_container_color','context_menu_container_color']:
            self.assertEqual(attrs[name], '@android:color/black', name)
        self.assertEqual(attrs['elevationOverlayEnabled'], 'false')
        self.assertEqual(attrs['amoled_black'], 'true')
        self.assertEqual(attrs['conversation_item_recv_bubble_color_normal'], '@color/molly_surface_container_high_dark')
        self.assertFalse({'colorPrimary','colorSecondary','colorAccent','colorOnSurface','colorOnBackground'} & attrs.keys())
        dynamic = ET.parse(APP / 'res/values-v31/amoled_theme.xml').find("style[@name='ThemeOverlay.Molly.Amoled.Dynamic']")
        self.assertEqual(dynamic.find("item[@name='conversation_item_recv_bubble_color_normal']").text,
                         '@color/dynamic_surface_container_high_dark')

    @unittest.skipUnless(shutil.which('kotlinc'), 'Standalone Kotlin not installed; Android compile runs separately')
    def test_palette_only_changes_background_surfaces(self):
        path = CORE / 'java/org/signal/core/ui/compose/theme/AmoledPalette.kt'
        self.assertTrue(path.exists(), 'AMOLED palette transform is missing')
        # Android/Compose is not available on the host. Use trivial value-type stubs,
        # then compile and execute the actual production copy transforms unchanged.
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            props = SURFACES + PRESERVED
            (d / 'Color.kt').write_text('package androidx.compose.ui.graphics\ndata class Color(val value: Long) { companion object { val Black=Color(0xff000000L) }}')
            (d / 'ColorScheme.kt').write_text('package androidx.compose.material3\nimport androidx.compose.ui.graphics.Color\ndata class ColorScheme(' + ','.join(f'val {n}:Color=Color({i+1}L)' for i,n in enumerate(props)) + ')')
            ext = (CORE / 'java/org/signal/core/ui/compose/theme/ExtendedColors.kt').read_text()
            ext = ext[ext.index('data class ExtendedColors('):ext.index('val LocalExtendedColors')]
            (d / 'Extended.kt').write_text('package org.signal.core.ui.compose.theme\nimport androidx.compose.ui.graphics.Color\n' + ext)
            ext_props = re.findall(r'val (\w+): Color', ext)
            checks = '\n'.join(f'check(black.{n} == Color.Black) {{ "{n}" }}' for n in SURFACES)
            checks += '\n' + '\n'.join(f'check(black.{n} == base.{n}) {{ "preserve {n}" }}' for n in PRESERVED)
            checks += '\ncheck(base.surface != Color.Black)\ncheck(black.amoledBlackSurfaces() == black)'
            ext_checks = '\n'.join(f'check(e.{n} == ' + ('Color.Black' if n in ['colorSurface1','colorSurface2','colorSurface3','colorSurface4','colorSurface5'] else f'original.{n}') + ')' for n in ext_props)
            (d / 'Main.kt').write_text('package org.signal.core.ui.compose.theme\nimport androidx.compose.ui.graphics.Color\nimport androidx.compose.material3.ColorScheme\nfun main() {\nval base=ColorScheme()\nval black=base.amoledBlackSurfaces()\n' + checks + '\nval original=ExtendedColors(' + ','.join(f'{n}=Color({i+5}L)' for i,n in enumerate(ext_props)) + ')\nval e=original.amoledBlackSurfaces()\n' + ext_checks + '\nprintln("PASS production palette transformations and preserved control/accent colors")\n}')
            subprocess.run(['kotlinc', *map(str,d.glob('*.kt')), str(path), '-include-runtime', '-d', str(d/'test.jar')], check=True, capture_output=True)
            r = subprocess.run(['java','-jar',str(d/'test.jar')], capture_output=True,text=True)
            self.assertEqual(r.returncode,0,r.stderr)

    def test_dark_mode_resume_and_backup_mapping(self):
        dynamic = (APP / 'java/org/thoughtcrime/securesms/util/DynamicTheme.java').read_text()
        self.assertIn('theme == Theme.DARK || theme == Theme.AMOLED', dynamic)
        self.assertIn('onCreateAmoledBlack != isAmoledBlack(activity)', dynamic)
        self.assertIn('applyAmoledOverlay', dynamic)
        archive = (APP / 'java/org/thoughtcrime/securesms/backup/v2/processor/AccountDataArchiveProcessor.kt').read_text()
        self.assertIn('SettingsValues.Theme.AMOLED -> AccountData.AppTheme.DARK', archive)
        splash = (APP / 'java/org/thoughtcrime/securesms/util/SplashScreenUtil.java').read_text()
        self.assertIn('case AMOLED:', splash)

    def test_compose_uses_explicit_theme_flag_not_global_color_replacements(self):
        theme = (CORE / 'java/org/signal/core/ui/compose/theme/SignalTheme.kt').read_text()
        self.assertIn('isDarkMode &&', theme)
        self.assertIn('R.attr.amoled_black', theme)
        self.assertIn('baseColorScheme.amoledBlackSurfaces()', theme)
        self.assertIn('baseExtendedColors.amoledBlackSurfaces()', theme)
        palette = (CORE / 'java/org/signal/core/ui/compose/theme/AmoledPalette.kt')
        self.assertTrue(palette.exists())
        self.assertNotIn('surfaceVariant =', palette.read_text())

if __name__ == '__main__':
    unittest.main(verbosity=2)
