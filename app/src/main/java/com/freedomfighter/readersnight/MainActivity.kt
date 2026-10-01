package com.freedomfighter.readersnight

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.freedomfighter.readersnight.ui.HomeScreen
import com.freedomfighter.readersnight.ui.LocalColors
import com.freedomfighter.readersnight.ui.Nav
import com.freedomfighter.readersnight.ui.ReaderTheme
import com.freedomfighter.readersnight.ui.Screen
import com.freedomfighter.readersnight.ui.SettingsScreen
import com.freedomfighter.readersnight.widget.NightWidget

class MainActivity : ComponentActivity() {
    private val nav = Nav()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = application as App
        setContent {
            val settings by app.prefs.settings.collectAsState()
            ReaderTheme(settings) {
                Bars()
                when (nav.current) {
                    Screen.Home -> HomeScreen(nav, app)
                    Screen.Settings -> SettingsScreen(nav, app)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // the grayscale may have been switched off in the system settings meanwhile
        Filter.sync(this)
    }

    override fun onPause() {
        super.onPause()
        // the colours of the widget follow the app's
        NightWidget.refresh(this)
    }

    @Composable
    private fun Bars() {
        val view = LocalView.current
        val colors = LocalColors.current
        LaunchedEffect(colors.isDark) {
            val c = WindowInsetsControllerCompat(window, view)
            c.isAppearanceLightStatusBars = !colors.isDark
            c.isAppearanceLightNavigationBars = !colors.isDark
        }
    }
}
