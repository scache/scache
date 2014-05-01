package arfaian.cache

import java.util.concurrent.Executors

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import org.scalamock.scalatest.MockFactory
import org.scalatest.FlatSpec
import org.scalatest.Matchers

import monifu.concurrent.atomic.AtomicInt

class EagerLoadingCacheSpec extends FlatSpec with MockFactory with Matchers {

  "An EagerLoadingCache" should "load values before allowing clients to call get" in {
    val cache = new EagerLoadingCacheBuilder().load("a", () => { Thread.sleep(10); "aValue" })
      .load("b", () => { Thread.sleep(10); "bValue" })
      .load("c", () => { Thread.sleep(10); "cValue" })
      .build()
    assert(cache.get("a").get == "aValue")
    assert(cache.get("b").get == "bValue")
    assert(cache.get("c").get == "cValue")
  }

  it should "reload values that expire" in {
    val atomicInteger = AtomicInt(0);
    val cache = new EagerLoadingCacheBuilder()
      .load("k", () => { Thread.sleep(1); atomicInteger.incrementAndGet(1) }, 500.millis).build()
    Thread.sleep(1000)
    cache.get("k") match {
      case Some(i) => assert(i > 0)
      case None => fail
    }
    cache.stopAll
  }

  it should "contain values that are loaded" in {
    val cache = new EagerLoadingCacheBuilder()
      .load("k", () => { "v" }).build()
    assert(cache.get("k").isDefined)
  }

  it should "return empty option for invalid key" in {
    val cache = new EagerLoadingCacheBuilder()
      .load("k", () => { "v" }).build()
    assert(cache.get("a").isEmpty)
  }

  it should "handle multiple threads while reloading expired values" in {
    val cache = new EagerLoadingCacheBuilder()
      .load("test3", () => { new TestObject() }, 1.milli).build()
    implicit val c = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(20))
    val tasks: Seq[Future[TestObject]] = for (i <- 1 to 10000) yield Future {
      val value = cache.get("test3").get
      println(value)
      value
    }

    val aggregated = Future.sequence(tasks)
    val values: Seq[TestObject] = Await.result(aggregated, 15.seconds)
    cache.stopAll
  }

  it should "call the remove callback method when a value is reloaded" in {
    val p = mockFunction[String, TestObject, String]
    val o = new TestObject()
    p.expects("test3", o).anyNumberOfTimes
    val cache = new EagerLoadingCacheBuilder()
      .load("test3", () => { o }, 1.milli).removalListener(p) build ()
  }

  private case class TestObject()

}