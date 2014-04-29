package arfaian.cache

import java.util.concurrent.Executors

import scala.collection.breakOut
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import com.typesafe.scalalogging.slf4j.LazyLogging

import monifu.concurrent.Cancelable
import monifu.concurrent.Scheduler
import monifu.concurrent.atomic.AtomicAny

/**
 * LoadingCache trait that offers a simple get method to retreive values and a stopAll method to halt reload operations.
 * @author arfaian
 *
 * @param <K>
 * @param <V>
 */
trait LoadingCache[K, V] {
  def get(key: K): Option[V]
  def stopAll(): Unit
}

/**
 * Builder class used to build EagerLoadingCache.
 * @author arfaian
 *
 * @param <K> key type
 * @param <V> value type
 */
class EagerLoadingCacheBuilder[K, V] {
  val elements = scala.collection.concurrent.TrieMap[K, (() => V, Option[FiniteDuration])]()

  def load(k: K, fn: () => V): EagerLoadingCacheBuilder[K, V] = {
    elements += (k -> (fn, None))
    this
  }
  
  def load(k: K, fn: () => V, d: FiniteDuration): EagerLoadingCacheBuilder[K, V] = {
    elements += (k -> (fn, Some(d)))
    this
  }

  def build(): LoadingCache[K, V] = {
    new EagerLoadingCacheClass[K, V](elements.toMap)
  }
}

/**
 * Implementation of EagerLoadingCache[K, V].  Eagerly evaluates values upon initialization.
 * Also schedules and executes load operations for keys for which a duration is defined.
 * Follows the single-writer multiple-reader principle in that values are only updated by
 * a single internally contained thread.  Load operations are delegated to the global
 * ExecutionContext in order to take advantage of parallelism for long-running tasks.
 *
 * @author Arian Arfaian <arfaian>
 *
 * @param <K> key
 * @param <V> value
 */
private class EagerLoadingCacheClass[K, V](val elements: Map[K, (() => V, Option[FiniteDuration])])
  extends LoadingCache[K, V] with LazyLogging {

  checkForDuplicateKeys()

  private val map = prime()
  private val cancelables = initializeScheduler()

  def get(key: K): Option[V] = {
    map.get(key) match {
      case Some(a) => Some(a.get)
      case None => None
    }
  }

  def stopAll(): Unit = {
    logger.info("stopping cache value refresh")
    cancelables.foreach(c => c.cancel)
  }

  private def checkForDuplicateKeys(): Unit = {
    val keys = elements.map { case (k, (fn, d)) => k }(breakOut): Seq[K]
    if (keys.distinct.size != keys.size) {
      throw new IllegalArgumentException("duplicate keys in constructor")
    }
  }

  private def prime(): Map[K, AtomicAny[V]] = {
    logger.info(s"priming cache for ${elements.size} elements")
    val futures = elements.map {
      case (k, (fn, d)) => load(k, fn)
    }

    val map = Await.result(Future.sequence(futures), Duration.Inf).map {
      case (k, v) => (k -> AtomicAny(v))
    }(breakOut): scala.collection.immutable.Map[K, AtomicAny[V]]
    logger.info("completed priming cache")
    map
  }

  private def initializeScheduler(): Seq[Cancelable] = {
    val s = Scheduler.fromExecutorService(Executors.newFixedThreadPool(1))
    elements.filter({
      case (k, (fn, d)) => d.isDefined
    }).map {
      case (k, (fn, d)) => scheduleRefresh(s, k, fn, d)
    }(breakOut): Seq[Cancelable]
  }

  private def scheduleRefresh(s: Scheduler, k: K, fn: () => V, d: Option[FiniteDuration]): Cancelable = {
    val duration = d.get
    s.scheduleRepeated(duration, duration, load(k, fn).map {
      case (k, v) =>
        val atomicAny = map(k)
        atomicAny.lazySet(v)
    })
  }

  private def load(k: K, fn: () => V): Future[(K, V)] = {
    val f = Future { (k, fn()) }
    f.onSuccess {
      case _ => logger.info(s"successfully loaded key for $k")
    }
    f
  }
}
