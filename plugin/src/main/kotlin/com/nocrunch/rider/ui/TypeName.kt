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
        /** [knownTypes] is every test class `type` in the same project. */
        fun parse(type: String, knownTypes: Set<String>): TypeName {
            val plus = type.indexOf('+')
            if (plus >= 0) {
                val outer = type.substring(0, plus).split('.')
                val inner = type.substring(plus + 1).split('+', '.')
                return TypeName(outer.dropLast(1), listOf(outer.last()) + inner)
            }
            val segments = type.split('.')
            // An outer class need not be a test class itself; a known `A.Outer+Inner` still proves `A.Outer` is a class.
            val classNames = knownTypes.flatMapTo(HashSet()) { known ->
                val first = known.substringBefore('+')
                if (first == known) listOf(known) else listOf(known, first)
            }
            // Longest wins; the type itself doesn't count, a class isn't its own outer class.
            val outerLength = (segments.size - 1 downTo 1).firstOrNull { segments.take(it).joinToString(".") in classNames }
            val namespaceLength = if (outerLength != null) outerLength - 1 else segments.size - 1
            return TypeName(segments.take(namespaceLength), segments.drop(namespaceLength))
        }
    }
}
