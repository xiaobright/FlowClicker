package com.flowclicker.app.core

import android.util.AtomicFile
import java.io.File

/** Caller serializes read-modify-write; AtomicFile protects the last committed bytes. */
object AtomicTextFile {
    fun read(file: File): String = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun write(file: File, text: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            atomic.failWrite(stream)
            throw t
        }
    }
}
