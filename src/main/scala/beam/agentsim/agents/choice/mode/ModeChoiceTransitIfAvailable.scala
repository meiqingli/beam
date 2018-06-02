package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

import scala.collection.mutable.ArrayBuffer

/**
  * BEAM
  */
class ModeChoiceTransitIfAvailable(val beamServices: BeamServices) extends ModeChoiceCalculator {

  override def clone(): ModeChoiceCalculator = new ModeChoiceTransitIfAvailable(beamServices)

  override def apply(alternatives: Seq[EmbodiedBeamTrip]): Option[EmbodiedBeamTrip] = {
    val containsTransitAlt: ArrayBuffer[Int] = ArrayBuffer[Int]()
    alternatives.zipWithIndex.foreach { alt =>
      if (alt._1.tripClassifier.isTransit) {
        containsTransitAlt += alt._2
      }
    }
    if (containsTransitAlt.nonEmpty) {
      Some(alternatives(containsTransitAlt.head))
    } else if (alternatives.nonEmpty) {
      Some(alternatives(chooseRandomAlternativeIndex(alternatives)))
    } else {
      None
    }
  }

  override def utilityOf(alternative: EmbodiedBeamTrip): Double = 0.0
  override def utilityOf(mode: Modes.BeamMode, cost: Double, time: Double, numTransfers: Int): Double = 0.0
}
