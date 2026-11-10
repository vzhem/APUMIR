package android.util
object Log {
    fun i(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String): Int = 0
    fun d(tag: String, msg: String): Int = 0
    fun e(tag: String, msg: String): Int = 0
    fun e(tag: String, msg: String, error: Throwable): Int = 0
}
