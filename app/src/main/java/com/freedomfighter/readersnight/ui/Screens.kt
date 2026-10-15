package com.freedomfighter.readersnight.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.input.KeyboardType
import java.util.Calendar
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freedomfighter.readersnight.App
import com.freedomfighter.readersnight.Filter
import com.freedomfighter.readersnight.NightTileService
import com.freedomfighter.readersnight.R
import com.freedomfighter.readersnight.Schedule
import com.freedomfighter.readersnight.data.FontChoice
import com.freedomfighter.readersnight.data.TextSize
import com.freedomfighter.readersnight.dimWord
import com.freedomfighter.readersnight.summary
import kotlinx.coroutines.delay

sealed class Screen {
    data object Home : Screen()
    data object Settings : Screen()
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
}

/** The command that gives the permission, typed on a computer. */
fun grantCommand(context: Context) = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

/** True from the moment the permission is there; looked at again while the page is open. */
@Composable
fun rememberAllowed(): Boolean {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(Filter.allowed(context)) }
    LaunchedEffect(Unit) { while (!allowed) { delay(1500); allowed = Filter.allowed(context) } }
    return allowed
}

/** Same for the veil's permission, which the user gives on the phone and may take away. */
@Composable
fun rememberVeilAllowed(): Boolean {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(Filter.veilAllowed(context)) }
    LaunchedEffect(Unit) { while (true) { delay(1500); allowed = Filter.veilAllowed(context) } }
    return allowed
}

/** The system page where the veil is allowed. */
fun askVeil(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The phone's own pages for what the full filter would drive, those this phone has. */
fun systemPages(context: Context): List<Pair<Int, Intent>> = listOf(
    R.string.sys_color to Intent("com.android.settings.ACCESSIBILITY_COLOR_SPACE_SETTINGS"),
    R.string.sys_night to Intent(Settings.ACTION_NIGHT_DISPLAY_SETTINGS),
    R.string.sys_dim to Intent("android.settings.REDUCE_BRIGHT_COLORS_SETTINGS")
).filter { context.packageManager.resolveActivity(it.second, 0) != null }

/** "21:00" the way the phone writes times. */
fun clock(context: Context, minutes: Int): String {
    val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, minutes / 60); set(Calendar.MINUTE, minutes % 60); set(Calendar.SECOND, 0) }
    return DateFormat.getTimeFormat(context).format(c.time)
}

/** Digits typed become "hh:mm"; anything else is dropped. */
fun timeMask(raw: String): String {
    val d = raw.filter { it.isDigit() }.take(4)
    return if (d.length <= 2) d else d.substring(0, 2) + ":" + d.substring(2)
}

/** "21:00" or "2100" → minutes, null when it is no time of day. */
fun parseTime(text: String): Int? {
    val d = text.filter { it.isDigit() }
    if (d.length < 3) return null
    val h = d.dropLast(2).toInt()
    val m = d.takeLast(2).toInt()
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}

// ---------------------------------------------------------------------------------------------
// Home: the state, what the filter does, the schedule, and the switch as the row at the bottom.
// Without the full permission the page also explains the computer step and offers the veil.
// ---------------------------------------------------------------------------------------------

private sealed class Ask {
    data object OnMenu : Ask()
    data object OnTime : Ask()
    data object OffTime : Ask()
}

@Composable
fun HomeScreen(nav: Nav, app: App) {
    val context = LocalContext.current
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val tick = rememberTick()
    var menu by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    val v by Filter.version.collectAsState()
    val full = rememberAllowed()
    val veil = rememberVeilAllowed()
    val canAct = full || veil
    // the full permission arriving while the veil is on: the settings take over at once
    LaunchedEffect(full, veil) { Filter.sync(context) }
    val on = remember(v, full, veil) { Filter.isOn(context) }
    val o = remember(v) { Filter.options(context) }
    val eff = remember(v, full) { Filter.effective(context) }
    val sched = remember(v) { Schedule.state(context) }
    val sunset = remember(v) { Schedule.sunsetToday(context) }
    val actionLabel = stringResource(if (on) R.string.turn_off else R.string.turn_on)
    fun toggle() { tick(); if (!Filter.toggle(context)) askVeil(context) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Schedule.set(context, sunset = true) }
    fun chooseSunset() {
        if (Schedule.hasLocationPermission(context)) Schedule.set(context, sunset = true)
        else askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.app_title), onBack = null, trailing = "⋯", onTrailing = { menu = true })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                // ---- the state, inverted while on ----
                Column(
                    Modifier.fillMaxWidth().padding(top = 14.dp).background(if (on) colors.fg else colors.bg)
                        .noRippleClickable { toggle() }
                        .padding(horizontal = rowPadH, vertical = 18.dp)
                ) {
                    T(stringResource(if (on) R.string.state_on else R.string.state_off), size = typo.tile * 1.3f, color = if (on) colors.bg else colors.fg, maxLines = 1)
                    Small(
                        when {
                            on -> summary(context, eff)
                            !canAct -> stringResource(R.string.veil_ask)
                            else -> stringResource(R.string.detail_off)
                        },
                        color = if (on) colors.bg.copy(alpha = 0.7f) else colors.dim, maxLines = 4
                    )
                }
                // ---- what it does ----
                Small(stringResource(R.string.what), Modifier.padding(horizontal = rowPadH).padding(top = 18.dp, bottom = 2.dp), maxLines = 2)
                ValueRow(
                    stringResource(R.string.gray),
                    if (!full) stringResource(R.string.needs_step) else stringResource(if (o.gray) R.string.on else R.string.off),
                    strong = full && o.gray
                ) {
                    if (full) { tick(); Filter.setOptions(context, o.copy(gray = !o.gray)) }
                }
                ValueRow(stringResource(R.string.warm), stringResource(if (o.warm) R.string.on else R.string.off), strong = o.warm) {
                    tick(); Filter.setOptions(context, o.copy(warm = !o.warm))
                }
                if (Filter.dimAvailable || !full) ValueRow(stringResource(R.string.dim), dimWord(context, o.dim), strong = o.dim > 0) {
                    tick(); Filter.setOptions(context, o.copy(dim = Filter.nextDim(o.dim)))
                }
                Rule(Modifier.padding(vertical = 8.dp))
                if (full) {
                    Small(stringResource(R.string.limit_note), Modifier.padding(horizontal = rowPadH, vertical = 6.dp), maxLines = 8)
                } else {
                    // ---- the veil, and the computer step that replaces it ----
                    if (!veil) {
                        TextRow(stringResource(R.string.veil_allow), secondary = stringResource(R.string.veil_allow_hint), size = typo.title) { askVeil(context) }
                        Rule(Modifier.padding(vertical = 8.dp))
                    }
                    Setup()
                    Rule(Modifier.padding(vertical = 8.dp))
                    val pages = remember { systemPages(context) }
                    if (pages.isNotEmpty()) {
                        Small(stringResource(R.string.sys_title), Modifier.padding(horizontal = rowPadH).padding(top = 10.dp, bottom = 2.dp), maxLines = 3)
                        pages.forEach { (label, intent) ->
                            TextRow(stringResource(label), size = typo.title) {
                                tick(); runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        }
                        Rule(Modifier.padding(vertical = 8.dp))
                    }
                }
                // ---- the schedule ----
                if (canAct) {
                    Small(stringResource(R.string.sched_title), Modifier.padding(horizontal = rowPadH).padding(top = 10.dp, bottom = 2.dp), maxLines = 2)
                    ValueRow(stringResource(R.string.sched), stringResource(if (sched.enabled) R.string.on else R.string.off), strong = sched.enabled) {
                        tick(); Schedule.set(context, enabled = !sched.enabled)
                    }
                    if (sched.enabled) {
                        ValueRow(
                            stringResource(R.string.sched_from),
                            when {
                                sched.sunset && sunset != null -> stringResource(R.string.at_sunset_time, clock(context, sunset))
                                sched.sunset -> stringResource(R.string.at_sunset)
                                else -> clock(context, sched.onMinutes)
                            },
                            strong = true
                        ) { tick(); ask = Ask.OnMenu }
                        ValueRow(stringResource(R.string.sched_until), clock(context, sched.offMinutes), strong = true) { tick(); ask = Ask.OffTime }
                        if (sched.sunset && sunset == null) Small(
                            stringResource(if (sched.located) R.string.no_sunset else R.string.no_location, clock(context, sched.onMinutes)),
                            Modifier.padding(horizontal = rowPadH, vertical = 6.dp), maxLines = 4
                        )
                    }
                    VSpace(12.dp)
                }
            }
            Rule()
            TextRow(actionLabel, size = typo.title) { toggle() }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) TextMenu(null, buildList {
            if (canAct) add(MenuItem(actionLabel) { toggle() })
        }, onDismiss = { menu = false }, footer = listOf(
            MenuItem(if (colors.isDark) stringResource(R.string.theme_light) else stringResource(R.string.theme_dark)) { app.prefs.toggleTheme(colors.isDark) },
            MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings) }
        ))
        when (ask) {
            Ask.OnMenu -> TextMenu(stringResource(R.string.sched_from), listOf(
                MenuItem(stringResource(R.string.at_sunset), secondary = stringResource(R.string.at_sunset_hint)) { chooseSunset() },
                MenuItem(stringResource(R.string.at_time), secondary = clock(context, sched.onMinutes)) { ask = Ask.OnTime }
            ), onDismiss = { ask = null })
            Ask.OnTime -> TextPrompt(
                stringResource(R.string.time_prompt_on), initial = clock24(sched.onMinutes), keyboard = KeyboardType.Number, selectAll = true, normalize = ::timeMask,
                onDone = { parseTime(it)?.let { m -> Schedule.set(context, sunset = false, onMinutes = m) }; ask = null }, onCancel = { ask = null }
            )
            Ask.OffTime -> TextPrompt(
                stringResource(R.string.time_prompt_off), initial = clock24(sched.offMinutes), keyboard = KeyboardType.Number, selectAll = true, normalize = ::timeMask,
                onDone = { parseTime(it)?.let { m -> Schedule.set(context, offMinutes = m) }; ask = null }, onCancel = { ask = null }
            )
            null -> {}
        }
    }
}

/** "21:00", what the prompt starts with whatever the phone's clock style. */
fun clock24(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

/** The computer step: why it matters, how it is done, the command. */
@Composable
fun Setup(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val command = grantCommand(context)
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2500); copied = false } }
    Column(modifier) {
        T(stringResource(R.string.setup_title), Modifier.padding(horizontal = rowPadH).padding(top = 10.dp, bottom = 8.dp), size = typo.title, align = TextAlign.Start)
        Small(stringResource(R.string.setup_why), Modifier.padding(horizontal = rowPadH), maxLines = 12, align = TextAlign.Start)
        Small(stringResource(R.string.setup_gray), Modifier.padding(horizontal = rowPadH).padding(top = 10.dp), color = colors.fg, maxLines = 10, align = TextAlign.Start)
        Small(stringResource(R.string.setup_how), Modifier.padding(horizontal = rowPadH).padding(top = 10.dp), maxLines = 10, align = TextAlign.Start)
        SelectionContainer {
            BasicText(
                command,
                Modifier.padding(horizontal = rowPadH, vertical = 16.dp),
                style = TextStyle(color = colors.fg, fontFamily = FontFamily.Monospace, fontSize = typo.small, lineHeight = typo.small * 1.4f)
            )
        }
        Rule()
        TextRow(stringResource(if (copied) R.string.copied else R.string.setup_copy), size = typo.title) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("adb", command))
            copied = true
        }
        Rule()
        Small(stringResource(R.string.setup_wait), Modifier.padding(horizontal = rowPadH, vertical = 14.dp), maxLines = 6, align = TextAlign.Start)
    }
}

/** A name on the left, its state on the right. */
@Composable
fun ValueRow(label: String, value: String, strong: Boolean, onClick: () -> Unit) {
    val colors = LocalColors.current
    val typo = LocalTypo.current
    Row(
        Modifier.fillMaxWidth().noRippleClickable(onClick = onClick).padding(horizontal = rowPadH, vertical = rowPadV * 0.45f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        T(label, Modifier.weight(1f), size = typo.title, maxLines = 1, align = TextAlign.Start)
        T(value, size = typo.small, color = if (strong) colors.fg else colors.dim, maxLines = 1, align = TextAlign.End)
    }
}

// ---------------------------------------------------------------------------------------------
// Settings: the quick-settings switch, the look
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(nav: Nav, app: App) {
    val context = LocalContext.current
    val s by app.prefs.settings.collectAsState()
    val colors = LocalColors.current
    val typo = LocalTypo.current
    BackHandler { nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.settings), onBack = { nav.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                VSpace(10.dp)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val label = stringResource(R.string.tile_label)
                    TextRow(stringResource(R.string.add_tile), secondary = stringResource(R.string.add_tile_hint), size = typo.title) {
                        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                            ComponentName(context, NightTileService::class.java), label,
                            Icon.createWithResource(context, R.drawable.ic_tile), context.mainExecutor
                        ) { }
                    }
                } else {
                    Small(stringResource(R.string.add_tile_manual), Modifier.padding(horizontal = rowPadH, vertical = 10.dp), maxLines = 6)
                }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(if (colors.isDark) stringResource(R.string.theme_dark) else stringResource(R.string.theme_light), secondary = stringResource(R.string.colours)) { app.prefs.toggleTheme(colors.isDark) }
                TextRow(when (s.textSize) { TextSize.SMALL -> stringResource(R.string.size_small); TextSize.MEDIUM -> stringResource(R.string.size_medium); TextSize.LARGE -> stringResource(R.string.size_large) }, secondary = stringResource(R.string.text_size)) {
                    app.prefs.setTextSize(when (s.textSize) { TextSize.SMALL -> TextSize.MEDIUM; TextSize.MEDIUM -> TextSize.LARGE; TextSize.LARGE -> TextSize.SMALL })
                }
                TextRow(when (s.font) { FontChoice.SANS -> stringResource(R.string.font_sans); FontChoice.SERIF -> stringResource(R.string.font_serif); FontChoice.MONO -> stringResource(R.string.font_mono) }, secondary = stringResource(R.string.font)) {
                    app.prefs.setFont(when (s.font) { FontChoice.SANS -> FontChoice.SERIF; FontChoice.SERIF -> FontChoice.MONO; FontChoice.MONO -> FontChoice.SANS })
                }
                TextRow(if (s.haptics) stringResource(R.string.on) else stringResource(R.string.off), secondary = stringResource(R.string.haptics)) { app.prefs.setHaptics(!s.haptics) }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(stringResource(R.string.app_name), secondary = stringResource(R.string.about)) { }
                TextRow(stringResource(R.string.credits)) { }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}
