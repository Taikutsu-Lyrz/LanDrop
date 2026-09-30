package dev.landrop.mobile

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val entries = ArrayDeque<String>()

    fun add(message: String) {
        synchronized(lock) {
            entries += "${timeFormat.format(Date())}  $message"
            while (entries.size > 300) {
                entries.removeFirst()
            }
        }
    }

    fun snapshot(): List<String> {
        return synchronized(lock) { entries.toList().asReversed() }
    }
}
