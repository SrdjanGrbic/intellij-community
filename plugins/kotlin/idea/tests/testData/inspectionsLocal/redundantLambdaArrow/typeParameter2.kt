// PROBLEM: Redundant lambda arrow
class Foo<T>(val t: T)

fun bar(foo: Foo<(Boolean) -> String>) {}

fun test() {
    bar(Foo({ <caret>_ -> "" }))
}