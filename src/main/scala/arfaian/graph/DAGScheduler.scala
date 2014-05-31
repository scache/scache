package arfaian.graph

import scala.collection.breakOut
import scala.reflect.runtime.universe._

import arfaian.graph.constraints.AcyclicWithException
import monifu.concurrent.atomic.Atomic
import scalax.collection.GraphEdge.DiEdge
import scalax.collection.GraphPredef.EdgeAssoc
import scalax.collection.GraphTraversal.VisitorReturn.Continue
import scalax.collection.constrained.Config
import scalax.collection.constrained.constraintToConfig
import scalax.collection.constrained.immutable.Graph

trait DAGScheduler[T] {
  def getExecutionSequence(): List[T]
  def getOutNeighbors(value: T): Set[T]
  def getInNeighbors(value: T): Set[T]
  def getSources(): List[T]
  def updateAndGetDependents(key: T): Set[T]
}

object DAGScheduler {
  def apply[T: TypeTag](nodes: List[T], edges: List[(T, T)]): DAGScheduler[T] = {
    implicit val conf: Config = AcyclicWithException
    new DAGSchedulerImpl(Graph.from(nodes, edges.map { case (a, b) => a ~> b }))
  }

  private class DAGSchedulerImpl[T: TypeTag](private val graph: Graph[T, DiEdge]) extends DAGScheduler[T] {
    private type N = graph.NodeT
    private val sources = graph.degreeNodeSeq(graph.InDegree, degreeFilter = _ == 0).toList.map(_._2)
    private val state = DependencyState(createDependantsMap(), createInDegreeCountMap())

    override def getExecutionSequence(): List[T] = {
      tsort(sources).map(_.value)
    }
    
    override def getOutNeighbors(value: T): Set[T] = {
      graph.get(value).outNeighbors.map(_.value)(breakOut)
    }
    
    override def getInNeighbors(value: T): Set[T] = {
      graph.get(value).inNeighbors.map(_.value)(breakOut)
    }

    override def getSources(): List[T] = {
      sources.map(_.value)
    }

    override def updateAndGetDependents(key: T): Set[T] = {
      state.updateAndGetNext(key).filter(k => k.isDefined).map(o => o.get)
    }

    private def createInDegreeCountMap() = {
      graph.nodes.map {
        n => (n -> n.inDegree)
      } toMap
    }

    private def createDependantsMap() = {
      graph.nodes.map {
        n => (n.value -> n.outNeighbors.toSet)
      } toMap
    }

    /**
     * topological sort
     */
    private def tsort(s: List[N]) = {
      var sorted = List.empty[N]
      var sourceNodes = s
      var deletedEdges = Set[(N, N)]()
      while (sourceNodes.nonEmpty) {
        val (n :: rest) = sourceNodes
        sourceNodes = rest
        sorted ::= n
        n.outNeighbors.filter(m => !deletedEdges.contains((m, n))).foreach { m =>
          deletedEdges += ((n, m))
          if (m.inNeighbors.filterNot(in => deletedEdges.contains((in, m))).isEmpty && !sourceNodes.contains(m))
            sourceNodes ::= m
          Continue
        }
      }
      sorted.reverse
    }

    private case class DependencyState(dependants: Map[T, Set[N]], remainingDependencies: Map[N, Int]) {
      private val currentInDegreeCount = Atomic(remainingDependencies)
      def updateAndGetNext(key: T) = {
        dependants(key).map { dep =>
          currentInDegreeCount.transformAndExtract {
            count =>
              val decrementedCount = (count(dep) - 1)
              val opt = if (decrementedCount == 0) Some(dep.value) else None
              val updatedCount = if (decrementedCount == 0) remainingDependencies(dep) else decrementedCount
              (opt, count + (dep -> updatedCount))
          }
        }
      }
    }
  }
}
