// !DIAGNOSTICS: -UNUSED_PARAMETER
open class A
open class B : A()
open class C : B()
open class D : B()

fun foo(x: A) {
    if (x !is B) return

    when (x) {
        is D -> {}
        is C -> {}
    }

    bar(<!DEBUG_INFO_SMARTCAST!>x<!>)
}

fun bar(x: B) {}
