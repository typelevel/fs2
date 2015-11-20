package fs2.internal

import scala.collection.concurrent.TrieMap
import java.util.concurrent.atomic.AtomicLong

private[fs2] class ConcurrentLinkedMap[K,V](
    entries: TrieMap[K,(V,Long)],
    insertionOrder: TrieMap[Long,K],
    ids: AtomicLong)
{
  def get(k: K): Option[V] = entries.get(k).map(_._1)
  def update(k: K, v: V): Unit = {
    val id = ids.getAndIncrement
    entries.update(k, v -> id)
    insertionOrder.update(id, k)
  }
  def updated(k: K, v: V): ConcurrentLinkedMap[K,V] = { update(k,v); this }

  def remove(k: K): Unit = entries.get(k) match {
    case None => ()
    case Some((v,id)) => entries.remove(k); insertionOrder.remove(id)
  }
  def removed(k: K): ConcurrentLinkedMap[K,V] = { remove(k); this }

  /** The keys of this map, in the order they were added. */
  def keys: Iterable[K] = insertionOrder.toList.sortBy(_._1).map(_._2)
  /** The values in this map, in the order they were added. */
  def values: Iterable[V] = keys.flatMap(k => entries.get(k).toList.map(_._1))
}

private[fs2] object ConcurrentLinkedMap {
  def empty[K,V]: ConcurrentLinkedMap[K,V] =
    new ConcurrentLinkedMap[K,V](TrieMap.empty, TrieMap.empty, new AtomicLong(0L))
}
