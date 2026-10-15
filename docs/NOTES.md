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

## Without the permission: the veil (1.3.0)

`VeilService` draws a translucent layer over every app (`TYPE_APPLICATION_OVERLAY`, the
"draw over other apps" permission, which the user gives on the phone): a warm colour for the
tint, black for the dimming, touches passed through. What Android imposes on such a layer:

* **Opacity capped at 0.8** (`LayoutParams.alpha`): above it, touches through a non-touchable
  overlay are blocked as "untrusted". The view's own colours carry the levels under that cap.
* It never covers the status bar, the notification shade, the lock screen or the keyboard;
  it is a window, so it cannot change colours: no grayscale this way.
* A foreground service keeps it alive (`specialUse`, quiet notification on channel `veil`,
  not shown without the notification permission, which the app never asks for).

State: `on` + `veil` in the `filter` preferences. `Filter.sync()` (activity resume, tile
listening, home polling) drops the veil the moment `WRITE_SECURE_SETTINGS` arrives and turns
the real filter on; after a boot or an update `BootReceiver` draws it again. The home page
in this mode explains the computer step (grayscale pitch), keeps the command, and offers the
phone's own pages for what the full filter would switch (only those that resolve on the
phone: Night Light is absent on the emulator, so is its row).

`StateProvider`: `allowed` = the switch can act (full or veil), new column `full` = the
computer step is done. Reader's Launcher 1.24 shows on/off as before and "one step left"
only when neither permission is there.

## The schedule (1.3.0)

`Schedule`: on at a time or at sunset, off at a time (07:00 by default). Two exact alarms
(`setExactAndAllowWhileIdle`, `USE_EXACT_ALARM` so no user step on Android 13+;
`SCHEDULE_EXACT_ALARM` kept up to API 32), armed again after each firing, after a boot, an
update, a clock or time-zone change, and at each activity resume. A tap in between is left
alone: the schedule only acts at its two moments.

Sunset: `Sun.sunsetMinutes` (Almanac for Computers 1990 formula, within 2 minutes of NOAA's
spreadsheet in `SunTest`), from the phone's last known coarse position, kept in the
preferences; `getCurrentLocation` once when none is kept. Without a position, or where the
sun does not set, the chosen time is used and the page says so. Nothing fetched.

## Reader's Launcher

`StateProvider` (`content://com.freedomfighter.readersnight/state`, columns `on`, `allowed`,
`gray`, `warm`, `dim`; `call("toggle")`) is exported without a permission and checks the caller
itself: Reader's Launcher's package, signed with its release key or the key of its first versions.
