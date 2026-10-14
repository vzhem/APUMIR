package com.vladimir.messenger.data.diagnostics

import java.io.InputStream

/** Byte bound applies to the opened stream, including an AtomicFile-recovered backup. */
internal fun readBoundedDiagnosticText(input: InputStream, maxBytes: Int): String {
    require(maxBytes in 1..262144)
    val buffer = ByteArray(maxBytes + 1)
    var read = 0
    while (read < buffer.size) {
        val count = input.read(buffer, read, buffer.size - read)
        if (count < 0) break
        if (count == 0) { val byte = input.read(); if (byte < 0) break; buffer[read++] = byte.toByte() }
        else read += count
    }
    require(read <= maxBytes) { "diagnostic metadata exceeds byte bound" }
    return String(buffer, 0, read, Charsets.UTF_8)
}
