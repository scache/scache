package arfaian.graph.constraints

import org.scalatest.FlatSpec
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers
import scalax.collection.GraphEdge.DiEdge
import scalax.collection.GraphPredef.EdgeAssoc
import scalax.collection.constrained.Config
import scalax.collection.constrained.Graph

class AcyclicWithExceptionSpec extends FlatSpec with MockFactory with Matchers {

  "An AcyclicWithException" should "throw an excetion when creating a cyclic graph" in {

    intercept[Exception] {
      val cyclicNodes = List(1, 2, 3)
      val cyclicEdges = List((1 ~> 2), (2 ~> 3), (3 ~> 1))
      implicit val conf: Config = AcyclicWithException
      Graph.from(cyclicNodes, cyclicEdges)
    }
  }
}