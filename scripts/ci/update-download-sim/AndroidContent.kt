package android.content
import android.content.pm.PackageManager
open class Context {
    companion object { const val DOWNLOAD_SERVICE = "download"; const val MODE_PRIVATE = 0 }
    open fun getSystemService(name: String): Any? = null
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("not implemented")
    open fun getString(id: Int, vararg args: Any): String = "Update · ${args.firstOrNull()}"
    open val packageManager: PackageManager get() = error("not implemented")
    open val packageName: String get() = "com.vladimir.messenger"
}
interface SharedPreferences {
    fun getLong(key: String, defValue: Long): Long
    fun getString(key: String, defValue: String?): String?
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun contains(key: String): Boolean
    fun edit(): Editor
    interface Editor {
        fun putLong(key: String, value: Long): Editor
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putStringSet(key: String, value: Set<String>?): Editor
        fun remove(key: String): Editor
        fun commit(): Boolean
        fun apply()
    }
}
