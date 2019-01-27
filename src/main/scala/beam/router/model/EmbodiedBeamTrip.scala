package beam.router.model

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, CAV, DRIVE_TRANSIT, RIDE_HAIL, RIDE_HAIL_POOLED, RIDE_HAIL_TRANSIT, TRANSIT, WALK, WALK_TRANSIT}
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

case class EmbodiedBeamTrip(legs: IndexedSeq[EmbodiedBeamLeg]) {

  @transient
  lazy val costEstimate: Double = legs.map(_.cost).sum /// Generalize or remove

  @transient
  lazy val tripClassifier: BeamMode = determineTripMode(legs)

  @transient
  lazy val vehiclesInTrip: IndexedSeq[Id[Vehicle]] = determineVehiclesInTrip(legs)

  @transient
  lazy val requiresReservationConfirmation: Boolean = tripClassifier != WALK && legs.exists(
    !_.asDriver
  )

  val totalTravelTimeInSecs: Int = legs.map(_.beamLeg.duration).sum

  def beamLegs(): IndexedSeq[BeamLeg] =
    legs.map(embodiedLeg => embodiedLeg.beamLeg)

  def toBeamTrip: BeamTrip = BeamTrip(beamLegs())

  def determineTripMode(legs: IndexedSeq[EmbodiedBeamLeg]): BeamMode = {
    var theMode: BeamMode = WALK
    var hasUsedCar: Boolean = false
    var hasUsedRideHail: Boolean = false
    legs.foreach { leg =>
      // Any presence of transit makes it transit
      if (leg.beamLeg.mode.isTransit) {
        theMode = TRANSIT
      } else if (theMode == WALK && leg.isRideHail) {
        if (leg.isPooledTrip) {
          theMode = RIDE_HAIL_POOLED
        } else {
          theMode = RIDE_HAIL
        }
      } else if (theMode == WALK && leg.beamLeg.mode == CAR) {
        theMode = CAR
      } else if (theMode == WALK && leg.beamLeg.mode == CAV) {
        theMode = CAV
      } else if (theMode == WALK && leg.beamLeg.mode == BIKE) {
        theMode = BIKE
      }
      if (leg.beamLeg.mode == CAR) hasUsedCar = true
      if (leg.isRideHail) hasUsedRideHail = true
    }
    if (theMode == TRANSIT && hasUsedRideHail) {
      RIDE_HAIL_TRANSIT
    } else if (theMode == TRANSIT && hasUsedCar) {
      DRIVE_TRANSIT
    } else if (theMode == TRANSIT && !hasUsedCar) {
      WALK_TRANSIT
    } else {
      theMode
    }
  }

  def updateStartTime(newStartTime: Int): EmbodiedBeamTrip = {
    val deltaStart = newStartTime - legs.head.beamLeg.startTime
    this.copy(legs = legs.map { leg =>
      leg.copy(beamLeg = leg.beamLeg.updateStartTime(leg.beamLeg.startTime + deltaStart))
    })
  }

  def determineVehiclesInTrip(legs: IndexedSeq[EmbodiedBeamLeg]): IndexedSeq[Id[Vehicle]] = {
    legs.map(leg => leg.beamVehicleId).distinct
  }

  override def toString: String = {
    s"EmbodiedBeamTrip($tripClassifier starts ${legs.headOption
      .map(head => head.beamLeg.startTime)
      .getOrElse("empty")} legModes ${legs.map(_.beamLeg.mode).mkString(",")})"
  }
}

object EmbodiedBeamTrip {
  val empty: EmbodiedBeamTrip = EmbodiedBeamTrip(Vector())
  def dummyCAVAt(tick: Int, bodyId: Id[Vehicle], cavVehicleId: Id[Vehicle], cavVehicleTypeId: Id[BeamVehicleType]): EmbodiedBeamTrip = {
    val walk1 = EmbodiedBeamLeg.dummyLegAt(tick, bodyId, false)
    val cavLeg = EmbodiedBeamLeg.dummyLegAt(tick+1, cavVehicleId, false, CAV, cavVehicleTypeId, asDriver = false)
    val walk2 = EmbodiedBeamLeg.dummyLegAt(tick+2, bodyId, true)
    EmbodiedBeamTrip(Vector(walk1, cavLeg, walk2))
  }
}
