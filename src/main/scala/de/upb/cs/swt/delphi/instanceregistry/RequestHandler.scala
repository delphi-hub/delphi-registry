package de.upb.cs.swt.delphi.instanceregistry

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import de.upb.cs.swt.delphi.instanceregistry.Docker.DockerActor._
import de.upb.cs.swt.delphi.instanceregistry.Docker.{DockerActor, DockerConnection}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import de.upb.cs.swt.delphi.instanceregistry.daos.{DynamicInstanceDAO, InstanceDAO}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class RequestHandler(configuration: Configuration, connection: DockerConnection) extends AppLogging {



  implicit val system: ActorSystem = Registry.system
  implicit val materializer : Materializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  private[instanceregistry] val instanceDao: InstanceDAO = new DynamicInstanceDAO(configuration)

  val (eventActor, eventPublisher) = Source.actorRef[RegistryEvent](0, OverflowStrategy.dropNew)
    .toMat(Sink.asPublisher(fanout = true))(Keep.both)
    .run()
  val dockerActor: ActorRef = system.actorOf(DockerActor.props(connection))

  def initialize(): Unit = {
    log.info("Initializing request handler...")
    instanceDao.initialize()
    if (!instanceDao.allInstances().exists(instance => instance.name.equals("Default ElasticSearch Instance"))) {
      //Add default ES instance
      handleRegister(Instance(None, "elasticsearch://172.17.0.1", 9200, "Default ElasticSearch Instance", ComponentType.ElasticSearch, None, InstanceState.Running))
    }
    log.info("Done initializing request handler.")
  }

  def shutdown() : Unit = {
    eventActor ! PoisonPill
    instanceDao.shutdown()
  }

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
      case Success(_) =>
        fireNumbersChangedEvent(newInstance.componentType)
        fireInstanceAddedEvent(newInstance)
        Success(newID)
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
      val instanceToRemove = instanceDao.getInstance(instanceId).get
      fireInstanceRemovedEvent(instanceToRemove)
      instanceDao.removeInstance(instanceId)
      fireNumbersChangedEvent(instanceToRemove.componentType)
      OperationResult.Ok
    }
  }

  def getAllInstancesOfType(compType: ComponentType): List[Instance] = {
    instanceDao.getInstancesOfType(compType)
  }

  def getNumberOfInstances(compType: ComponentType): Int = {
    instanceDao.allInstances().count(i => i.componentType == compType)
  }

  def getEventList(id: Long) : Try[List[RegistryEvent]] = {
    instanceDao.getEventsFor(id)
  }

  def getMatchingInstanceOfType(compType: ComponentType): Try[Instance] = {
    //TODO: Check for links in state 'Assigned'
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

  def handleInstanceLinkCreated(instanceIdFrom: Long, instanceIdTo: Long): OperationResult.Value = {
    if(!instanceDao.hasInstance(instanceIdFrom) || !instanceDao.hasInstance(instanceIdTo)){
      OperationResult.IdUnknown
    } else {
      //TODO: Verify there is no link present that is not failed!
      //TODO: Insert Link into database, state should be 'Assigned'
      OperationResult.Ok
    }
  }

  def handleInstanceAssignment(instanceId: Long, newDependencyId: Long): OperationResult.Value = {
    if(!instanceDao.hasInstance(instanceId) || !instanceDao.hasInstance(newDependencyId)){
      OperationResult.IdUnknown
    } else if(!isInstanceDockerContainer(instanceId)){
      OperationResult.NoDockerContainer
    } else {
      val instance = instanceDao.getInstance(instanceId).get
      val dependency = instanceDao.getInstance(instanceId).get

      if(assignmentAllowed(instance.componentType) && compatibleTypes(instance.componentType, dependency.componentType)){
        //TODO: Update database with assignment. Verify there is only one link present, remove it and replace it with new one
        implicit val timeout : Timeout = Timeout(10 seconds)

        (dockerActor ? restart(instance.dockerId.get)).map{
          _ => log.info(s"Instance $instanceId restarted.")
            instanceDao.setStateFor(instance.id.get, InstanceState.Stopped) //Set to stopped, will report start automatically
            fireStateChangedEvent(instanceDao.getInstance(instanceId).get)
        }.recover {
          case ex: Exception =>
            log.warning(s"Failed to restart container with id $instanceId. Message is: ${ex.getMessage}")
            fireDockerOperationErrorEvent(Some(instance), s"Pause failed with message: ${ex.getMessage}")
        }

        OperationResult.Ok
      } else {
        OperationResult.InvalidTypeForOperation
      }


    }
  }

  def handleMatchingResult(callerId: Long, matchedInstanceId: Long, result: Boolean): OperationResult.Value = {
    if (!instanceDao.hasInstance(callerId) || !instanceDao.hasInstance(matchedInstanceId)) {
      OperationResult.IdUnknown
    } else {
      val callingInstance = instanceDao.getInstance(callerId).get
      val matchedInstance = instanceDao.getInstance(matchedInstanceId).get

      //Update list of matching results
      instanceDao.addMatchingResult(matchedInstanceId, result)
      //Update state of matchedInstance accordingly
      if (result && matchedInstance.instanceState == InstanceState.NotReachable) {
        instanceDao.setStateFor(matchedInstanceId, InstanceState.Running)
        fireStateChangedEvent(instanceDao.getInstance(matchedInstanceId).get) //Re-retrieve instance bc reference was invalidated by 'setStateFor'
      } else if (!result && matchedInstance.instanceState == InstanceState.Running) {
        instanceDao.setStateFor(matchedInstanceId, InstanceState.NotReachable)
        fireStateChangedEvent(instanceDao.getInstance(matchedInstanceId).get)//Re-retrieve instance bc reference was invalidated by 'setStateFor'
      }
      log.info(s"Applied matching result $result to instance with id $matchedInstanceId.")

      //TODO: Handle Link state, set it to failed if needed.
      OperationResult.Ok
    }
  }

  def handleDeploy(componentType: ComponentType, name: Option[String]): Try[Long] = {
    val newId = generateNextId()

    log.info(s"Deploying container of type $componentType")

    implicit val timeout: Timeout = Timeout(10 seconds)

    val future: Future[Any] = dockerActor ? create(componentType, newId)
    val deployResult = Await.result(future, timeout.duration).asInstanceOf[Try[(String, String, Int)]]

    deployResult match {
      case Failure(ex) =>
        log.error(s"Failed to deploy container, docker host not reachable.")
        fireDockerOperationErrorEvent(None, s"Deploy failed with message: ${ex.getMessage}")
        Failure(new RuntimeException(s"Failed to deploy container, docker host not reachable (${ex.getMessage})."))
      case Success((dockerId, host, port)) =>
        val normalizedHost = host.substring(1,host.length - 1)
        log.info(s"Deployed new container with id $dockerId, host $normalizedHost and port $port.")

        val newInstance = Instance(Some(newId), normalizedHost, port, name.getOrElse(s"Generic $componentType"), componentType, Some(dockerId), InstanceState.Deploying)
        log.info(s"Registering instance $newInstance....")

        instanceDao.addInstance(newInstance) match {
          case Success(_) =>
            log.info("Successfully registered.")
            fireInstanceAddedEvent(newInstance)
            fireNumbersChangedEvent(newInstance.componentType)
            Success(newId)
          case Failure(x) =>
            log.info(s"Failed to register. Exception: $x")
            Failure(x)
        }
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
      fireStateChangedEvent(instanceDao.getInstance(id).get)
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
          instanceDao.setStateFor(instance.id.get, InstanceState.Stopped)
        case _ =>
          instanceDao.setStateFor(instance.id.get, InstanceState.NotReachable)
      }
      log.info(s"Instance with id $id has reported stop.")
      fireStateChangedEvent(instanceDao.getInstance(id).get)
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
      fireStateChangedEvent(instanceDao.getInstance(id).get)
      OperationResult.Ok
    }

  }

  /** *
    * Handles a call to /pause. Needs instance with the specified id to be present, deployed inside a docker container,
    * and running. Will pause the container and set state accordingly.
    *
    * @param id Id of the instance to pause
    * @return OperationResult indicating either success or the reason for failure (which preconditions failed)
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
        implicit val timeout : Timeout = Timeout(10 seconds)

        (dockerActor ? pause(instance.dockerId.get)).map{
          _ => log.info(s"Instance $id paused.")
            instanceDao.setStateFor(instance.id.get, InstanceState.Paused)
            fireStateChangedEvent(instanceDao.getInstance(id).get)
        }.recover {
          case ex: Exception =>
            log.warning(s"Failed to pause container with id $id. Message is: ${ex.getMessage}")
            fireDockerOperationErrorEvent(Some(instance), s"Pause failed with message: ${ex.getMessage}")
        }

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
        implicit val timeout : Timeout = Timeout(10 seconds)

        (dockerActor ? unpause(instance.dockerId.get)).map{
          _ => log.info(s"Instance $id resumed.")
            instanceDao.setStateFor(instance.id.get, InstanceState.Running)
            fireStateChangedEvent(instanceDao.getInstance(id).get)
        }.recover {
          case ex: Exception =>
            log.warning(s"Failed to resume container with id $id. Message is: ${ex.getMessage}")
            fireDockerOperationErrorEvent(Some(instance), s"Resume failed with message: ${ex.getMessage}")
        }

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

      log.info("Stopping container...")
      implicit val timeout: Timeout = Timeout(10 seconds)

      (dockerActor ? stop(instance.dockerId.get)).map{
        _ => log.info(s"Instance $id stopped.")
          instanceDao.setStateFor(instance.id.get, InstanceState.Stopped)
          fireStateChangedEvent(instance)
      }.recover {
        case ex: Exception =>
          log.warning(s"Failed to stop container with id $id. Message is: ${ex.getMessage}")
          fireDockerOperationErrorEvent(Some(instance), s"Stop failed with message: ${ex.getMessage}")
      }

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
        implicit val timeout: Timeout = Timeout(10 seconds)

        (dockerActor ? start(instance.dockerId.get)).map{
          _ => log.info(s"Instance $id started.")
        }.recover {
          case ex: Exception =>
            log.warning(s"Failed to start container with id $id. Message is: ${ex.getMessage}")
            fireDockerOperationErrorEvent(Some(instance), s"Start failed with message: ${ex.getMessage}")
        }

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
      if (instance.instanceState != InstanceState.Running) {
        log.info("Deleting container...")

        implicit val timeout: Timeout = Timeout(10 seconds)

        (dockerActor ? delete(instance.dockerId.get)).map{
          _ => log.info(s"Container for instance $id deleted.")
        }.recover {
          case ex: Exception =>
            log.warning(s"Failed to delete container for instance with id $id. Message is: ${ex.getMessage}")
            fireDockerOperationErrorEvent(Some(instance), s"Delete failed with message: ${ex.getMessage}")
        }

        //Delete data either way
        instanceDao.removeInstance(id) match {
          case Success(_) =>
            fireNumbersChangedEvent(instance.componentType)
            fireInstanceRemovedEvent(instance)
            OperationResult.Ok
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

  def instanceHasState(id: Long, state: InstanceState): Boolean = {
    instanceDao.getInstance(id) match {
      case Some(instance) => instance.instanceState == state
      case None => false
    }
  }

  def isInstanceDockerContainer(id: Long): Boolean = {
    instanceDao.getDockerIdFor(id).isSuccess
  }

  private def fireNumbersChangedEvent(componentType: ComponentType): Unit = {
    val newNumber = instanceDao.getInstancesOfType(componentType).size
    eventActor ! RegistryEventFactory.createNumbersChangedEvent(componentType, newNumber)
  }

  private def fireInstanceAddedEvent(addedInstance: Instance): Unit = {
    val event = RegistryEventFactory.createInstanceAddedEvent(addedInstance)
    eventActor ! event
    instanceDao.addEventFor(addedInstance.id.get, event)
  }

  private def fireInstanceRemovedEvent(removedInstance: Instance): Unit = {
    //Do not add removed event, instance will not be present in DAO anymore
    eventActor ! RegistryEventFactory.createInstanceRemovedEvent(removedInstance)
  }

  private def fireStateChangedEvent(updatedInstance: Instance): Unit = {
    val event = RegistryEventFactory.createStateChangedEvent(updatedInstance)
    eventActor ! event
    instanceDao.addEventFor(updatedInstance.id.get, event)
  }

  private def fireDockerOperationErrorEvent(affectedInstance: Option[Instance], errorMessage: String): Unit = {
    val event = RegistryEventFactory.createDockerOperationErrorEvent(affectedInstance, errorMessage)
    eventActor ! event
    if(affectedInstance.isDefined){
      instanceDao.addEventFor(affectedInstance.get.id.get, event)
    }
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

  private def assignmentAllowed(instanceType: ComponentType) : Boolean = {
    instanceType == ComponentType.Crawler || instanceType == ComponentType.WebApi || instanceType == ComponentType.WebApp
  }

  private def compatibleTypes(instanceType: ComponentType, dependencyType: ComponentType) : Boolean = {
    instanceType match {
      case ComponentType.Crawler => dependencyType == ComponentType.ElasticSearch
      case ComponentType.WebApi => dependencyType == ComponentType.ElasticSearch
      case ComponentType.WebApp => dependencyType == ComponentType.WebApi
      case _ => false
    }
  }

  object OperationResult extends Enumeration {
    val IdUnknown: Value = Value("IdUnknown")
    val NoDockerContainer: Value = Value("NoDockerContainer")
    val IsDockerContainer: Value = Value("IsDockerContainer")
    val InvalidStateForOperation: Value = Value("InvalidStateForOperation")
    val InvalidTypeForOperation: Value = Value("InvalidTypeForOperation")
    val Ok: Value = Value("Ok")
    val InternalError: Value = Value("InternalError")
  }

}

