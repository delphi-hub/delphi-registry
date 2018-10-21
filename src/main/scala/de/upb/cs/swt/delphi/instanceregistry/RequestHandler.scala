package de.upb.cs.swt.delphi.instanceregistry

import akka.actor._
import akka.http.scaladsl.model.StatusCodes
import akka.pattern.ask
import akka.util.Timeout
import de.upb.cs.swt.delphi.instanceregistry.Docker.DockerActor._
import de.upb.cs.swt.delphi.instanceregistry.Docker.{ContainerConfig, DockerActor, DockerConnection, DockerImage}
import de.upb.cs.swt.delphi.instanceregistry.connection.RestClient
import de.upb.cs.swt.delphi.instanceregistry.daos.{DynamicInstanceDAO, InstanceDAO}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.{Instance, InstanceEnums}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class RequestHandler(configuration: Configuration, connection: DockerConnection) extends AppLogging {


  implicit val system: ActorSystem = Registry.system
  implicit val ec: ExecutionContext = system.dispatcher

  val dockerActor = system.actorOf(DockerActor.props(connection))

  private[instanceregistry] val instanceDao: InstanceDAO = new DynamicInstanceDAO(configuration)


  def initialize(): Unit = {
    log.info("Initializing request handler...")
    instanceDao.initialize()
    if (!instanceDao.allInstances().exists(instance => instance.name.equals("Default ElasticSearch Instance"))) {
      //Add default ES instance
      handleRegister(Instance(None, "elasticsearch://localhost", 9200, "Default ElasticSearch Instance", ComponentType.ElasticSearch, None, InstanceState.Running))
    }
    log.info("Done initializing request handler.")
  }

  def shutdown(): Unit = {
    instanceDao.shutdown()
  }
//TODO: Add shutdown hook for docker actor
/*  def dockerShutdown(): Unit = {
    log.warning("Received shutdown signal for docker.")
    implicit val timeout = Timeout(100 seconds)
    val future: Future[Any] = dockerActor ? terminate

    Await.result(future, timeout.duration)
  }*/

  /**
    * Called when a new instance registers itself, meaning it is not running in a docker container. Will ignore the
    * parameter instances' id, dockerId and state
    *
    * @param instance Instance that is registering
    * @return Newly assigned ID if successful
    */
  def handleRegister(instance: Instance): Try[Long] = {
    val newID = generateNextId()

    log.info(s"Assigned new id $newID to registering instance with name ${instance.name}.")

    val newInstance = Instance(id = Some(newID), name = instance.name, host = instance.host,
      portNumber = instance.portNumber, componentType = instance.componentType,
      dockerId = None, instanceState = InstanceState.Running)

    instanceDao.addInstance(newInstance) match {
      case Success(_) => Success(newID)
      case Failure(x) => Failure(x)
    }
  }

  /** *
    * Called when an instance is shut down that is not running inside of a docker container. Will remove the instance
    * from the registry.
    *
    * @param instanceId ID of the instance that was shut down
    * @return OperationResult indicating either success or the reason for failure (which precondition was not met)
    */
  def handleDeregister(instanceId: Long): OperationResult.Value = {
    if (!instanceDao.hasInstance(instanceId)) {
      OperationResult.IdUnknown
    } else if (isInstanceDockerContainer(instanceId)) {
      OperationResult.IsDockerContainer
    } else {
      instanceDao.removeInstance(instanceId)
      OperationResult.Ok
    }
  }

  def getAllInstancesOfType(compType: ComponentType): List[Instance] = {
    instanceDao.getInstancesOfType(compType)
  }

  def getNumberOfInstances(compType: ComponentType): Int = {
    instanceDao.allInstances().count(i => i.componentType == compType)
  }

  def getMatchingInstanceOfType(compType: ComponentType): Try[Instance] = {
    log.info(s"Trying to match to instance of type $compType ...")
    getNumberOfInstances(compType) match {
      case 0 =>
        log.error(s"Cannot match to any instance of type $compType, no such instance present.")
        Failure(new RuntimeException(s"Cannot match to any instance of type $compType, no instance present."))
      case 1 =>
        val instance: Instance = instanceDao.getInstancesOfType(compType).head
        log.info(s"Only one instance of that type present, matching to instance with id ${instance.id.get}.")
        Success(instance)
      case x =>
        log.info(s"Found $x instances of type $compType.")

        //First try: Match to instance with most consecutive positive matching results
        var maxConsecutivePositiveResults = 0
        var instanceToMatch: Instance = null

        for (instance <- instanceDao.getInstancesOfType(compType)) {
          if (countConsecutivePositiveMatchingResults(instance.id.get) > maxConsecutivePositiveResults) {
            maxConsecutivePositiveResults = countConsecutivePositiveMatchingResults(instance.id.get)
            instanceToMatch = instance
          }
        }

        if (instanceToMatch != null) {
          log.info(s"Matching to instance with id ${instanceToMatch.id}, as it has $maxConsecutivePositiveResults positive results in a row.")
          Success(instanceToMatch)
        } else {
          //Second try: Match to instance with most positive matching results
          var maxPositiveResults = 0

          for (instance <- instanceDao.getInstancesOfType(compType)) {
            val noOfPositiveResults: Int = instanceDao.getMatchingResultsFor(instance.id.get).get.count(i => i)
            if (noOfPositiveResults > maxPositiveResults) {
              maxPositiveResults = noOfPositiveResults
              instanceToMatch = instance
            }
          }

          if (instanceToMatch != null) {
            log.info(s"Matching to instance with id ${instanceToMatch.id}, as it has $maxPositiveResults positive results.")
            Success(instanceToMatch)
          } else {
            //All instances are equally good (or bad), match to any of them
            instanceToMatch = instanceDao.getInstancesOfType(compType).head
            log.info(s"Matching to instance with id ${instanceToMatch.id}, no differences between instances have been found.")
            Success(instanceToMatch)
          }
        }
    }

  }

  def handleMatchingResult(id: Long, result: Boolean): OperationResult.Value = {
    if (!instanceDao.hasInstance(id)) {
      OperationResult.IdUnknown
    } else {
      val instance = instanceDao.getInstance(id).get
      instanceDao.addMatchingResult(id, result)
      if (result && instance.instanceState == InstanceState.NotReachable) {
        instanceDao.setStateFor(instance.id.get, InstanceState.Running)
      } else if (!result && instance.instanceState == InstanceState.Running) {
        instanceDao.setStateFor(instance.id.get, InstanceState.NotReachable)
      }
      log.info(s"Applied matching result $result to instance with id $id.")
      OperationResult.Ok
    }
  }

  def handleDeploy(componentType: ComponentType, name: Option[String]): Try[Long] = {
    val newId = generateNextId()
    //TODO: Deploy container (async?), set env var 'INSTANCE_ID' to newId
    //TODO: Get below values for container!
    val host: String = ???
    val port: Int = ???

    log.info(s"Initializing Docker")

    implicit val timeout = Timeout(10 seconds)

    val dockerImage = new DockerImage()
    val future: Future[Any] = dockerActor ? create(ContainerConfig(dockerImage.getImageName(componentType)))
    val dockerId = Await.result(future, timeout.duration).asInstanceOf[String]

    log.info(s"Deployed new container with id $dockerId.")

    val newInstance = Instance(Some(newId), host, port, name.getOrElse(s"Generic $componentType"), componentType, Some(dockerId), InstanceState.Stopped)
    log.info(s"Registering instance $newInstance....")

    instanceDao.addInstance(newInstance) match {
      case Success(_) =>
        log.info("Successfully registered.")
        Success(newId)
      case Failure(x) =>
        log.info(s"Failed to register. Exception: $x")
        Failure(x)
    }
  }

  /** *
    * Handles a call to /reportStart. Needs the instance with the specified id to be present and running inside a docker
    * container. Will update the state of the instance to 'Running'. Will print warnings if illegal state transitions
    * occur, but will update state anyway.
    *
    * @param id Id of the instance that reported start
    * @return OperationResult indicating either success or the reason for failure (which precondition was not met)
    */
  def handleReportStart(id: Long): OperationResult.Value = {
    if (!instanceDao.hasInstance(id)) {
      OperationResult.IdUnknown
    } else if (!isInstanceDockerContainer(id)) {
      OperationResult.NoDockerContainer
    } else {
      val instance = instanceDao.getInstance(id).get
      instance.instanceState match {
        case InstanceState.Running =>
          log.warning(s"Instance with id $id reported start but state already was 'Running'.")
        case InstanceState.Paused =>
          log.warning(s"Instance with id $id reported start but state is 'Paused'. Will set state to 'Running'.")
          instanceDao.setStateFor(instance.id.get, InstanceState.Running)
        case _ =>
          instanceDao.setStateFor(instance.id.get, InstanceState.Running)
      }
      log.info(s"Instance with id $id has reported start.")
      OperationResult.Ok
    }
  }

  /** *
    * Handles a call to /reportStop. Needs the instance with the specified id to be present and running inside a docker
    * container. Will update the state of the instance to 'NotReachable'. Will print warnings if illegal state transitions
    * occur, but will update state anyway.
    *
    * @param id Id of the instance that reported stop
    * @return OperationResult indicating either success or the reason for failure (which precondition was not met)
    */
  def handleReportStop(id: Long): OperationResult.Value = {
    if (!instanceDao.hasInstance(id)) {
      OperationResult.IdUnknown
    } else if (!isInstanceDockerContainer(id)) {
      OperationResult.NoDockerContainer
    } else {
      val instance = instanceDao.getInstance(id).get
      instance.instanceState match {
        case InstanceState.Stopped =>
          log.warning(s"Instance with id $id reported stop but state already was 'Stopped'.")
        case InstanceState.Failed =>
          log.warning(s"Instance with id $id reported stop but state already was 'Failed'.")
        case InstanceState.Paused =>
          log.warning(s"Instance with id $id reported stop but state already was 'Paused'.")
        case InstanceState.Running | InstanceState.NotReachable =>
          instanceDao.setStateFor(instance.id.get, InstanceState.NotReachable)
        case _ =>
          instanceDao.setStateFor(instance.id.get, InstanceState.NotReachable)
      }
      log.info(s"Instance with id $id has reported stop.")
      OperationResult.Ok
    }
  }

  /** *
    * Handles a call to /reportFailure. Needs the instance with the specified id to be present and running inside a docker
    * container. Will update the state of the instance to 'Failed'. Will print warnings if illegal state transitions
    * occur, but will update state anyway.
    *
    * @param id Id of the instance that reported failure
    * @return OperationResult indicating either success or the reason for failure (which precondition was not met)
    */
  def handleReportFailure(id: Long, errorLog: Option[String]): OperationResult.Value = {
    if (!instanceDao.hasInstance(id)) {
      OperationResult.IdUnknown
    } else if (!isInstanceDockerContainer(id)) {
      OperationResult.NoDockerContainer
    } else {
      val instance = instanceDao.getInstance(id).get //TODO:Handle errorLog
      instance.instanceState match {
        case InstanceState.Failed =>
          log.warning(s"Instance with id $id reported failure but state already was 'Failed'.")
        case InstanceState.Paused =>
          log.warning(s"Instance with id $id reported failure but state is 'Paused'. Will set state to 'Failed.")
          instanceDao.setStateFor(instance.id.get, InstanceState.Failed)
        case InstanceState.Stopped =>
          log.warning(s"Instance with id $id reported failure but state is 'Stopped'. Will set state to 'Failed'.")
          instanceDao.setStateFor(instance.id.get, InstanceState.Failed)
        case _ =>
          instanceDao.setStateFor(instance.id.get, InstanceState.Failed)
      }
      log.info(s"Instance with id $id has reported failure.")
      OperationResult.Ok
    }

  }

  /** *
    * Handles a call to /pause. Needs instance with the specified id to be present, deployed inside a docker container,
    * and running. Will pause the container and set state accordingly.
    *
    * @param id Id of the instance to pause
    * @return OperationRsult indicating either success or the reason for failure (which preconditions failed)
    */
  def handlePause(id: Long): OperationResult.Value = {
    if (!instanceDao.hasInstance(id)) {
      OperationResult.IdUnknown
    } else if (!isInstanceDockerContainer(id)) {
      OperationResult.NoDockerContainer
    } else {
      val instance = instanceDao.getInstance(id).get
      if (instance.instanceState == InstanceState.Running) {
        log.info(s"Handling /pause for instance with id $id...")
        //  dockerActor ! PauseMessage(instance.dockerId.get, instanceHasState())
        //TODO: execute pause command (async?)
        instanceDao.setStateFor(instance.id.get, InstanceState.Paused) //TODO: Move state update to async block?
        OperationResult.Ok
      } else {
        OperationResult.InvalidStateForOperation
      }
    }
  }

  /** *
    * Handles a call to /resume. Needs instance with the specified id to be present, deployed inside a docker container,
    * and paused. Will resume the container and set state accordingly.
    *
    * @param id Id of the instance to resume
    * @return OperationResult indicating either success or the reason for failure (which preconditions failed)
    */
  def handleResume(id: Long): OperationResult.Value = {
    if (!instanceDao.hasInstance(id)) {
      OperationResult.IdUnknown
    } else if (!isInstanceDockerContainer(id)) {
      OperationResult.NoDockerContainer
    } else {
      val instance = instanceDao.getInstance(id).get
      if (instance.instanceState == InstanceState.Paused) {
        log.info(s"Handling /resume for instance with id $id...")
        //TODO: Pause the container (async?)
        instanceDao.setStateFor(instance.id.get, InstanceState.Running) //TODO: Move state update to async block?
        OperationResult.Ok
      } else {
        OperationResult.InvalidStateForOperation
      }
    }
  }

  /** *
    * Handles a call to /stop. Needs the instance with the specified id to be present and deployed inside a
    * docker container. Will try to gracefully shutdown instance, stop the container and set state accordingly.
    *
    * @param id ID of the instance to stop
    * @return OperationResult indicating either success or the reason for failure (which preconditions failed)
    */
  def handleStop(id: Long): OperationResult.Value = {
    if (!instanceDao.hasInstance(id)) {
      OperationResult.IdUnknown
    } else if (!isInstanceDockerContainer(id)) {
      OperationResult.NoDockerContainer
    } else {
      log.info(s"Handling /stop for instance with id $id...")
      val instance = instanceDao.getInstance(id).get
      if (instance.instanceState == InstanceState.Running) {
        //Only call /stop when instance is crawler, webapi or webapp
        if (instance.componentType == ComponentType.Crawler ||
          instance.componentType == ComponentType.WebApi ||
          instance.componentType == ComponentType.WebApp) {
          log.info(s"Shutting instance down gracefully...")

          val shutdownFuture = RestClient.executePost(RestClient.getUri(instance) + "/stop")
          shutdownFuture.onComplete {
            case Success(response) =>
              if (response.status == StatusCodes.OK) {
                log.info(s"Successfully shut down instance with id $id.")
              } else {
                log.warning(s"Failed to shut down instance with id $id, server returned ${response.status}.")
              }
            case Failure(x) =>
              log.warning(s"Failed to shut down instance with id $id, exception occurred: $x")
          }
          Await.ready(shutdownFuture, Duration.Inf)
        }
      } else {
        log.warning(s"Instance with id $id is in state ${instance.instanceState}, so it will not be gracefully shut down.")
      }
      log.info("Stopping container...")

      implicit val timeout = Timeout(10 seconds)
      val future: Future[Any] = dockerActor ? stop(instance.dockerId.get)
      instanceDao.setStateFor(instance.id.get, InstanceState.Stopped) //TODO: Move state update to async block?

      OperationResult.Ok
    }
  }

  /** *
    * Handles a call to /start. Needs the instance with the specified id to be present, deployed inside a docker container,
    * and stopped. Will start the docker container. State will be updated by the corresponding instance by calling
    * /reportStart.
    *
    * @param id Id of the instance to start
    * @return OperationResult indicating either success or the reasons for failure (which preconditions failed)
    */
  def handleStart(id: Long): OperationResult.Value = {
    if (!instanceDao.hasInstance(id)) {
      OperationResult.IdUnknown
    } else if (!isInstanceDockerContainer(id)) {
      OperationResult.NoDockerContainer
    } else {
      log.info(s"Handling /start for instance with id $id...")
      val instance = instanceDao.getInstance(id).get
      if (instance.instanceState == InstanceState.Stopped) {
        log.info("Starting container...")
        implicit val timeout = Timeout(10 seconds)
        val future: Future[Any] = dockerActor ? start(instance.dockerId.get)
        OperationResult.Ok
      } else {
        OperationResult.InvalidStateForOperation
      }
    }
  }

  /** *
    * Handles a call to /delete. Needs the instance with the specified id to be present, deployed inside a docker container,
    * and stopped. Will delete the docker container and remove any data from the IR.
    *
    * @param id Id of the instance to delete
    * @return OperationResult indicating either success or the reason for failure (which preconditions failed)
    */
  def handleDeleteContainer(id: Long): OperationResult.Value = {
    if (!instanceDao.hasInstance(id)) {
      OperationResult.IdUnknown
    } else if (!isInstanceDockerContainer(id)) {
      OperationResult.NoDockerContainer
    } else {
      log.info(s"Handling /delete for instance with id $id...")
      val instance = instanceDao.getInstance(id).get
      if (instance.instanceState == InstanceState.Stopped) {
        log.info("Deleting container...")
        dockerActor ! delete(instance.dockerId.get)
        instanceDao.removeInstance(id) match {
          case Success(_) => OperationResult.Ok
          case Failure(_) => OperationResult.InternalError
        }
      } else {
        OperationResult.InvalidStateForOperation
      }
    }
  }


  def isInstanceIdPresent(id: Long): Boolean = {
    instanceDao.hasInstance(id)
  }

  def getInstance(id: Long): Option[Instance] = {
    instanceDao.getInstance(id)
  }

  def instanceHasState(id: Long, state: InstanceEnums.State): Boolean = {
    instanceDao.getInstance(id) match {
      case Some(instance) => instance.instanceState == state
      case None => false
    }
  }

  def isInstanceDockerContainer(id: Long): Boolean = {
    instanceDao.getDockerIdFor(id).isSuccess
  }

  private def countConsecutivePositiveMatchingResults(id: Long): Int = {
    if (!instanceDao.hasInstance(id) || instanceDao.getMatchingResultsFor(id).get.isEmpty) {
      0
    } else {
      val matchingResults = instanceDao.getMatchingResultsFor(id).get
      var count = 0

      for (index <- matchingResults.size to 1) {
        if (matchingResults(index - 1)) {
          count += 1
        } else {
          return count
        }
      }
      count
    }

  }

  private def generateNextId(): Long = {
    if (instanceDao.allInstances().isEmpty) {
      0L
    } else {
      (instanceDao.allInstances().map(i => i.id.getOrElse(0L)) max) + 1L
    }
  }

  object OperationResult extends Enumeration {
    val IdUnknown: Value = Value("IdUnknown")
    val NoDockerContainer: Value = Value("NoDockerContainer")
    val IsDockerContainer: Value = Value("IsDockerContainer")
    val InvalidStateForOperation: Value = Value("InvalidState")
    val Ok: Value = Value("Ok")
    val InternalError: Value = Value("InternalError")
  }

}

