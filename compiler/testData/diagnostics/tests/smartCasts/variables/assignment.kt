fun foo() {
    var v: Any
    v = "abc"
    v = 42
    v.<!UNRESOLVED_REFERENCE!>length<!>()
}
