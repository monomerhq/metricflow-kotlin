package cc.monomer.metricflow.common.util

/**
 * Returns the first non-`null` argument, or `null` if all are `null`.
 *
 * Mirrors `metricflow_semantics.toolkit.syntactic_sugar.mf_first_non_none`.
 * Helpful when `a ?: b` is the wrong idiom because falsy non-null values
 * (`0`, empty string) should be preserved.
 */
fun <T : Any> mfFirstNonNull(vararg args: T?): T? = args.firstOrNull { it != null }

/**
 * Same as [mfFirstNonNull] but throws via [errorSupplier] (or [IllegalStateException]) when
 * everything is `null`.
 */
fun <T : Any> mfFirstNonNullOrRaise(vararg args: T?, errorSupplier: (() -> Throwable)?): T {
    val first = args.firstOrNull { it != null }
    if (first != null) return first
    if (errorSupplier != null) throw errorSupplier()
    error("Expected at least one non-null argument")
}

/** Default-argument overload — no [errorSupplier]. */
fun <T : Any> mfFirstNonNullOrRaise(vararg args: T?): T =
    mfFirstNonNullOrRaise(*args, errorSupplier = null)

/** Returns an empty map if [optionalMapping] is `null`. */
fun <K, V> mfEnsureMapping(optionalMapping: Map<K, V>?): Map<K, V> = optionalMapping ?: emptyMap()

/**
 * Returns the first item of [iterable] or throws via [errorSupplier] (or [NoSuchElementException]).
 *
 * Mirrors `metricflow_semantics.toolkit.syntactic_sugar.mf_first_item`.
 */
fun <T> mfFirstItem(iterable: Iterable<T>, errorSupplier: (() -> Throwable)?): T {
    val iterator = iterable.iterator()
    if (!iterator.hasNext()) {
        if (errorSupplier != null) throw errorSupplier()
        throw NoSuchElementException("Can't return the first item as the iterable has no items")
    }
    return iterator.next()
}

/** Default-argument overload. */
fun <T> mfFirstItem(iterable: Iterable<T>): T = mfFirstItem(iterable, errorSupplier = null)

/**
 * Flattens an iterable of iterables — equivalent to `itertools.chain.from_iterable`.
 *
 * Kotlin stdlib has [Iterable.flatten], which we re-export under the
 * metricflow-domain name so call sites read like the Python original.
 */
fun <T> mfFlatten(iterables: Iterable<Iterable<T>>): List<T> = iterables.flatten()
