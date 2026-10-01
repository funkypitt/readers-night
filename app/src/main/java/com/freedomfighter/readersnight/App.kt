package com.freedomfighter.readersnight

import android.app.Application
import com.freedomfighter.readersnight.data.Prefs

class App : Application() {
    val prefs: Prefs by lazy { Prefs(this) }
    override fun onCreate() { super.onCreate(); prefs }
}
