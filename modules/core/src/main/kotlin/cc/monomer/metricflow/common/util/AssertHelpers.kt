package cc.monomer.metricflow.common.util

/**
 * Mirror of `metricflow_semantics.toolkit.assert_one_arg`.
 *
 * Throws [IllegalStateException] if zero or more than one argument is non-null.
 * Used by call sites that accept several mutually exclusive optional arguments.
 */
fun assertExactlyOneArgSet(vararg args: Pair<String, Any?>) {
    val numSet = args.count { (_, v) -> v != null }
    check(numSet == 1) {
        "$numSet argument(s) set instead of 1 in arguments: ${args.toMap()}"
    }
}

/** Throws [IllegalStateException] if more than one argument is non-null. */
fun assertAtMostOneArgSet(vararg args: Pair<String, Any?>) {
    val numSet = args.count { (_, v) -> v != null }
    check(numSet <= 1) {
        "$numSet argument(s) set instead of <=1 in arguments: ${args.toMap()}"
    }
}
