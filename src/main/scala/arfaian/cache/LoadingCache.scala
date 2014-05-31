package arfaian.cache

import java.util.concurrent.Executors

import scala.collection.immutable.Map
import scala.collection.Seq
import scala.collection.Set
import scala.collection.breakOut
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.{global => globalExecutionContext}
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.reflect.runtime.universe._
import scala.util.Failure
import scala.util.Success

import com.typesafe.scalalogging.slf4j.LazyLogging

import arfaian.graph.DAGScheduler
import monifu.concurrent.Cancelable
import monifu.concurrent.Scheduler
import monifu.concurrent.atomic.AtomicAny

/**
 * LoadingCache trait that offers a simple get method to retreive values and a stopAll method to halt reload operations.
 *
 * @author Arian Arfaian <arfaian>
 *
 * @param <K> key type
 * @param <V> value type
 */
trait LoadingCache[K, V] {
  def get(key: K): Option[V]
  def stopAll(): Unit
}

/**
 * Builder class used to build instances of EagerLoadingCacheClass. Values are not loaded until cache is built. Not thread-safe.
 *
 * @author Arian Arfaian <arfaian>
 *
 * @param <K> key type
 * @param <V> value type
 */
class EagerLoadingCacheBuilder[K, V](private val callback: (K, V) => _ = (k: K, v: V) => (),
                                     private val executionContext: ExecutionContext = globalExecutionContext) {
  val elements = Map.newBuilder[K, (() => V, Option[FiniteDuration])]

  def load(k: K, fn: () => V): EagerLoadingCacheBuilder[K, V] = {
    load(k, fn, None)
  }

  def load(k: K, fn: () => V, d: FiniteDuration): EagerLoadingCacheBuilder[K, V] = {
    load(k, fn, Some(d))
  }

  private def load(k: K, fn: () => V, d: Option[FiniteDuration]): EagerLoadingCacheBuilder[K, V] = {
    elements += k -> (fn, d)
    this
  }

  def build(): LoadingCache[K, V] = {
    new EagerLoadingCacheImpl[K, V](elements.result, callback)(executionContext)
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
 * @param <K> key type
 * @param <V> value type
 */
private class EagerLoadingCacheImpl[K, V](private val elements: Map[K, (() => V, Option[FiniteDuration])],
                                          private val callback: (K, V) => _)(implicit private val ec: ExecutionContext)
    extends LoadingCache[K, V] with LazyLogging {

  protected val map = prime(elements)
  private val cancelables = initializeScheduler()

  override def get(key: K): Option[V] = {
    map.get(key).map(v => v.get)
  }

  override def stopAll(): Unit = {
    logger.info("stopping cache value refresh")
    cancelables.foreach(c => c.cancel)
  }

  private def prime(els: Map[K, (() => V, Option[FiniteDuration])]): Map[K, AtomicAny[V]] = {
    logger.info(s"priming cache for ${els.size} elements")
    val futures = els.map {
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
    s.scheduleRepeated(duration, duration, load(k, fn).map(f => onLoadComplete(f._1, f._2)))
  }

  private def onLoadComplete(k: K, v: V) = {
    val atomicAny = map(k)
    val oldValue = atomicAny.get
    atomicAny.lazySet(v)
    callback(k, oldValue)
  }

  private def load(k: K, fn: () => V): Future[(K, V)] = {
    val f = Future { (k, fn()) }(ec)
    f.onComplete {
      case Success(_) => logger.info(s"successfully loaded key for $k")
      case Failure(t) =>
        logger.info(s"error loading value for $k: ${t.getMessage}")
        throw t
    }
    f
  }
}

/**
 * Builder class used to build instances of LoadingCache. Values are not loaded until cache is built. Not thread-safe.
 *
 * @author Arian Arfaian <arfaian>
 *
 * @param <K> key type
 * @param <V> value type
 */
class EagerCascadedLoadingCacheBuilder[K: TypeTag, V](private val callback: (K, V) => _ = (k: K, v: V) => (),
                                                      private val executionContext: ExecutionContext = globalExecutionContext) {
  private var nodes = Set.empty[K]
  private var edges = List.empty[(K, K)]
  val independents = Map.newBuilder[K, (() => V, Option[FiniteDuration])]
  val dependents = Map.newBuilder[K, Map[K, V] => V]

  def load(k: K, fn: () => V): EagerCascadedLoadingCacheBuilder[K, V] = {
    load(k, fn, None)
  }
  
  def load(k: K, fn: () => V, d: FiniteDuration): EagerCascadedLoadingCacheBuilder[K, V] = {
    load(k, fn, Some(d))
  }
  
  def load(k: K, fn: Map[K, V] => V, dependencies: K*): EagerCascadedLoadingCacheBuilder[K, V] = {
    nodes += k
    edges ++= dependencies.map(d => (d, k))
    dependents += k -> fn
    this
  }
  
  private def load(k: K, fn: () => V, d: Option[FiniteDuration]): EagerCascadedLoadingCacheBuilder[K, V] = {
    nodes += k
    independents += k -> (fn, d)
    this
  }

  def build(): LoadingCache[K, V] = {
    val scheduler = DAGScheduler(nodes.toList, edges)
    val independentsMap = independents.result
    val dependentsMap = dependents.result
    val map = EagerCascadedLoadingCachePrimer(independentsMap, dependentsMap, scheduler)(executionContext)
    new EagerCascadedLoadingCacheImpl[K, V](map, independentsMap, dependentsMap, scheduler, callback)(executionContext)
  }
}

case object EagerCascadedLoadingCachePrimer {
  def apply[K, V](independents: Map[K, (() => V, Option[FiniteDuration])],
                  dependents: Map[K, Map[K, V] => V],
                  scheduler: DAGScheduler[K])(implicit ec: ExecutionContext): Map[K, AtomicAny[V]] = {
    var resultsMap = Map.empty[K, V]
    for (k <- scheduler.getExecutionSequence()) {
      val data = independents.get(k)
      val f = if (data.isEmpty) {
        val fn = dependents(k)
        val args: Map[K, V] = scheduler.getInNeighbors(k).map {
          n => n -> resultsMap(n)
        }(breakOut)
        Future { (k, fn(args)) }
      } else {
        Future { (k, data.get._1()) }
      }
      val (resultK, resultV) = Await.result(f, Duration.Inf)
      resultsMap += (resultK -> resultV)
    }
    resultsMap.map { case (k, v) => k -> AtomicAny(v) }
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
 * @param <K> key type
 * @param <V> value type
 */
private class EagerCascadedLoadingCacheImpl[K, V](private val map: Map[K, AtomicAny[V]],
                                                  private val independents: Map[K, (() => V, Option[FiniteDuration])],
                                                  private val dependents: Map[K, Map[K, V] => V],
                                                  private val scheduler: DAGScheduler[K],
                                                  private val callback: (K, V) => _)(implicit private val ec: ExecutionContext) extends LoadingCache[K, V] with LazyLogging {
  private val cancelables = initializeScheduler()
  
  override def get(key: K): Option[V] = {
    map.get(key).map(v => v.get)
  }

  override def stopAll(): Unit = {
    logger.info("stopping cache value refresh")
    cancelables.foreach(c => c.cancel)
  }

  private def initializeScheduler(): Seq[Cancelable] = {
    val s = Scheduler.fromExecutorService(Executors.newFixedThreadPool(1))
    independents.filter({
      case (k, (fn, d)) => d.isDefined
    }).map {
      case (k, (fn, d)) => scheduleRefresh(s, k, fn, d)
    }(breakOut): Seq[Cancelable]
  }

  private def scheduleRefresh(s: Scheduler, k: K, fn: () => V, d: Option[FiniteDuration]): Cancelable = {
    val duration = d.get
    s.scheduleRepeated(duration, duration, load(k, fn).map(f => onLoadComplete(f._1, f._2)))
  }

  private def load(k: K, fn: () => V): Future[(K, V)] = {
    val f = Future { (k, fn()) }(ec)
    f.onComplete {
      case Success(_) =>
        loadDependents(k)
        logger.info(s"successfully loaded key for $k")
      case Failure(t) =>
        logger.info(s"error loading value for $k: ${t.getMessage}")
        throw t
    }
    f
  }

  private def load(k: K, fn: Map[K, V] => V, args: Map[K, V]): Future[(K, V)] = {
    val f = Future { (k, fn(args)) }(ec)
    f.onComplete {
      case Success(_) =>
        loadDependents(k)
        logger.info(s"successfully loaded key for $k")
      case Failure(t) =>
        logger.info(s"error loading value for $k: ${t.getMessage}")
        throw t
    }
    f
  }

  private def loadDependents(k: K) = {
    Future {
      Thread.sleep(100)
      val t = scheduler.getInNeighbors(k).map {
        in => in -> (scheduler.getOutNeighbors(in).map((parentKey) => parentKey -> map(parentKey))(breakOut): Map[K, AtomicAny[V]])
      }(breakOut): Map[K, Map[K, AtomicAny[V]]]
      t.foreach {
        case (k, v) => load(k, dependents(k), v.map { case (k, v) => k -> v.get })
      }
    }
  }

  private def onLoadComplete(k: K, v: V) = {
    val atomicAny = map(k)
    val oldValue = atomicAny.get
    atomicAny.lazySet(v)
    callback(k, oldValue)
  }
}
