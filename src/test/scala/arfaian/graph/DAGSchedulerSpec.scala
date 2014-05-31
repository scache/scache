package arfaian.graph

import org.scalatest.FlatSpec
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers

class DAGSchedulerSpec extends FlatSpec with MockFactory with Matchers {
  private val nodes = List(1, 2, 3, 4, 5, 6, 7, 8, 9)
  private val edges = List((1, 2), (1, 3), (4, 3), (3, 5), (3, 6), (3, 7), (4, 7), (8, 9))

  "A DAGScheduler" should "return source tasks" in {
    val scheduler = DAGScheduler(nodes, edges)
    val tasks = scheduler.getSources()
    assert(tasks.length == 3)
    assert(tasks.contains(1))
    assert(tasks.contains(4))
    assert(tasks.contains(8))
  }

  it should "return return the correct working set" in {
    val scheduler = DAGScheduler(nodes, edges)
    val tasks = scheduler.updateAndGetDependents(1)
    assert(tasks.size == 1)
    assert(tasks.contains(2))
    val tasks1 = scheduler.updateAndGetDependents(4)
    assert(tasks1.size == 1)
    assert(tasks1.contains(3))
    val tasks2 = scheduler.updateAndGetDependents(1)
    assert(tasks2.size == 1)
    assert(tasks2.contains(2))
    val tasks3 = scheduler.updateAndGetDependents(2)
    assert(tasks3.size == 0)
    val tasks4 = scheduler.updateAndGetDependents(3)
    assert(tasks4.size == 3)
    assert(tasks4.contains(5))
    assert(tasks4.contains(6))
    assert(tasks4.contains(7))
  }
}