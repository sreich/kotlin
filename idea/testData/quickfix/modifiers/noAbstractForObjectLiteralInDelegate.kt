// "Make 'object : A {}' abstract" "false"
// ACTION: Convert object literal to class
// ACTION: Implement members
// ERROR: Object must be declared abstract or implement abstract member public abstract fun get(x: Int): Unit defined in A

interface A {
    fun get(x: Int)
}

class B : A by <caret>object : A {}