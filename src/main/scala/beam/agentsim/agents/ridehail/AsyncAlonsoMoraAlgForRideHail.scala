package beam.agentsim.agents.ridehail

import beam.agentsim.agents.{Dropoff, EnRoute, MobilityRequest, Pickup}
import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import org.jgrapht.graph.DefaultEdge
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.collection.mutable

class AsyncAlonsoMoraAlgForRideHail(
  spatialDemand: QuadTree[CustomerRequest],
  supply: List[VehicleAndSchedule],
  beamServices: BeamServices,
  skimmer: BeamSkimmer
) {

  private val solutionSpaceSizePerVehicle =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.solutionSpaceSizePerVehicle

  private val waitingTimeInSec =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.waitingTimeInSec

  private def matchVehicleRequests(v: VehicleAndSchedule): List[(RideHailTrip, VehicleAndSchedule, Double)] = {
    val requestWithCurrentVehiclePosition = v.getRequestWithCurrentVehiclePosition
    val center = requestWithCurrentVehiclePosition.activity.getCoord
    val searchRadius = waitingTimeInSec * BeamSkimmer.speedMeterPerSec(BeamMode.CAV)

    // get all customer requests located at a proximity to the vehicle
    var customers = v.geofence match {
      case Some(gf) =>
        val gfCenter = new Coord(gf.geofenceX, gf.geofenceY)
        spatialDemand
          .getDisk(center.getX, center.getY, searchRadius)
          .asScala
          .filter(
            r =>
              GeoUtils.distFormula(r.pickup.activity.getCoord, gfCenter) <= gf.geofenceRadius &&
              GeoUtils.distFormula(r.dropoff.activity.getCoord, gfCenter) <= gf.geofenceRadius
          )
          .toList
      case _ =>
        spatialDemand.getDisk(center.getX, center.getY, searchRadius).asScala.toList
    }

    // if no customer found, returns
    if (customers.isEmpty)
      return List.empty[(RideHailTrip, VehicleAndSchedule, Double)]

    if (requestWithCurrentVehiclePosition.tag == EnRoute) {
      // if vehicle is EnRoute, then filter list of customer based on the destination of the passengers
      val i = v.schedule.indexWhere(_.tag == EnRoute)
      val mainTasks = v.schedule.slice(0, i)
      customers = customers
        .filter(r => AlonsoMoraPoolingAlgForRideHail.checkDistance(r.dropoff, mainTasks, searchRadius))
        .sortBy(r => GeoUtils.minkowskiDistFormula(center, r.pickup.activity.getCoord))
    } else {
      // if vehicle is empty, prioritize the destination of the current closest customers
      customers = customers.sortBy(r => GeoUtils.minkowskiDistFormula(center, r.pickup.activity.getCoord))
      val mainRequests = customers.slice(0, Math.max(customers.size, 1))
      customers = mainRequests ::: customers
        .drop(mainRequests.size)
        .filter(
          r => AlonsoMoraPoolingAlgForRideHail.checkDistance(r.dropoff, mainRequests.map(_.dropoff), searchRadius)
        )
    }

    val potentialTrips = mutable.ListBuffer.empty[(RideHailTrip, VehicleAndSchedule, Double)]
    // consider solo rides as initial potential trips
    customers
      .take(solutionSpaceSizePerVehicle)
      .flatten(
        c =>
          getRidehailSchedule(v.schedule, List(c.pickup, c.dropoff), v.vehicleRemainingRangeInMeters.toInt, skimmer)
            .map(schedule => (c, schedule))
      )
      .foreach {
        case (c, schedule) =>
          val trip = RideHailTrip(List(c), schedule)
          potentialTrips.append((trip, v, computeCost(trip, v)))
      }

    // if no solo ride is possible, returns
    if (potentialTrips.isEmpty)
      return List.empty[(RideHailTrip, VehicleAndSchedule, Double)]

    // building pooled rides from bottom up
    val numPassengers = v.getFreeSeats
    for (k <- 2 to numPassengers) {
      val potentialTripsWithKPassengers = mutable.ListBuffer.empty[(RideHailTrip, VehicleAndSchedule, Double)]
      potentialTrips.zipWithIndex.foreach {
        case ((t1, _, _), pt1_index) =>
          potentialTrips
            .drop(pt1_index)
            .filter {
              case (t2, _, _) =>
                !(t2.requests exists (s => t1.requests contains s)) && (t1.requests.size + t2.requests.size) == k
            }
            .flatten {
              case (t2, _, _) =>
                getRidehailSchedule(
                  v.schedule,
                  (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff)),
                  v.vehicleRemainingRangeInMeters.toInt,
                  skimmer
                ).map(schedule => RideHailTrip(t1.requests ++ t2.requests, schedule))
            }
            .foreach { t =>
              val cost = computeCost(t, v)
              //potentialTripsWithKPassengers.append((t, v, cost))
              if (potentialTripsWithKPassengers.size == 1) {
                // then replace the trip with highest sum of delays
                val ((_, _, tripWithLargestDelayCost), index) =
                  potentialTripsWithKPassengers.filter(_._1.requests.size == k).zipWithIndex.maxBy(_._1._3)
                if (tripWithLargestDelayCost > cost) {
                  potentialTripsWithKPassengers.patch(index, Seq((t, v, cost)), 1)
                }
              } else {
                // then add the new trip
                potentialTripsWithKPassengers.append((t, v, cost))
              }
            }
      }
      potentialTrips.appendAll(potentialTripsWithKPassengers)
    }
    potentialTrips.toList
  }

  def matchAndAssign(tick: Int): Future[List[(RideHailTrip, VehicleAndSchedule, Double)]] = {
    /*asyncBuildOfRSVGraph().map(
      AlonsoMoraPoolingAlgForRideHail.greedyAssignment(_, supply.map(_.getFreeSeats).max)
    )*/
    Future
      .sequence(supply.withFilter(_.getFreeSeats >= 1).map { v =>
        Future { matchVehicleRequests(v) }
      })
      .map(result => greedyAssignmentBis(result.flatten))
      .recover {
        case e =>
          println(e.getMessage)
          List.empty[(RideHailTrip, VehicleAndSchedule, Double)]
      }

  }
  /*private def asyncBuildOfRSVGraph(): Future[AlonsoMoraPoolingAlgForRideHail.RTVGraph] = {
    Future
      .sequence(supply.withFilter(_.getFreeSeats >= 1).map { v =>
        Future { matchVehicleRequests(v) }
      })
      .map { result =>
        val rTvG = AlonsoMoraPoolingAlgForRideHail.RTVGraph(classOf[DefaultEdge])
        result.foreach {
          case (rideHailTrips, vehicle) =>
            rTvG.addVertex(vehicle)
            rideHailTrips.foreach { trip =>
              rTvG.addVertex(trip)
              rTvG.addEdge(trip, vehicle)
              trip.requests.foreach { customer =>
                if (!rTvG.containsVertex(customer)) rTvG.addVertex(customer)
                rTvG.addEdge(customer, trip)
              }
            }
        }
        rTvG
      }
      .recover {
        case e =>
          println(e.getMessage)
          AlonsoMoraPoolingAlgForRideHail.RTVGraph(classOf[DefaultEdge])
      }
  }*/
}
