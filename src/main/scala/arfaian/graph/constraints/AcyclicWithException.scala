package arfaian.graph.constraints

import scalax.collection.GraphPredef.EdgeLikeIn
import scalax.collection.constrained.ConstraintCompanion
import scalax.collection.constrained.constraints.Acyclic
import scalax.collection.constrained.Graph

object AcyclicWithException extends ConstraintCompanion[Acyclic] {
  def apply[N, E[X] <: EdgeLikeIn[X]](self: Graph[N, E]) =
    new Acyclic[N, E](self) {
      override def onAdditionRefused(refusedNodes: Iterable[N],
                                     refusedEdges: Iterable[E[N]],
                                     graph: Graph[N, E]) = {
        throw new CycleException("Addition to graph refused (violates acyclicality): " +
          "nodes = " + refusedNodes + ", " +
          "edges = " + refusedEdges)
      }
    }
}
class CycleException(msg: String) extends IllegalArgumentException(msg)