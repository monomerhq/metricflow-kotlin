package cc.monomer.metricflow.common.util

import java.security.MessageDigest
import kotlin.random.Random

/** Default length for [mfRandomId] — matches the Python `length=8` default. */
const val MF_RANDOM_ID_DEFAULT_LENGTH: Int = 8

/** Default alphabet for [mfRandomId] (excludes glyphs that descend below the line for visual cleanliness). */
const val MF_RANDOM_ID_EXCLUDED_CHARACTERS: String = "gjpqy"

private const val ID_ALPHABET: String = "abcdefghijklmnopqrstuvwxyz0123456789"

/**
 * Generates a random alphanumeric string suitable for `mfd_<id>`-style node IDs.
 *
 * Port of `metricflow_semantics.toolkit.id_helpers.mf_random_id`. Pass
 * [MF_RANDOM_ID_DEFAULT_LENGTH] and [MF_RANDOM_ID_EXCLUDED_CHARACTERS] for
 * the Python defaults.
 */
fun mfRandomId(length: Int, excludedCharacters: String): String {
    val filtered = ID_ALPHABET.filterNot { it in excludedCharacters }
    require(filtered.isNotEmpty()) { "All characters in the alphabet were excluded." }
    val sb = StringBuilder(length)
    repeat(length) { sb.append(filtered[Random.nextInt(filtered.length)]) }
    return sb.toString()
}

/** Default-flavoured overload (matches the Python `mf_random_id()` zero-arg call). */
fun mfRandomId(): String = mfRandomId(MF_RANDOM_ID_DEFAULT_LENGTH, MF_RANDOM_ID_EXCLUDED_CHARACTERS)

/**
 * Produces a hex SHA-1 hash from the concatenation of every item's `toString()`.
 *
 * Port of `metricflow_semantics.toolkit.id_helpers.mf_sha1_iterables`.
 * Order matters — the iterables are walked in argument order, and items in
 * each iterable in their iteration order.
 */
fun mfSha1Iterables(vararg iterables: Iterable<Any>): String {
    val digest = MessageDigest.getInstance("SHA-1")
    for (iterable in iterables) {
        for (item in iterable) {
            digest.update(item.toString().toByteArray(Charsets.UTF_8))
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
