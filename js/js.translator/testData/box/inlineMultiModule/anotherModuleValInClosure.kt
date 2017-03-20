// MODULE: lib
// FILE: lib.kt

package utils

public var LOG: String = ""

inline
public fun log(s: String): String {
    LOG += s
    return LOG
}

inline fun foo() {
    try {
        log("hello")
    }
    catch (e: dynamic) {
        bar()
    }
}

var a = true

inline fun bar() {
    run {
        log("bar1")
        if (LOG === null) return
        log("bar2")
    }
}


// MODULE: main(lib)
// FILE: main.kt

import utils.*

// CHECK_CONTAINS_NO_CALLS: test

internal fun test(s: String): String = log(s + ";")

fun box(): String {
    assertEquals("a;", test("a"))
    assertEquals("a;b;", test("b"))

    foo()

    return "OK"
}