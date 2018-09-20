package beam.sim

import java.io.FileOutputStream
import java.nio.file.{Files, Paths}
import java.util.Properties

import beam.agentsim.agents.ridehail.RideHailSurgePricingManager
import beam.agentsim.events.handling.BeamEventsHandling
import beam.analysis.plots.{GraphSurgePricing, RideHailRevenueAnalysis}
import beam.replanning._
import beam.replanning.utilitybased.UtilityBasedModeChoice
import beam.router.r5.NetworkCoordinator
import beam.scoring.BeamScoringFunctionFactory
import beam.sim.config.{BeamConfig, ConfigModule, MatSimBeamConfigBuilder}
import beam.sim.metrics.Metrics._
import beam.sim.modules.{BeamAgentModule, UtilsModule}
import beam.sim.population.PopulationAdjustment
import beam.utils.reflection.ReflectionUtils
import beam.utils.{BeamConfigUtils, FileUtils, LoggingUtil}
import com.conveyal.r5.streets.StreetLayer
import com.conveyal.r5.transit.TransportNetwork
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import com.typesafe.scalalogging.LazyLogging
import kamon.Kamon
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.Config
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.controler._
import org.matsim.core.controler.corelisteners.{ControlerDefaultCoreListenersModule, EventsHandling}
import org.matsim.core.scenario.{MutableScenario, ScenarioByInstanceModule, ScenarioUtils}
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.households.Household
import org.matsim.utils.objectattributes.AttributeConverter
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.util.Try

trait BeamHelper extends LazyLogging {
  private val argsParser = new scopt.OptionParser[Arguments]("beam") {
    opt[String]("config")
      .action(
        (value, args) =>
          args.copy(
            config = Try(BeamConfigUtils.parseFileSubstitutingInputDirectory(value)).toOption,
            configLocation = Option(value)
        )
      )
      .validate(
        value =>
          if (value.trim.isEmpty) failure("config location cannot be empty")
          else success
      )
      .text("Location of the beam config file")
    opt[String]("cluster-type")
      .action(
        (value, args) =>
          args.copy(clusterType = value.trim.toLowerCase match {
            case "master" => Some(Master)
            case "worker" => Some(Worker)
            case _        => None
          })
      )
      .text("If running as a cluster, specify master or worker")
    opt[String]("node-host")
      .action((value, args) => args.copy(nodeHost = Option(value)))
      .validate(value => if (value.trim.isEmpty) failure("node-host cannot be empty") else success)
      .text("Host used to run the remote actor system")
    opt[String]("node-port")
      .action((value, args) => args.copy(nodePort = Option(value)))
      .validate(value => if (value.trim.isEmpty) failure("node-port cannot be empty") else success)
      .text("Port used to run the remote actor system")
    opt[String]("seed-address")
      .action((value, args) => args.copy(seedAddress = Option(value)))
      .validate(
        value =>
          if (value.trim.isEmpty) failure("seed-address cannot be empty")
          else success
      )
      .text(
        "Comma separated list of initial addresses used for the rest of the cluster to bootstrap"
      )
    opt[Boolean]("use-local-worker")
      .action((value, args) => args.copy(useLocalWorker = Some(value)))
      .text(
        "Boolean determining whether to use a local worker. " +
        "If cluster is NOT enabled this defaults to true and cannot be false. " +
        "If cluster is specified then this defaults to false and must be explicitly set to true. " +
        "NOTE: For cluster, this will ONLY be checked if cluster-type=master"
      )

    checkConfig(
      args =>
        if (args.useCluster && (args.nodeHost.isEmpty || args.nodePort.isEmpty || args.seedAddress.isEmpty))
          failure("If using the cluster then node-host, node-port, and seed-address are required")
        else if (args.useCluster && !args.useLocalWorker.getOrElse(true))
          failure("If using the cluster then use-local-worker MUST be true (or unprovided)")
        else success
    )
  }

  private def updateConfigForClusterUsing(
    parsedArgs: Arguments,
    config: TypesafeConfig
  ): TypesafeConfig = {
    (for {
      seedAddress <- parsedArgs.seedAddress
      nodeHost    <- parsedArgs.nodeHost
      nodePort    <- parsedArgs.nodePort
    } yield {
      config.withFallback(
        ConfigFactory.parseMap(
          Map(
            "seed.address" -> seedAddress,
            "node.host"    -> nodeHost,
            "node.port"    -> nodePort
          ).asJava
        )
      )
    }).getOrElse(config)
  }

  private def embedSelectArgumentsIntoConfig(
    parsedArgs: Arguments,
    config: TypesafeConfig
  ): TypesafeConfig = {
    config.withFallback(
      ConfigFactory.parseMap(
        (
          Map(
            "beam.cluster.enabled" -> parsedArgs.useCluster,
            "beam.useLocalWorker" -> parsedArgs.useLocalWorker.getOrElse(
              if (parsedArgs.useCluster) false else true
            )
          ) ++ {
            if (parsedArgs.useCluster)
              Map("beam.cluster.clusterType" -> parsedArgs.clusterType.get.toString)
            else Map.empty[String, Any]
          }
        ).asJava
      )
    )
  }

  def module(
    typesafeConfig: TypesafeConfig,
    scenario: Scenario,
    networkCoordinator: NetworkCoordinator
  ): com.google.inject.Module =
    AbstractModule.`override`(
      ListBuffer(new AbstractModule() {
        override def install(): Unit = {
          // MATSim defaults
          install(new NewControlerModule)
          install(new ScenarioByInstanceModule(scenario))
          install(new ControlerDefaultsModule)
          install(new ControlerDefaultCoreListenersModule)

          // Beam Inject below:
          install(new ConfigModule(typesafeConfig))
          install(new BeamAgentModule(BeamConfig(typesafeConfig)))
          install(new UtilsModule)
        }
      }).asJava,
      new AbstractModule() {
        private val mapper = new ObjectMapper()
        mapper.registerModule(DefaultScalaModule)

        override def install(): Unit = {
          val beamConfig = BeamConfig(typesafeConfig)

          bind(classOf[BeamConfig]).toInstance(beamConfig)
          bind(classOf[PrepareForSim]).to(classOf[BeamPrepareForSim])
          bind(classOf[RideHailSurgePricingManager]).asEagerSingleton()

          addControlerListenerBinding().to(classOf[BeamSim])

          addControlerListenerBinding().to(classOf[GraphSurgePricing])
          addControlerListenerBinding().to(classOf[RideHailRevenueAnalysis])

          bindMobsim().to(classOf[BeamMobsim])
          bind(classOf[EventsHandling]).to(classOf[BeamEventsHandling])
          bindScoringFunctionFactory().to(classOf[BeamScoringFunctionFactory])
          if (getConfig.strategy().getPlanSelectorForRemoval == "tryToKeepOneOfEachClass") {
            bindPlanSelectorForRemoval().to(classOf[TryToKeepOneOfEachClass])
          }
          addPlanStrategyBinding("GrabExperiencedPlan").to(classOf[GrabExperiencedPlan])
          addPlanStrategyBinding("SwitchModalityStyle").toProvider(classOf[SwitchModalityStyle])
          addPlanStrategyBinding("ClearRoutes").toProvider(classOf[ClearRoutes])
          addPlanStrategyBinding("ClearModes").toProvider(classOf[ClearRoutes])
          addPlanStrategyBinding(BeamReplanningStrategy.UtilityBasedModeChoice.toString)
            .toProvider(classOf[UtilityBasedModeChoice])
          addAttributeConverterBinding(classOf[MapStringDouble])
            .toInstance(new AttributeConverter[MapStringDouble] {
              override def convertToString(o: scala.Any): String =
                mapper.writeValueAsString(o.asInstanceOf[MapStringDouble].data)

              override def convert(value: String): MapStringDouble =
                MapStringDouble(mapper.readValue(value, classOf[Map[String, Double]]))
            })
          bind(classOf[TransportNetwork]).toInstance(networkCoordinator.transportNetwork)
          bind(classOf[TravelTimeCalculator]).toInstance(
            new FakeTravelTimeCalculator(
              networkCoordinator.network,
              new TravelTimeCalculatorConfigGroup()
            )
          )

          // Override EventsManager
          bind(classOf[EventsManager]).to(classOf[LoggingParallelEventsManager]).asEagerSingleton()
        }
      }
    )

  def runBeamUsing(args: Array[String], isConfigArgRequired: Boolean = true) = {
    val parsedArgs = argsParser.parse(args, init = Arguments()) match {
      case Some(parsedArgs) => parsedArgs
      case None =>
        throw new IllegalArgumentException(
          "Arguments provided were unable to be parsed. See above for reasoning."
        )
    }
    assert(
      !isConfigArgRequired || (isConfigArgRequired && parsedArgs.config.isDefined),
      "config is a required value, and must yield a valid config."
    )
    val configLocation = parsedArgs.configLocation.get

    val config = embedSelectArgumentsIntoConfig(parsedArgs, {
      if (parsedArgs.useCluster) updateConfigForClusterUsing(parsedArgs, parsedArgs.config.get)
      else parsedArgs.config.get
    }).resolve()

    parsedArgs.clusterType match {
      case Some(Worker) => runClusterWorkerUsing(config) //Only the worker requires a different path
      case _ => {
        val (_, outputDirectory) = runBeamWithConfig(config)
        val props = new Properties()
        props.setProperty("commitHash", LoggingUtil.getCommitHash)
        props.setProperty("configFile", configLocation)
        val out = new FileOutputStream(Paths.get(outputDirectory, "beam.properties").toFile)
        props.store(out, "Simulation out put props.")
        val beamConfig = BeamConfig(config)
        if (beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass
              .equalsIgnoreCase("ModeChoiceLCCM")) {
          Files.copy(
            Paths.get(beamConfig.beam.agentsim.agents.modalBehaviors.lccm.paramFile),
            Paths.get(
              outputDirectory,
              Paths
                .get(beamConfig.beam.agentsim.agents.modalBehaviors.lccm.paramFile)
                .getFileName
                .toString
            )
          )
        }
        Files.copy(Paths.get(configLocation), Paths.get(outputDirectory, "beam.conf"))
      }
    }
  }

  def runClusterWorkerUsing(config: TypesafeConfig) = {
    val clusterConfig = ConfigFactory
      .parseString(s"""
                      |akka.cluster.roles = [compute]
                      |akka.actor.deployment {
                      |      /statsService/singleton/workerRouter {
                      |        router = round-robin-pool
                      |        cluster {
                      |          enabled = on
                      |          max-nr-of-instances-per-node = 1
                      |          allow-local-routees = on
                      |          use-roles = ["compute"]
                      |        }
                      |      }
                      |    }
          """.stripMargin)
      .withFallback(config)

    if (isMetricsEnable) Kamon.start(clusterConfig.withFallback(ConfigFactory.defaultReference()))

    import akka.actor.{ActorSystem, DeadLetter, PoisonPill, Props}
    import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
    import beam.router.ClusterWorkerRouter
    import beam.sim.monitoring.DeadLetterReplayer

    val system = ActorSystem("ClusterSystem", clusterConfig)
    system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = Props(classOf[ClusterWorkerRouter], clusterConfig),
        terminationMessage = PoisonPill,
        settings = ClusterSingletonManagerSettings(system).withRole("compute")
      ),
      name = "statsService"
    )
    system.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = "/user/statsService",
        settings = ClusterSingletonProxySettings(system).withRole("compute")
      ),
      name = "statsServiceProxy"
    )
    val replayer = system.actorOf(DeadLetterReplayer.props())
    system.eventStream.subscribe(replayer, classOf[DeadLetter])

    import scala.concurrent.ExecutionContext.Implicits.global
    Await.ready(system.whenTerminated.map(_ => {
      if (isMetricsEnable) Kamon.shutdown()
      logger.info("Exiting BEAM")
    }), scala.concurrent.duration.Duration.Inf)
  }

  def runBeamWithConfig(config: TypesafeConfig): (Config, String) = {
    val beamConfig = BeamConfig(config)
    level = beamConfig.beam.metrics.level
    runName = beamConfig.beam.agentsim.simulationName
    if (isMetricsEnable) Kamon.start(config.withFallback(ConfigFactory.defaultReference()))

    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSamConf()
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)

    ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", 2000.0)

    val outputDirectory = FileUtils.getConfigOutputFile(
      beamConfig.beam.outputs.baseOutputDirectory,
      beamConfig.beam.agentsim.simulationName,
      beamConfig.beam.outputs.addTimestampToOutputDirectory
    )
    LoggingUtil.createFileLogger(outputDirectory)
    matsimConfig.controler.setOutputDirectory(outputDirectory)
    matsimConfig.controler().setWritePlansInterval(beamConfig.beam.outputs.writePlansInterval)

    val networkCoordinator = new NetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()

    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(networkCoordinator.network)

    samplePopulation(scenario, beamConfig, matsimConfig)

    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      module(config, scenario, networkCoordinator)
    )

    val beamServices: BeamServices = injector.getInstance(classOf[BeamServices])

    beamServices.controler.run()

    if (isMetricsEnable) Kamon.shutdown()

    (matsimConfig, outputDirectory)
  }

  // sample population (beamConfig.beam.agentsim.numAgents - round to nearest full household)
  def samplePopulation(
    scenario: MutableScenario,
    beamConfig: BeamConfig,
    matsimConfig: Config
  ): Unit = {
    if (scenario.getPopulation.getPersons.size() > beamConfig.beam.agentsim.numAgents) {
      var notSelectedHouseholdIds = mutable.Set[Id[Household]]()
      var notSelectedVehicleIds = mutable.Set[Id[Vehicle]]()
      var notSelectedPersonIds = mutable.Set[Id[Person]]()
      var numberOfAgents = 0

      scenario.getVehicles.getVehicles
        .keySet()
        .forEach(vehicleId => notSelectedVehicleIds.add(vehicleId))
      scenario.getHouseholds.getHouseholds
        .keySet()
        .forEach(householdId => notSelectedHouseholdIds.add(householdId))
      scenario.getPopulation.getPersons
        .keySet()
        .forEach(persondId => notSelectedPersonIds.add(persondId))

      val iterHouseholds = scenario.getHouseholds.getHouseholds.values().iterator()
      while (numberOfAgents < beamConfig.beam.agentsim.numAgents && iterHouseholds.hasNext) {
        val household = iterHouseholds.next()
        numberOfAgents += household.getMemberIds.size()
        household.getVehicleIds.forEach(vehicleId => notSelectedVehicleIds.remove(vehicleId))
        notSelectedHouseholdIds.remove(household.getId)
        household.getMemberIds.forEach(persondId => notSelectedPersonIds.remove(persondId))
      }

      notSelectedVehicleIds.foreach(vehicleId => scenario.getVehicles.removeVehicle(vehicleId))

      notSelectedHouseholdIds.foreach { housholdId =>
        scenario.getHouseholds.getHouseholds.remove(housholdId)
        scenario.getHouseholds.getHouseholdAttributes.removeAllAttributes(housholdId.toString)
      }

      notSelectedPersonIds.foreach { personId =>
        scenario.getPopulation.removePerson(personId)
      }
    }

    val populationAdjustment = PopulationAdjustment.getPopulationAdjustment(beamConfig.beam.agentsim.populationAdjustment, beamConfig)
    populationAdjustment.update(scenario)
  }
}

case class MapStringDouble(data: Map[String, Double])
case class Arguments(
  configLocation: Option[String] = None,
  config: Option[TypesafeConfig] = None,
  clusterType: Option[ClusterType] = None,
  nodeHost: Option[String] = None,
  nodePort: Option[String] = None,
  seedAddress: Option[String] = None,
  useLocalWorker: Option[Boolean] = None
) {
  val useCluster = clusterType.isDefined
}

sealed trait ClusterType
case object Master extends ClusterType {
  override def toString() = "master"
}
case object Worker extends ClusterType {
  override def toString() = "worker"
}
