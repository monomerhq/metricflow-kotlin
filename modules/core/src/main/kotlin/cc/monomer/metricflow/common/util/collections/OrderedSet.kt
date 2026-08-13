package cc.monomer.metricflow.common.util.collections

/**
 * A `Set` whose iteration order is the insertion order.
 *
 * Port of `metricflow_semantics.toolkit.collections.ordered_set.OrderedSet`.
 *
 * In Python the type is split into `OrderedSet` (abstract), `FrozenOrderedSet`
 * (hashable, immutable), and `MutableOrderedSet`. Kotlin doesn't need the
 * frozen vs mutable split as sharply: `Set` is read-only by interface and the
 * two implementations below cover both behaviours.
 *
 * Equality and hashing are order-independent (matching Python: it compares
 * via `frozenset(self._set_as_dict)`).
 */
sealed interface OrderedSet<E> : Set<E> {
    fun asMutable(): MutableOrderedSet<E>
    fun asFrozen(): FrozenOrderedSet<E>
    fun copy(): OrderedSet<E>
    fun difference(vararg others: Iterable<E>): OrderedSet<E>
    fun intersection(vararg others: Iterable<E>): OrderedSet<E>
    fun union(vararg others: Iterable<E>): OrderedSet<E>
}

/** Immutable, hashable ordered set. */
class FrozenOrderedSet<E> private constructor(
    private val backing: LinkedHashMap<E, Unit>,
) : OrderedSet<E> {

    constructor(iterable: Iterable<E>) : this(linkedMapOf<E, Unit>().also { map ->
        for (item in iterable) map.putIfAbsent(item, Unit)
    })

    constructor() : this(linkedMapOf<E, Unit>())

    private val cachedHash: Int by lazy { backing.keys.toHashSet().hashCode() }

    override val size: Int get() = backing.size
    override fun isEmpty(): Boolean = backing.isEmpty()
    override fun iterator(): Iterator<E> = backing.keys.iterator()
    override fun containsAll(elements: Collection<E>): Boolean = backing.keys.containsAll(elements)
    override fun contains(element: E): Boolean = backing.containsKey(element)

    override fun asMutable(): MutableOrderedSet<E> = MutableOrderedSet<E>(backing.keys)
    override fun asFrozen(): FrozenOrderedSet<E> = this

    override fun copy(): FrozenOrderedSet<E> {
        val newBacking = LinkedHashMap<E, Unit>(backing.size)
        for (k in backing.keys) newBacking[k] = Unit
        return FrozenOrderedSet(newBacking)
    }

    override fun difference(vararg others: Iterable<E>): FrozenOrderedSet<E> {
        val remove = HashSet<E>()
        for (other in others) remove.addAll(other)
        return FrozenOrderedSet(backing.keys.filter { it !in remove })
    }

    override fun intersection(vararg others: Iterable<E>): FrozenOrderedSet<E> {
        val common = HashSet(backing.keys)
        for (other in others) common.retainAll(other.toSet())
        return FrozenOrderedSet(backing.keys.filter { it in common })
    }

    override fun union(vararg others: Iterable<E>): FrozenOrderedSet<E> {
        val result = LinkedHashMap<E, Unit>(backing)
        for (other in others) for (item in other) result.putIfAbsent(item, Unit)
        return FrozenOrderedSet(result)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Set<*>) return false
        return backing.keys.size == other.size && backing.keys.containsAll(other)
    }

    override fun hashCode(): Int = cachedHash

    override fun toString(): String = backing.keys.joinToString(prefix = "{", postfix = "}")
}

/** Mutable ordered set — analog of Python's `MutableOrderedSet`. */
class MutableOrderedSet<E> : OrderedSet<E>, MutableSet<E> {

    private val backing: LinkedHashMap<E, Unit>

    constructor() {
        backing = linkedMapOf()
    }

    constructor(iterable: Iterable<E>) {
        backing = linkedMapOf()
        for (item in iterable) backing.putIfAbsent(item, Unit)
    }

    override val size: Int get() = backing.size
    override fun isEmpty(): Boolean = backing.isEmpty()
    override fun iterator(): MutableIterator<E> = backing.keys.iterator()
    override fun containsAll(elements: Collection<E>): Boolean = backing.keys.containsAll(elements)
    override fun contains(element: E): Boolean = backing.containsKey(element)

    override fun add(element: E): Boolean = backing.put(element, Unit) == null
    override fun remove(element: E): Boolean = backing.remove(element) != null

    override fun addAll(elements: Collection<E>): Boolean {
        var changed = false
        for (e in elements) if (add(e)) changed = true
        return changed
    }

    override fun removeAll(elements: Collection<E>): Boolean {
        var changed = false
        for (e in elements) if (remove(e)) changed = true
        return changed
    }

    override fun retainAll(elements: Collection<E>): Boolean {
        val keep = elements.toSet()
        val iter = backing.keys.iterator()
        var changed = false
        while (iter.hasNext()) {
            val next = iter.next()
            if (next !in keep) {
                iter.remove()
                changed = true
            }
        }
        return changed
    }

    override fun clear() { backing.clear() }

    fun update(vararg iterables: Iterable<E>) {
        for (iter in iterables) for (item in iter) backing.putIfAbsent(item, Unit)
    }

    override fun asMutable(): MutableOrderedSet<E> = this
    override fun asFrozen(): FrozenOrderedSet<E> = FrozenOrderedSet(backing.keys)

    override fun copy(): MutableOrderedSet<E> {
        val out = MutableOrderedSet<E>()
        for (k in backing.keys) out.backing.put(k, Unit)
        return out
    }

    override fun difference(vararg others: Iterable<E>): MutableOrderedSet<E> {
        val remove = HashSet<E>()
        for (other in others) remove.addAll(other)
        return MutableOrderedSet(backing.keys.filter { it !in remove })
    }

    override fun intersection(vararg others: Iterable<E>): MutableOrderedSet<E> {
        val common = HashSet(backing.keys)
        for (other in others) common.retainAll(other.toSet())
        return MutableOrderedSet(backing.keys.filter { it in common })
    }

    override fun union(vararg others: Iterable<E>): MutableOrderedSet<E> {
        val out = MutableOrderedSet(backing.keys)
        for (other in others) for (item in other) out.add(item)
        return out
    }

    /** Removes and returns the first item. Throws [NoSuchElementException] if empty. */
    fun pop(): E {
        val iter = backing.keys.iterator()
        if (!iter.hasNext()) throw NoSuchElementException("Can't pop an item as the set is empty.")
        val first = iter.next()
        iter.remove()
        return first
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Set<*>) return false
        return backing.keys.size == other.size && backing.keys.containsAll(other)
    }

    override fun hashCode(): Int = backing.keys.toHashSet().hashCode()

    override fun toString(): String = backing.keys.joinToString(prefix = "{", postfix = "}")
}
