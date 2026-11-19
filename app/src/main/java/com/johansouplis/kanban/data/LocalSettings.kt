package com.johansouplis.kanban.data

import android.content.Context

data class BoardLink(val boardId: String, val joinCode: String)

/** What this phone alone remembers: its board, its last calendar, and the tasks its calendar still owes a change. */
class LocalSettings(context: Context) {
    private val prefs = context.getSharedPreferences("kanban", Context.MODE_PRIVATE)

    var board: BoardLink?
        get() {
            val id = prefs.getString("boardId", null) ?: return null
            return BoardLink(id, prefs.getString("joinCode", null).orEmpty())
        }
        set(value) {
            prefs.edit().putString("boardId", value?.boardId).putString("joinCode", value?.joinCode).apply()
        }

    var lastCalendarKey: String?
        get() = prefs.getString("lastCalendarKey", null)
        set(value) = prefs.edit().putString("lastCalendarKey", value).apply()

    var pending: Set<String>
        get() = prefs.getStringSet("pending", null).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet("pending", value).apply()
}
