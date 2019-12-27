package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import org.jgrapht.graph.DefaultEdge
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AsyncAlonsoMoraAlgForRideHail(
  spatialDemand: QuadTree[CustomerRequest],
  supply: List[VehicleAndSchedule],
  beamServices: BeamServices,
  skimmer: BeamSkimmer
) {

  //private val solutionSpaceSizePerVehicle = Integer.MAX_VALUE
  private val waitingTimeInSec =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.waitingTimeInSec

  private implicit val implicitServices = beamServices

  private def matchVehicleRequests(v: VehicleAndSchedule): (List[RTVGraphNode], List[(RTVGraphNode, RTVGraphNode)]) = {
    val vertices = ListBuffer.empty[RTVGraphNode]
    val edges = ListBuffer.empty[(RTVGraphNode, RTVGraphNode)]
    val finalRequestsList = ListBuffer.empty[RideHailTrip]
    val requestWithCurrentVehiclePosition = v.getRequestWithCurrentVehiclePosition
    val center = requestWithCurrentVehiclePosition.activity.getCoord
    val searchRadius = waitingTimeInSec * BeamSkimmer.speedMeterPerSec(BeamMode.CAV)

    // get all customer requests located at a proximity to the vehicle
    var customers = MatchmakingUtils.getRequestsWithinGeofence(
      v,
      spatialDemand.getDisk(center.getX, center.getY, searchRadius).asScala.toList
    )

    // heading same direction
    customers = MatchmakingUtils.getNearbyRequestsHeadingSameDirection(v, customers)

    customers.foreach(
      r =>
        MatchmakingUtils.getRidehailSchedule(
          v.schedule,
          List(r.pickup, r.dropoff),
          v.vehicleRemainingRangeInMeters.toInt,
          skimmer
        ) match {
          case Some(schedule) =>
            val t = RideHailTrip(List(r), schedule)
            finalRequestsList append t
            if (!vertices.contains(v)) vertices append v
            vertices append (r, t)
            edges append ((r, t), (t, v))
          case _ =>
      }
    )
    if (finalRequestsList.nonEmpty) {
      for (k <- 2 to v.getFreeSeats) {
        val kRequestsList = ListBuffer.empty[RideHailTrip]
        for (t1 <- finalRequestsList) {
          for (t2 <- finalRequestsList
                 .drop(finalRequestsList.indexOf(t1))
                 .filter(
                   x => !(x.requests exists (s => t1.requests contains s)) && (t1.requests.size + x.requests.size) == k
                 )) {
            MatchmakingUtils
              .getRidehailSchedule(
                v.schedule,
                (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff)),
                v.vehicleRemainingRangeInMeters.toInt,
                skimmer
              )
              .map { schedule =>
                val t = RideHailTrip(t1.requests ++ t2.requests, schedule)
                kRequestsList append t
                vertices append t
                t.requests.foreach(r => edges.append((r, t)))
                edges append ((t, v))
              }
          }
        }
        finalRequestsList.appendAll(kRequestsList)
      }
    }
    (vertices.toList, edges.toList)
  }

  private def asyncBuildOfRSVGraph(): Future[AlonsoMoraPoolingAlgForRideHail.RTVGraph] = {
    Future
      .sequence(supply.withFilter(_.getFreeSeats >= 1).map { v =>
        Future { matchVehicleRequests(v) }
      })
      .map { result =>
        val rTvG = AlonsoMoraPoolingAlgForRideHail.RTVGraph(classOf[DefaultEdge])
        result foreach {
          case (vertices, edges) =>
            vertices foreach (vertex => rTvG.addVertex(vertex))
            edges foreach { case (vertexSrc, vertexDst) => rTvG.addEdge(vertexSrc, vertexDst) }
        }
        rTvG
      }
      .recover {
        case e =>
          println(e.getMessage)
          AlonsoMoraPoolingAlgForRideHail.RTVGraph(classOf[DefaultEdge])
      }
  }

  def matchAndAssign(tick: Int): Future[List[(RideHailTrip, VehicleAndSchedule, Double)]] = {
    val V: Int = supply.foldLeft(0) { case (maxCapacity, v) => Math max (maxCapacity, v.getFreeSeats) }
    asyncBuildOfRSVGraph().map(greedyAssignment(_, V))
  }
}
