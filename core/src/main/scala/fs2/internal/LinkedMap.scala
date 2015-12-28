package fs2.internal

import scala.collection.immutable.LongMap

/**
 * A Map which tracks the insertion order of entries, so that entries may be
 * traversed in the order they were inserted.
 */

import scala.collection.immutable.LongMap

private[fs2] class LinkedMap[K,+V](
  entries: Map[K,(V,Long)],
  insertionOrder: LongMap[K],
  nextID: Long) {

  def get(k: K): Option[V] = entries.get(k).map(_._1)

  /** Insert an entry into this map, overriding any previous entry for the given `K`. */
  def updated[V2>:V](k: K, v: V2): LinkedMap[K,V2] =
    new LinkedMap(entries.updated(k, (v,nextID)), insertionOrder.updated(nextID, k), nextID+1)

  /** Remove this key from this map. */
  def -(k: K) = new LinkedMap(
    entries - k,
    entries.get(k).map { case (_,id) => insertionOrder - id }.getOrElse(insertionOrder),
    nextID)

  /** The keys of this map, in the order they were added. */
  def keys: Iterable[K] = insertionOrder.values

  /** The values in this map, in the order they were added. */
  def values: Iterable[V] = keys.flatMap(k => entries.get(k).toList.map(_._1))

  def isEmpty = entries.isEmpty
}

private[fs2] object LinkedMap {
  def empty[K,V] = new LinkedMap[K,V](Map.empty, LongMap.empty, 0)
}

private[fs2] class LinkedSet[K](ks: LinkedMap[K,Unit]) {
  def +(k: K) = new LinkedSet(ks.updated(k, ()))
  def -(k: K) = new LinkedSet(ks - k)
  def values: Iterable[K] = ks.keys
  def iterator = values.iterator
  def isEmpty = ks.isEmpty
}

private[fs2] object LinkedSet {
  def empty[K]: LinkedSet[K] = new LinkedSet(LinkedMap.empty)
}
