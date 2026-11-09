package android.net
class Uri private constructor(private val value: String) {
    override fun toString(): String = value
    companion object { fun parse(value: String): Uri = Uri(value) }
}
