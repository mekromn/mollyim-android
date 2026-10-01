/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.core.ui.compose.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** Only background surfaces change; accents, text, errors and control fills retain
 * their original palette. Black surfaceTint prevents elevation from tinting black. */
internal fun ColorScheme.amoledBlackSurfaces(): ColorScheme = copy(
  background = Color.Black,
  surface = Color.Black,
  surfaceDim = Color.Black,
  surfaceBright = Color.Black,
  surfaceContainerLowest = Color.Black,
  surfaceContainerLow = Color.Black,
  surfaceContainer = Color.Black,
  surfaceContainerHigh = Color.Black,
  surfaceContainerHighest = Color.Black,
  surfaceTint = Color.Black
)

internal fun ExtendedColors.amoledBlackSurfaces(): ExtendedColors = copy(
  colorSurface1 = Color.Black,
  colorSurface2 = Color.Black,
  colorSurface3 = Color.Black,
  colorSurface4 = Color.Black,
  colorSurface5 = Color.Black
)
