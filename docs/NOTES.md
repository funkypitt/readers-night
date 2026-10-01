# Notes

## What the filter writes

All in `Settings.Secure`, with `WRITE_SECURE_SETTINGS` (granted by `adb shell pm grant`, kept
across updates and reboots, lost on uninstall):

| Part | Keys | Value while on |
|---|---|---|
| grayscale | `accessibility_display_daltonizer`, `accessibility_display_daltonizer_enabled` | `0` (monochromacy), `1` |
| warm tint | `night_display_auto_mode`, `night_display_color_temperature`, `night_display_activated` | `0`, the framework's `config_nightDisplayColorTemperatureMin`, `1` |
| dimming | `reduce_bright_colors_level`, `reduce_bright_colors_activated` | 30 / 60 / 90, `1` |

Turning on stores each previous value in the app's preferences (`prev_<key>`); turning off writes
them back. The Night Light schedule is switched off while the filter is on, otherwise sunrise
would end the tint.

## Three things Android does that shaped `Filter.kt`

1. **The tint is not recomputed when the temperature is written.** `ColorDisplayService` compares
   the new setting with `getColorTemperature()`, which reads the same setting, finds them equal
   and does nothing. The matrix is rebuilt at boot and whenever the colour-correction switch
   changes (`onAccessibilityActivated` → `onDisplayColorModeChanged` → `setMatrix`). Hence the
   order in `write()`: temperature first, grayscale last; and `nudge()`, which moves the
   colour-correction switch between unset and `0` (both mean off) when it would not move by
   itself.
2. **The extra-dim keys cannot be read by an app** (`SecurityException: … is not readable`, hidden
   settings since Android 12). They can be written. So the previous extra-dim state is unknown:
   switching the filter off turns extra dim off and leaves its level where the filter put it.
3. **A temperature below the minimum is clamped**, and the minimum is a framework resource that
   only root can overlay (`cmd overlay fabricate` → "must be root").

## How far it goes (Pixel 10 Pro XL, Android 17)

Read from `dumpsys SurfaceFlinger` (`colorTransformMatrix`), in linear light, relative to red:

| State | green | blue |
|---|---|---|
| Night Light at its default strength | 0.63 | 0.33 |
| the filter's warm tint (2596 K) | 0.50 | 0.18 |

With grayscale on, Android applies the tint with its other coefficient set (1, 0.72, 0.46 on
encoded values), which looks like the same tint expressed before the display's gamma (the two sets agree to within a 2.2 power). Dimming "medium"
multiplies everything by about 0.39 in light.

A red-only screen (`service call SurfaceFlinger 1015` with a matrix sending luminance to red) is
refused to the shell ("Operation not permitted"): it needs root.

## Reader's Launcher

`StateProvider` (`content://com.freedomfighter.readersnight/state`, columns `on`, `allowed`,
`gray`, `warm`, `dim`; `call("toggle")`) is exported without a permission and checks the caller
itself: Reader's Launcher's package, signed with its release key or the key of its first versions.
