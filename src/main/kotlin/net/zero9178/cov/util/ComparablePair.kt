package net.zero9178.cov.util

data class ComparablePair<A : Comparable<A>, B : Comparable<B>>(val first: A, val second: B) :
    Comparable<ComparablePair<A, B>> {

    override fun compareTo(other: ComparablePair<A, B>): Int {
        val result = first.compareTo(other.first)
        return if (result != 0) {
            result
        } else {
            second.compareTo(other.second)
        }
    }

    override fun toString() = "($first,$second)"

    fun toPair() = first to second
}

infix fun <A : Comparable<A>, B : Comparable<B>> A.toCP(that: B) = ComparablePair(this, that)