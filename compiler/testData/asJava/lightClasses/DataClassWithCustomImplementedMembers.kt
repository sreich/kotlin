// p.C
package p

data class C(val code: String, val name: String) {
    override fun equals(other: Any?): Boolean =
            this === other || other is C && other.code == code

    override fun hashCode(): Int =
            code.hashCode()
}

// LAZINESS:NoLaziness