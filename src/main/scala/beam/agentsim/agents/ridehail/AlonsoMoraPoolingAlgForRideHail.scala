package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.agentsim.agents.vehicles.{BeamVehicle, PersonIdWithActorRef}
import beam.agentsim.agents.{MobilityRequest, _}
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.sim.common.GeoUtils
import beam.sim.{BeamServices, Geofence}
import optimus.algebra.Expression
import optimus.optimization.MPModel
import optimus.optimization.enums.SolverLib
import optimus.optimization.model.MPBinaryVar
import optimus.algebra.AlgebraOps._
import optimus.optimization.enums.{SolutionStatus, SolverLib}
import optimus.optimization.model.MPFloatVar
import org.jgrapht.graph.{DefaultEdge, DefaultUndirectedWeightedGraph}
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.collection.mutable.ListBuffer
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager
import optimus.optimization.MPModel
import optimus.optimization.enums.SolverLib
import optimus.optimization.model.{INFINITE, MPBinaryVar, MPFloatVar}
import optimus.algebra._
import optimus.optimization._

import scala.collection.mutable
import scala.concurrent.Future

class AlonsoMoraPoolingAlgForRideHail(
  spatialDemand: QuadTree[CustomerRequest],
  supply: List[VehicleAndSchedule],
  beamServices: BeamServices,
  skimmer: BeamSkimmer
) {

  // Methods below should be kept as def (instead of val) to allow automatic value updating
  //private def solutionSpaceSizePerVehicle: Int = Integer.MAX_VALUE
  private def waitingTimeInSec: Int =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.waitingTimeInSec
  private implicit val implicitServices = beamServices

  // Request Vehicle Graph
  def pairwiseRVGraph: RVGraph = {
    val rvG = RVGraph(classOf[RideHailTrip])
    val searchRadius = waitingTimeInSec * BeamSkimmer.speedMeterPerSec(BeamMode.CAV)

    for (r1: CustomerRequest <- spatialDemand.values().asScala) {
      val center = r1.pickup.activity.getCoord
      spatialDemand.getDisk(center.getX, center.getY, searchRadius).asScala.foreach {
        case r2 if r1 != r2 && !rvG.containsEdge(r1, r2) =>
          MatchmakingUtils
            .getRidehailSchedule(
              List.empty[MobilityRequest],
              List(r1.pickup, r1.dropoff, r2.pickup, r2.dropoff),
              Integer.MAX_VALUE,
              skimmer
            )
            .foreach { schedule =>
              rvG.addVertex(r2)
              rvG.addVertex(r1)
              rvG.addEdge(r1, r2, RideHailTrip(List(r1, r2), schedule))
            }
        case _ => // nothing
      }
    }

    for (v: VehicleAndSchedule <- supply.withFilter(_.getFreeSeats >= 1)) {
      val requestWithCurrentVehiclePosition = v.getRequestWithCurrentVehiclePosition
      val center = requestWithCurrentVehiclePosition.activity.getCoord

      // get all customer requests located at a proximity to the vehicle
      var customers = MatchmakingUtils.getRequestsWithinGeofence(
        v,
        spatialDemand.getDisk(center.getX, center.getY, searchRadius).asScala.toList
      )
      // heading same direction
      customers = MatchmakingUtils.getNearbyRequestsHeadingSameDirection(v, customers)

      // solution size resizing
      customers = customers
        .sortBy(r => GeoUtils.minkowskiDistFormula(center, r.pickup.activity.getCoord))
        .take(
          beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.solutionSpaceSizePerVehicle
        )

      customers
        .foreach(
          r =>
            MatchmakingUtils
              .getRidehailSchedule(
                v.schedule,
                List(r.pickup, r.dropoff),
                v.vehicleRemainingRangeInMeters.toInt,
                skimmer
              )
              .foreach { schedule =>
                rvG.addVertex(v)
                rvG.addVertex(r)
                rvG.addEdge(v, r, RideHailTrip(List(r), schedule))
            }
        )
    }
    rvG
  }

  // Request Trip Vehicle Graph
  def rTVGraph(rvG: RVGraph): RTVGraph = {
    val rTvG = RTVGraph(classOf[DefaultEdge])

    for (v <- supply.filter(rvG.containsVertex)) {
      rTvG.addVertex(v)
      val finalRequestsList: ListBuffer[RideHailTrip] = ListBuffer.empty[RideHailTrip]
      val individualRequestsList = ListBuffer.empty[RideHailTrip]
      for (t <- rvG.outgoingEdgesOf(v).asScala) {
        individualRequestsList.append(t)
        rTvG.addVertex(t)
        rTvG.addVertex(t.requests.head)
        rTvG.addEdge(t.requests.head, t)
        rTvG.addEdge(t, v)
      }
      finalRequestsList.appendAll(individualRequestsList)

      if (v.getFreeSeats > 1) {
        val pairRequestsList = ListBuffer.empty[RideHailTrip]
        for (t1 <- individualRequestsList) {
          for (t2 <- individualRequestsList
                 .drop(individualRequestsList.indexOf(t1))
                 .filter(x => rvG.containsEdge(t1.requests.head, x.requests.head))) {
            MatchmakingUtils
              .getRidehailSchedule(
                v.schedule,
                (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff)),
                v.vehicleRemainingRangeInMeters.toInt,
                skimmer
              )
              .foreach { schedule =>
                val t = RideHailTrip(t1.requests ++ t2.requests, schedule)
                pairRequestsList append t
                rTvG.addVertex(t)
                rTvG.addEdge(t1.requests.head, t)
                rTvG.addEdge(t2.requests.head, t)
                rTvG.addEdge(t, v)
              }
          }
        }
        finalRequestsList.appendAll(pairRequestsList)

        for (k <- 3 to v.getFreeSeats) {
          val kRequestsList = ListBuffer.empty[RideHailTrip]
          for (t1 <- finalRequestsList) {
            for (t2 <- finalRequestsList
                   .drop(finalRequestsList.indexOf(t1))
                   .filter(
                     x =>
                       !(x.requests exists (s => t1.requests contains s)) && (t1.requests.size + x.requests.size) == k
                   )) {
              MatchmakingUtils
                .getRidehailSchedule(
                  v.schedule,
                  (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff)),
                  v.vehicleRemainingRangeInMeters.toInt,
                  skimmer
                )
                .foreach { schedule =>
                  val t = RideHailTrip(t1.requests ++ t2.requests, schedule)
                  kRequestsList.append(t)
                  rTvG.addVertex(t)
                  t.requests.foreach(rTvG.addEdge(_, t))
                  rTvG.addEdge(t, v)
                }
            }
          }
          finalRequestsList.appendAll(kRequestsList)
        }
      }
    }
    rTvG
  }

  // a greedy assignment using a cost function
  def matchAndAssign(tick: Int): Future[List[(RideHailTrip, VehicleAndSchedule, Double)]] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    Future {
      val rvG = pairwiseRVGraph
      val rTvG = rTVGraph(rvG)
      //val V: Int = supply.foldLeft(0) { case (maxCapacity, v) => Math max (maxCapacity, v.getFreeSeats) }
      val assignment = optimalAssignment(rTvG)
      assignment
    }
  }

  def optimalAssignment(rTvG: RTVGraph): List[(RideHailTrip, VehicleAndSchedule, Double)] = {
    val optimalAssignment = mutable.ListBuffer.empty[(RideHailTrip, VehicleAndSchedule, Double)]
    val combinations = rTvG
      .vertexSet()
      .asScala
      .filter(t => t.isInstanceOf[RideHailTrip])
      .map { t =>
        val trip = t.asInstanceOf[RideHailTrip]
        val vehicle = rTvG
          .getEdgeTarget(
            rTvG
              .outgoingEdgesOf(trip)
              .asScala
              .filter(e => rTvG.getEdgeTarget(e).isInstanceOf[VehicleAndSchedule])
              .head
          )
          .asInstanceOf[VehicleAndSchedule]
        (trip, vehicle, trip.requests.sortBy(_.getId).map(_.getId).mkString(","))
      }
      .toList

    if (combinations.nonEmpty) {
      val trips = combinations.map(_._3).distinct.toArray
      val requests = spatialDemand.values().asScala.toArray
      val vehicles = supply.toArray
      import scala.language.implicitConversions
      implicit val model = MPModel(SolverLib.oJSolver)

      val epsilonVars = mutable.Map.empty[Integer, mutable.Map[Integer, MPBinaryVar]]
      val chiVars = mutable.Map.empty[Integer, MPBinaryVar]
      val objFunction = ListBuffer.empty[Expression]
      val constraint1 = mutable.Map.empty[Integer, ListBuffer[Expression]]
      val constraint2 = mutable.Map.empty[Integer, ListBuffer[Expression]]
      combinations.foreach {
        case (trip, vehicle, _) =>
          val c_ij = trip.sumOfDelays
          val i = trips.indexOf(trip.requests.sortBy(_.getId).map(_.getId).mkString(","))
          val j = vehicles.indexOf(vehicle)
          val epsilonVar = MPBinaryVar(s"epsilon($i,$j)")
          epsilonVars.getOrElseUpdate(i, mutable.Map.empty[Integer, MPBinaryVar]).put(j, epsilonVar)
          objFunction.append(c_ij * epsilonVar)
          constraint1.getOrElseUpdate(j, ListBuffer.empty[Expression]).append(epsilonVar)
      }
      requests.zipWithIndex.foreach {
        case (r, k) =>
          combinations.filter(_._3.contains(r.getId)).foreach { t =>
            val i = trips.indexOf(t._1.requests.sortBy(_.getId).map(_.getId).mkString(","))
            val j = vehicles.indexOf(t._2)
            constraint2.getOrElseUpdate(k, ListBuffer.empty[Expression]).append(epsilonVars(i)(j))
          }
          //val c_k0 = r.dropoff.upperBoundTime - r.dropoff.baselineNonPooledTime
          val c_k0 = 24*3600
          val chiVar = MPBinaryVar(s"chi($k)")
          chiVars.put(k, chiVar)
          objFunction.append(c_k0 * chiVar)
          constraint2.getOrElseUpdate(k, ListBuffer.empty[Expression]).append(chiVar)
      }

      minimize(sum(objFunction))
      constraint1.values.foreach(cons1 => add(sum(cons1) <:= 1))
      constraint2.values.foreach(cons2 => add(sum(cons2) := 1))
      start()
      for (i <- epsilonVars.keys) {
        for (j <- epsilonVars(i).keys) {
          epsilonVars(i)(j).value match {
            case Some(epsilon) if epsilon == 1 =>
              val vehicle = vehicles(j)
              val trip = combinations.find(c => c._2 == vehicle && c._3 == trips(i)).get._1
              optimalAssignment.append((trip, vehicle, 0.0))
            case _ =>
          }
          //println(s"$i - $j => " + epsilonVars(i)(j).value)
        }
      }
//      println("RESULT printing")
//      println("status " + status)
//      println("objectiveValue " + objectiveValue)
//      println("checkConstraints " + checkConstraints())
//      for (k <- chiVars.keys) {
//        println(s"$k => " + chiVars(k).value)
//      }
    }
    optimalAssignment.toList
  }
}

object AlonsoMoraPoolingAlgForRideHail {

  // ************ Helper functions ************
  def greedyAssignment(
    rTvG: RTVGraph,
    maximumVehCapacity: Int
  ): List[(RideHailTrip, VehicleAndSchedule, Double)] = {
    val Rok = collection.mutable.HashSet.empty[CustomerRequest]
    val Vok = collection.mutable.HashSet.empty[VehicleAndSchedule]
    val greedyAssignmentList = ListBuffer.empty[(RideHailTrip, VehicleAndSchedule, Double)]
    for (k <- maximumVehCapacity to 1 by -1) {
      val sortedList = rTvG
        .vertexSet()
        .asScala
        .filter(t => t.isInstanceOf[RideHailTrip] && t.asInstanceOf[RideHailTrip].requests.size == k)
        .map { t =>
          val trip = t.asInstanceOf[RideHailTrip]
          val vehicle = rTvG
            .getEdgeTarget(
              rTvG
                .outgoingEdgesOf(trip)
                .asScala
                .filter(e => rTvG.getEdgeTarget(e).isInstanceOf[VehicleAndSchedule])
                .head
            )
            .asInstanceOf[VehicleAndSchedule]
          val cost = MatchmakingUtils.computeAlonsoMoraCost(trip, vehicle)
          (trip, vehicle, cost)
        }
        .toList
        .sortBy(_._3)

      sortedList.foreach {
        case (trip, vehicle, cost) if !(Vok contains vehicle) && !(trip.requests exists (r => Rok contains r)) =>
          trip.requests.foreach(Rok.add)
          Vok.add(vehicle)
          greedyAssignmentList.append((trip, vehicle, cost))
        case _ =>
      }
    }

    greedyAssignmentList.toList
  }

  // ***** Graph Structure *****
  sealed trait RTVGraphNode {
    def getId: String
    override def toString: String = s"[$getId]"
  }
  sealed trait RVGraphNode extends RTVGraphNode
  // customer requests
  case class CustomerRequest(person: PersonIdWithActorRef, pickup: MobilityRequest, dropoff: MobilityRequest)
      extends RVGraphNode {
    override def getId: String = person.personId.toString
    override def toString: String = s"Person:${person.personId}|Pickup:$pickup|Dropoff:$dropoff"
  }
  // Ride Hail vehicles, capacity and their predefined schedule
  case class VehicleAndSchedule(
    vehicle: BeamVehicle,
    schedule: List[MobilityRequest],
    geofence: Option[Geofence],
    vehicleRemainingRangeInMeters: Double = Double.MaxValue
  ) extends RVGraphNode {
    private val numberOfPassengers: Int =
      schedule.takeWhile(_.tag != EnRoute).count(req => req.person.isDefined && req.tag == Dropoff)
    private val seatingCapacity: Int = vehicle.beamVehicleType.seatingCapacity
    override def getId: String = vehicle.id.toString
    def getNoPassengers: Int = numberOfPassengers
    def getSeatingCapacity: Int = seatingCapacity
    def getFreeSeats: Int = seatingCapacity - numberOfPassengers
    def getRequestWithCurrentVehiclePosition: MobilityRequest = schedule.find(_.tag == EnRoute).getOrElse(schedule.head)
  }
  // Trip that can be satisfied by one or more ride hail vehicle
  case class RideHailTrip(requests: List[CustomerRequest], schedule: List[MobilityRequest])
      extends DefaultEdge
      with RTVGraphNode {
    var sumOfDelays: Int = 0
    var upperBoundDelays: Int = 0
    schedule.filter(_.tag == Dropoff).foreach { s =>
      sumOfDelays += (s.serviceTime - s.baselineNonPooledTime)
      upperBoundDelays += (s.upperBoundTime - s.baselineNonPooledTime)
    }
    //val sumOfDelaysAsFraction: Double = sumOfDelays / upperBoundDelays.toDouble

    override def getId: String = requests.foldLeft(s"trip:") { case (c, x) => c + s"$x -> " }
    override def toString: String =
      s"${requests.size} requests and this schedule: ${schedule.map(_.toString).mkString("\n")}"
  }
  case class RVGraph(clazz: Class[RideHailTrip])
      extends DefaultUndirectedWeightedGraph[RVGraphNode, RideHailTrip](clazz)
  case class RTVGraph(clazz: Class[DefaultEdge])
      extends DefaultUndirectedWeightedGraph[RTVGraphNode, DefaultEdge](clazz)
  // ***************************

}
