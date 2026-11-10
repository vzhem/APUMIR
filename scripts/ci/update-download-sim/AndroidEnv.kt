package android.os
object Environment { const val DIRECTORY_DOWNLOADS = "Download" }
object SystemClock { fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L }
