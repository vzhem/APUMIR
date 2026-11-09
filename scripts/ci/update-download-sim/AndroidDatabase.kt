package android.database
interface Cursor : AutoCloseable {
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getColumnIndex(name: String): Int
    fun getColumnIndexOrThrow(name: String): Int
    fun getInt(index: Int): Int
    fun getLong(index: Int): Long
    fun getString(index: Int): String?
}
