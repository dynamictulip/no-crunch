package com.nocrunch.rider.ui

/**
 * A test class's type name split into its namespace and the classes it is nested in.
 *
 * What MTP reports as a nested class's `type` (checked against the sample projects): MSTest and xUnit v3
 * both report `Namespace.Outer+Inner`, the CLR name. NUnit and TUnit: not checked (no sample project), so
 * `Namespace.Outer.Inner` is also accepted, but only when the outer class is known to be a test class,
 * since otherwise it can't be told from a namespace.
 */
data class TypeName(val namespace: List<String>, val classes: List<String>) {
    companion object {
        /**
         * Convenience for a single name. It derives its lookup from [knownTypes] on every call, so a caller
         * parsing many names must build one [TypeNameParser] per project instead.
         */
        fun parse(type: String, knownTypes: Set<String>): TypeName = TypeNameParser(knownTypes).parse(type)
    }
}

/** Parses type names of one project. [knownTypes] is every test class `type` in that project. */
class TypeNameParser(knownTypes: Set<String>) {
    // An outer class need not be a test class itself; a known `A.Outer+Inner` still proves `A.Outer` is a class.
    // Derived once so each parse is linear in the name's length, not in the project's size.
    private val classNames: Set<String> = knownTypes.flatMapTo(HashSet()) { listOf(it, it.substringBefore('+')) }

    fun parse(type: String): TypeName {
        val plus = type.indexOf('+')
        if (plus >= 0) {
            val outer = type.substring(0, plus).split('.')
            val inner = type.substring(plus + 1).split('+', '.')
            return TypeName(outer.dropLast(1), listOf(outer.last()) + inner)
        }
        val segments = type.split('.')
        // Shortest wins: the outermost known class ends the namespace, so a nested class is never taken for
        // one. The type itself doesn't count, a class isn't its own outer class.
        val prefix = StringBuilder()
        var namespaceLength = segments.size - 1
        for (i in 0 until segments.size - 1) {
            if (i > 0) prefix.append('.')
            prefix.append(segments[i])
            if (prefix.toString() in classNames) {
                namespaceLength = i
                break
            }
        }
        return TypeName(segments.take(namespaceLength), segments.drop(namespaceLength))
    }
}
