package android.net
class Uri private constructor(private val value: String) {
    override fun toString(): String = value
    companion object {
        val EMPTY = Uri("")
        fun parse(value: String): Uri = Uri(value)
    }
}
