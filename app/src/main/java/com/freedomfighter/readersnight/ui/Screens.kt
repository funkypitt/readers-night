package com.freedomfighter.readersnight.ui

import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
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
import com.freedomfighter.readersnight.data.FontChoice
import com.freedomfighter.readersnight.data.TextSize
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

// ---------------------------------------------------------------------------------------------
// Home: the state, what the filter does, and the switch as the row at the bottom.
// ---------------------------------------------------------------------------------------------

@Composable
fun HomeScreen(nav: Nav, app: App) {
    val context = LocalContext.current
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val tick = rememberTick()
    var menu by remember { mutableStateOf(false) }
    val v by Filter.version.collectAsState()
    val allowed = rememberAllowed()
    val on = remember(v) { Filter.isOn(context) }
    val o = remember(v) { Filter.options(context) }
    val actionLabel = stringResource(if (on) R.string.turn_off else R.string.turn_on)
    fun toggle() { tick(); Filter.toggle(context) }

    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.app_title), onBack = null, trailing = "⋯", onTrailing = { menu = true })
            if (!allowed) Setup(Modifier.weight(1f))
            else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    // ---- the state, inverted while on ----
                    Column(
                        Modifier.fillMaxWidth().padding(top = 14.dp).background(if (on) colors.fg else colors.bg)
                            .noRippleClickable { toggle() }
                            .padding(horizontal = rowPadH, vertical = 18.dp)
                    ) {
                        T(stringResource(if (on) R.string.state_on else R.string.state_off), size = typo.tile * 1.3f, color = if (on) colors.bg else colors.fg, maxLines = 1)
                        Small(if (on) summary(context, o) else stringResource(R.string.detail_off), color = if (on) colors.bg.copy(alpha = 0.7f) else colors.dim, maxLines = 4)
                    }
                    // ---- what it does ----
                    Small(stringResource(R.string.what), Modifier.padding(horizontal = rowPadH).padding(top = 18.dp, bottom = 2.dp), maxLines = 2)
                    ValueRow(stringResource(R.string.gray), stringResource(if (o.gray) R.string.on else R.string.off), strong = o.gray) {
                        tick(); Filter.setOptions(context, o.copy(gray = !o.gray))
                    }
                    ValueRow(stringResource(R.string.warm), stringResource(if (o.warm) R.string.on else R.string.off), strong = o.warm) {
                        tick(); Filter.setOptions(context, o.copy(warm = !o.warm))
                    }
                    if (Filter.dimAvailable) ValueRow(
                        stringResource(R.string.dim),
                        stringResource(when {
                            o.dim <= 0 -> R.string.off
                            o.dim <= Filter.DIM_LIGHT -> R.string.dim_light
                            o.dim <= Filter.DIM_MEDIUM -> R.string.dim_medium
                            else -> R.string.dim_strong
                        }),
                        strong = o.dim > 0
                    ) {
                        tick()
                        Filter.setOptions(context, o.copy(dim = when {
                            o.dim <= 0 -> Filter.DIM_LIGHT
                            o.dim <= Filter.DIM_LIGHT -> Filter.DIM_MEDIUM
                            o.dim <= Filter.DIM_MEDIUM -> Filter.DIM_STRONG
                            else -> 0
                        }))
                    }
                    Rule(Modifier.padding(vertical = 8.dp))
                    Small(stringResource(R.string.limit_note), Modifier.padding(horizontal = rowPadH, vertical = 6.dp), maxLines = 8)
                    VSpace(12.dp)
                }
                Rule()
                TextRow(actionLabel, size = typo.title) { toggle() }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) TextMenu(null, buildList {
            if (allowed) add(MenuItem(actionLabel) { toggle() })
        }, onDismiss = { menu = false }, footer = listOf(
            MenuItem(if (colors.isDark) stringResource(R.string.theme_light) else stringResource(R.string.theme_dark)) { app.prefs.toggleTheme(colors.isDark) },
            MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings) }
        ))
    }
}

/** Shown in place of the controls until the permission is given from a computer. */
@Composable
fun Setup(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val command = grantCommand(context)
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2500); copied = false } }
    Column(modifier.verticalScroll(rememberScrollState())) {
        T(stringResource(R.string.setup_title), Modifier.padding(horizontal = rowPadH).padding(top = 22.dp, bottom = 8.dp), size = typo.tile * 1.3f, align = TextAlign.Start)
        Small(stringResource(R.string.setup_text), Modifier.padding(horizontal = rowPadH), maxLines = 12, align = TextAlign.Start)
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
