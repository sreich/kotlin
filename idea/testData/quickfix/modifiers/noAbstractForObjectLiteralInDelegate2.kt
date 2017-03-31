// "Make 'object : A {}' abstract" "false"
// ACTION: Convert object literal to class
// ACTION: Implement members
// ACTION: Remove unnecessary parentheses
// ERROR: Object must be declared abstract or implement abstract member public abstract fun get(x: Int): Unit defined in A
// ERROR: None of the following functions can be called with the arguments supplied: <br>public final operator fun plus(other: Byte): Int defined in kotlin.Int<br>public final operator fun plus(other: Double): Double defined in kotlin.Int<br>public final operator fun plus(other: Float): Float defined in kotlin.Int<br>public final operator fun plus(other: Int): Int defined in kotlin.Int<br>public final operator fun plus(other: Long): Long defined in kotlin.Int<br>public final operator fun plus(other: Short): Int defined in kotlin.Int

interface A {
    fun get(x: Int)
}

class B : A by 1 + (<caret>object : A {})