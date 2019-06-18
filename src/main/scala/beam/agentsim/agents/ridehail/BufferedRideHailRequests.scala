package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.utils.DebugLib
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

class BufferedRideHailRequests(
  val scheduler: ActorRef
) {

  private var tick: Double = _
  private var triggerId: Long = _

  def newTimeout(tick: Double, triggerId: Long): Unit = {
    this.tick = tick
    this.triggerId = triggerId
  }

  def getTick(): Double = { tick }

  //println(s"creating BufferedRideHailRequests, tick: $tick")

  // TODO: make private (don't allow external access to these)
  // the completion triggers for the current timeout
  private var nextBufferedTriggerMessages = Vector[BeamAgentScheduler.ScheduleTrigger]()

  // these are the vehicleIds with which we are overwriting things
  private val setOfReplacementVehicles =
    mutable.Set[Id[Vehicle]]()

  var numberOfOverwriteRequestsOpen: Int = 0

  def setNumberOfOverwriteRequests(numRequests: Integer): Unit = {
    numberOfOverwriteRequestsOpen = numRequests
  }

  def decreaseNumberOfOpenOverwriteRequests(): Unit = {
    numberOfOverwriteRequestsOpen = numberOfOverwriteRequestsOpen - 1
  }

  def increaseNumberOfOpenOverwriteRequests(): Unit = {
    numberOfOverwriteRequestsOpen = numberOfOverwriteRequestsOpen + 1
  }

  def registerVehicleAsReplacementVehicle(vehicleId: Id[Vehicle]): Unit = {
    setOfReplacementVehicles add vehicleId

    DebugLib.emptyFunctionForSettingBreakPoint()
  }

  def replacementVehicleReservationCompleted(vehicleId: Id[Vehicle]): Unit = {
    setOfReplacementVehicles.remove(vehicleId)
  }

  def isReplacementVehicle(vehicleId: Id[Vehicle]): Boolean = {
    setOfReplacementVehicles.contains(vehicleId)
  }

  def addTriggerMessages(messages: Vector[BeamAgentScheduler.ScheduleTrigger]): Unit = {
    nextBufferedTriggerMessages = nextBufferedTriggerMessages ++ messages
  }

  def isBufferedRideHailRequestProcessingOver: Boolean = {

    numberOfOverwriteRequestsOpen == 0 && setOfReplacementVehicles.size == 0
  }

  def tryClosingBufferedRideHailRequestWaive(): Unit = {

    if (isBufferedRideHailRequestProcessingOver) {
      closingBufferedRideHailRequestWaive()
    }

  }

  def closingBufferedRideHailRequestWaive(): Unit = {

    if (nextBufferedTriggerMessages.size > 1) {
      DebugLib.emptyFunctionForSettingBreakPoint()
    }

    scheduler ! CompletionNotice(
      triggerId,
      nextBufferedTriggerMessages
    )

    DebugLib.emptyFunctionForSettingBreakPoint()

    nextBufferedTriggerMessages = Vector()

  }

}