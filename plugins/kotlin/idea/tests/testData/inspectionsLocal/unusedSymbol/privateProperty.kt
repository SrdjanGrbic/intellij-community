// WITH_STDLIB
// PROBLEM: Property "bar" is never used

class Foo {
    private val foo = object {
        var bar<caret>: Int = 42
    }
}