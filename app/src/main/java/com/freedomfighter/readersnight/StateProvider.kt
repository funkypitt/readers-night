package com.freedomfighter.readersnight

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * What Reader's Launcher needs for its tile: the state (`content://…/state`, one row) and the
 * switch (`call("toggle")`). Open to Reader's Launcher only, recognised by its signature.
 */
class StateProvider : ContentProvider() {
    companion object {
        val STATE: Uri = Uri.parse("content://com.freedomfighter.readersnight/state")
        const val TOGGLE = "toggle"
        private const val LAUNCHER = "com.freedomfighter.readerslauncher"
        // the launcher's release key, and the key of its first versions (still the one Android 12 and older see)
        private val KEYS = listOf(
            "B9C722E093F963AB7812922623E1C1A72FB9998537AD7D9AEFA8B1A3F3A8120C",
            "9B6AE69650B1CCF5BFA08AD15DC8B412622B8E5A3202EA066215067897A2BE28"
        ).map { hex -> ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
    }

    private fun trusted(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        val pm = context?.packageManager ?: return false
        val names = pm.getPackagesForUid(uid) ?: return false
        return LAUNCHER in names && KEYS.any { pm.hasSigningCertificate(LAUNCHER, it, PackageManager.CERT_INPUT_SHA256) }
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        if (!trusted()) throw SecurityException("not Reader's Launcher")
        val o = Filter.options(ctx)
        return MatrixCursor(arrayOf("on", "allowed", "gray", "warm", "dim")).apply {
            addRow(arrayOf<Any>(if (Filter.isOn(ctx)) 1 else 0, if (Filter.allowed(ctx)) 1 else 0, if (o.gray) 1 else 0, if (o.warm) 1 else 0, o.dim))
            setNotificationUri(ctx.contentResolver, STATE)
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (!trusted()) throw SecurityException("not Reader's Launcher")
        if (method != TOGGLE) return null
        val id = Binder.clearCallingIdentity()
        try {
            val done = Filter.toggle(ctx)
            return Bundle().apply { putBoolean("done", done); putBoolean("on", Filter.isOn(ctx)) }
        } finally { Binder.restoreCallingIdentity(id) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
