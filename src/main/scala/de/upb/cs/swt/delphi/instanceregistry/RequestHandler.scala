// Copyright (C) 2018 The Delphi Team.
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package de.upb.cs.swt.delphi.instanceregistry

import akka.actor._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.Message
import akka.pattern.{AskTimeoutException, ask}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.util.Timeout
import de.upb.cs.swt.delphi.instanceregistry.Docker.DockerActor._
import de.upb.cs.swt.delphi.instanceregistry.Docker.{ContainerAlreadyStoppedException, DockerActor, DockerConnection}
import de.upb.cs.swt.delphi.instanceregistry.authorization.AuthProvider
import de.upb.cs.swt.delphi.instanceregistry.connection.RestClient
import de.upb.cs.swt.delphi.instanceregistry.daos.{AuthDAO, InstanceDAO}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.LinkEnums.LinkState
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model._
import org.reactivestreams.Publisher

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}


// scalastyle:off number.of.methods
class RequestHandler(configuration: Configuration, authDao: AuthDAO, instanceDao: InstanceDAO, connection: DockerConnection) extends AppLogging {


  implicit val system: ActorSystem = Registry.system
  implicit val materializer: Materializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  val authProvider: AuthProvider = new AuthProvider(authDao)

  val (eventActor, eventPublisher) = Source.actorRef[RegistryEvent](bufferSize = 10, OverflowStrategy.dropNew)
    .toMat(Sink.asPublisher(fanout = true))(Keep.both)
    .run()
  val dockerActor: ActorRef = system.actorOf(DockerActor.props(connection))

  def initialize(): Unit = {
    log.info("Initializing request handler...")
    instanceDao.initialize()
    authDao.initialize()
    if (!instanceDao.allInstances().exists(instance => instance.name.equals("Default ElasticSearch Instance"))) {
      //Add default ES instance
      handleRegister(Instance(None,
        configuration.defaultElasticSearchInstanceHost,
        configuration.defaultElasticSearchInstancePort,
        "Default ElasticSearch Instance",
        ComponentType.ElasticSearch,
        None,
        InstanceState.Running,
        List("Default"),
        List.empty[InstanceLink],
        List.empty[InstanceLink]))
    }
    log.info("Done initializing request handler.")
  }

  def shutdown(): Unit = {
    eventActor ! PoisonPill
    instanceDao.shutdown()
    authDao.shutdown()
  }

  /**
    * Called when a new instance registers itself, meaning it is not running in a docker container. Will ignore the
    * parameter instances' id, dockerId and state
    *
    * @param instance Instance that is registering
    * @return Newly assigned ID if successful
    */
  def handleRegister(instance: Instance): Try[Long] = {

    val noIdInstance = Instance(id = None, name = instance.name, host = instance.host,
      portNumber = instance.portNumber, componentType = instance.componentType,
      dockerId = None, instanceState = InstanceState.Running, labels = instance.labels,
      linksTo = List.empty[InstanceLink], linksFrom = List.empty[InstanceLink])

    instanceDao.addInstance(noIdInstance) match {
      case Success(id) =>
        fireNumbersChangedEvent(instanceDao.getInstance(id).get.componentType)
        fireInstanceAddedEvent(instanceDao.getInstance(id).get)
        log.info(s"Assigned new id $id to registering instance with name ${instance.name}.")
        Success(id)
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

  def getAllInstancesOfType(compType: Option[ComponentType]): List[Instance] = {
    if (compType.isDefined) {
      instanceDao.getInstancesOfType(compType.get)
    } else {
      instanceDao.allInstances()
    }
  }

  def getNumberOfInstances(compType: Option[ComponentType]): Int = {
    if (compType.isDefined) {
      instanceDao.allInstances().count(i => i.componentType == compType.get)
    } else {
      instanceDao.allInstances().length
    }

  }

  def getEventList(id: Long, startPage: Long, pageItems: Long, limitItems: Long): Try[List[RegistryEvent]] = {
    instanceDao.getEventsFor(id, startPage, pageItems, limitItems)
  }

  def generateConfigurationInfo(): ConfigurationInfo = {
    ConfigurationInfo(DockerHttpUri = configuration.dockerUri, TraefikProxyUri = configuration.traefikUri)
  }

  def getMatchingInstanceOfType(callerId: Long, compType: ComponentType): (OperationResult.Value, Try[Instance]) = {
    log.info(s"Started matching: Instance with id $callerId is looking for instance of type $compType.")
    if (!instanceDao.hasInstance(callerId)) {
      log.warning(s"Matching failed: No instance with id $callerId was found.")
      (OperationResult.IdUnknown, Failure(new RuntimeException(s"Id $callerId not present.")))
    } else {
      tryLinkMatching(callerId, compType) match {
        case Success(instance) =>
          log.debug(s"Matching finished: First try yielded result $instance.")
          (OperationResult.Ok, Success(instance))
        case Failure(ex) =>
          log.warning(s"Matching pending: First try failed, message was ${ex.getMessage}")
          tryLabelMatching(callerId, compType) match {
            case Success(instance) =>
              log.debug(s"Matching finished: Second try yielded result $instance.")
              (OperationResult.Ok, Success(instance))
            case Failure(ex2) =>
              log.warning(s"Matching pending: Second try failed, message was ${ex2.getMessage}")
              tryDefaultMatching(compType) match {
                case Success(instance) =>
                  log.debug(s"Matching finished: Default matching yielded result $instance.")
                  (OperationResult.Ok, Success(instance))
                case Failure(ex3) =>
                  log.warning(s"Matching failed: Default matching did not yield result, message was ${ex3.getMessage}.")
                  (OperationResult.InternalError, Failure(ex3))
              }
          }
      }
    }
  }

  def handleInstanceLinkCreated(instanceIdFrom: Long, instanceIdTo: Long): OperationResult.Value = {
    if (!instanceDao.hasInstance(instanceIdFrom) || !instanceDao.hasInstance(instanceIdTo)) {
      OperationResult.IdUnknown
    } else {
      val (instanceFrom, instanceTo) = (instanceDao.getInstance(instanceIdFrom).get, instanceDao.getInstance(instanceIdTo).get)
      if (compatibleTypes(instanceFrom.componentType, instanceTo.componentType)) {
        val link = InstanceLink(instanceIdFrom, instanceIdTo, LinkState.Assigned)

        instanceDao.addLink(link) match {
          case Success(_) =>
            fireLinkAddedEvent(link)
            OperationResult.Ok
          case Failure(_) => OperationResult.InternalError //Should not happen, as ids are being verified above!
        }
      } else {
        OperationResult.InvalidTypeForOperation
      }
    }
  }

  def handleInstanceAssignment(instanceId: Long, newDependencyId: Long): OperationResult.Value = {
    if (!instanceDao.hasInstance(instanceId) || !instanceDao.hasInstance(newDependencyId)) {
      OperationResult.IdUnknown
    } else if (!isInstanceDockerContainer(instanceId)) {
      OperationResult.NoDockerContainer
    } else {
      val instance = instanceDao.getInstance(instanceId).get
      val dependency = instanceDao.getInstance(newDependencyId).get

      if (assignmentAllowed(instance.componentType) && compatibleTypes(instance.componentType, dependency.componentType)) {
        val link = InstanceLink(instanceId, newDependencyId, LinkState.Assigned)
        if (instanceDao.addLink(link).isFailure) {
          //This should not happen, as ids are being verified above!
          OperationResult.InternalError
        } else {

          fireLinkAddedEvent(link)

          implicit val timeout: Timeout = configuration.dockerOperationTimeout

          (dockerActor ? RestartMessage(instance.dockerId.get)).map {
            _ =>
              log.info(s"Instance $instanceId restarted.")
              instanceDao.setStateFor(instance.id.get, InstanceState.Stopped) //Set to stopped, will report start automatically
              fireStateChangedEvent(instanceDao.getInstance(instanceId).get)
          }.recover {
            case ex: Exception =>
              log.warning(s"Failed to restart container with id $instanceId. Message is: ${ex.getMessage}")
              fireDockerOperationErrorEvent(Some(instance), s"Pause failed with message: ${ex.getMessage}")
          }
          OperationResult.Ok
        }
      } else {
        OperationResult.InvalidTypeForOperation
      }


    }
  }

  def handleMatchingResult(callerId: Long, matchedInstanceId: Long, matchingSuccess: Boolean): OperationResult.Value = {
    if (!instanceDao.hasInstance(callerId) || !instanceDao.hasInstance(matchedInstanceId)) {
      OperationResult.IdUnknown
    } else {
      val matchedInstance = instanceDao.getInstance(matchedInstanceId).get
      //Update list of matching results
      instanceDao.addMatchingResult(matchedInstanceId, matchingSuccess)
      //Update state of matchedInstance accordingly
      if (matchingSuccess && matchedInstance.instanceState == InstanceState.NotReachable) {
        instanceDao.setStateFor(matchedInstanceId, InstanceState.Running)
        fireStateChangedEvent(instanceDao.getInstance(matchedInstanceId).get) //Re-retrieve instance bc reference was invalidated by 'setStateFor'
      } else if (!matchingSuccess && matchedInstance.instanceState == InstanceState.Running) {
        instanceDao.setStateFor(matchedInstanceId, InstanceState.NotReachable)
        fireStateChangedEvent(instanceDao.getInstance(matchedInstanceId).get) //Re-retrieve instance bc reference was invalidated by 'setStateFor'
      }
      log.debug(s"Applied matching result $matchingSuccess to instance with id $matchedInstanceId.")

      //Update link state
      if (!matchingSuccess) {

        setActiveLinksToFailed(matchedInstanceId) match {
          case Success(_) =>
            OperationResult.Ok
          case Failure(_) =>
            // Message logged by method
            OperationResult.InternalError
        }
      } else {
        OperationResult.Ok
      }
    }
  }


  def setActiveLinksToFailed(failedInstanceId: Long): Try[Unit] = {

    val linksToFailedInstance = instanceDao.getLinksTo(failedInstanceId)
    var errors = false

    for (link <- linksToFailedInstance) {
      //Do not update outdated links
      if (link.linkState == LinkState.Assigned) {
        val newLink = InstanceLink(link.idFrom, failedInstanceId, LinkState.Failed)
        instanceDao.updateLink(newLink) match {
          case Success(_) =>
            fireLinkStateChangedEvent(link)
          case Failure(ex) =>
            errors = true
            log.warning(s"There was a failure while updating the link state ${ex.getMessage}")
        }
      }
    }

    if(errors) Failure(new RuntimeException("Link updates unsuccessful")) else Success()
  }

  // scalastyle:off method.length
  def handleDeploy(componentType: ComponentType, name: Option[String]): Try[Long] = {
    log.debug(s"Deploying container of type $componentType")
    val instance = Instance(None,
      "",
      -1L,
      name.getOrElse(s"Generic $componentType"),
      componentType,
      None,
      InstanceState.Deploying,
      List.empty[String],
      List.empty[InstanceLink],
      List.empty[InstanceLink]
    )

    instanceDao.addInstance(instance) match {
      case Success(id) =>
        implicit val timeout: Timeout = configuration.dockerOperationTimeout

        val future: Future[Any] = dockerActor ? DeployMessage(componentType, id)
        val deployResult = Await.result(future, timeout.duration).asInstanceOf[Try[(String, String, Int, String)]]

        deployResult match {
          case Failure(ex) =>
            log.warning(s"Failed to deploy container, docker host not reachable. Message ${ex.getMessage}")
            instanceDao.removeInstance(id)
            fireDockerOperationErrorEvent(None, s"Deploy failed with message: ${ex.getMessage}")
            Failure(new RuntimeException(s"Failed to deploy container, docker host not reachable (${ex.getMessage})."))
          case Success((dockerId, host, port, traefikHost)) =>
            log.info(s"Deployed new $componentType container with docker id: $dockerId, host: $host, port: $port and Traefik host: $traefikHost.")

            val traefikConfig = TraefikConfiguration(traefikHost, configuration.traefikUri)

            val newInstance = Instance(Some(id),
              host,
              port,
              name.getOrElse(s"Generic $componentType"),
              componentType,
              Some(dockerId),
              InstanceState.Deploying,
              List.empty[String],
              List.empty[InstanceLink],
              List.empty[InstanceLink],
              Some(traefikConfig)
            )

            instanceDao.updateInstance(newInstance) match {
              case Success(_) =>
                log.info("Instance successfully registered.")
                fireInstanceAddedEvent(newInstance)
                fireNumbersChangedEvent(newInstance.componentType)
                Success(id)
              case Failure(x) =>
                log.warning(s"Failed to register. Exception: $x")
                Failure(x)
            }
        }
      case Failure(ex) =>
        Failure(ex)
    }
  }

  // scalastyle:on method.length

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
  def handleReportFailure(id: Long): OperationResult.Value = {
    if (!instanceDao.hasInstance(id)) {
      OperationResult.IdUnknown
    } else if (!isInstanceDockerContainer(id)) {
      OperationResult.NoDockerContainer
    } else {
      val instance = instanceDao.getInstance(id).get
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
        log.debug(s"Handling /pause for instance with id $id...")
        implicit val timeout: Timeout = configuration.dockerOperationTimeout

        (dockerActor ? PauseMessage(instance.dockerId.get)).map {
          _ =>
            log.info(s"Instance $id paused.")
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
        log.debug(s"Handling /resume for instance with id $id...")
        implicit val timeout: Timeout = configuration.dockerOperationTimeout

        (dockerActor ? UnpauseMessage(instance.dockerId.get)).map {
          _ =>
            log.info(s"Instance $id resumed.")
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

  // scalastyle:off method.length cyclomatic.complexity
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
      val instance = instanceDao.getInstance(id).get

      if (instance.componentType == ComponentType.ElasticSearch || instance.componentType == ComponentType.DelphiManagement) {
        log.warning(s"Cannot stop instance of type ${instance.componentType}.")
        OperationResult.InvalidTypeForOperation
      } else {
        log.debug(s"Calling /stop on non-docker instance $instance..")
        RestClient.executePost(RestClient.getUri(instance) + "/stop").map {
          response =>
            log.info(s"Request to /stop returned $response")
            if (response.status == StatusCodes.OK) {
              log.info(s"Instance with id $id has been shut down successfully.")
            } else {
              log.warning(s"Failed to shut down instance with id $id. Status code was: ${response.status}")
            }
        }.recover {
          case ex: Exception =>
            log.warning(s"Failed to shut down instance with id $id. Message is: ${ex.getMessage}")
        }
        handleDeregister(id)
        OperationResult.Ok
      }
    } else if (instanceDao.getInstance(id).get.instanceState != InstanceState.Paused) {
      log.debug(s"Handling /stop for instance with id $id...")

      val instance = instanceDao.getInstance(id).get

      implicit val timeout: Timeout = configuration.dockerOperationTimeout

      (dockerActor ? StopMessage(instance.dockerId.get)).map {
        _ =>
          log.info(s"Docker Instance $id stopped.")
          instanceDao.setStateFor(instance.id.get, InstanceState.Stopped)
          fireStateChangedEvent(instanceDao.getInstance(instance.id.get).get)
      }.recover {
        case atx: AskTimeoutException => //Timeout: Most likely operation will be completed in the background, so update state anyway
          log.warning(s"Ask timed out with timeout $timeout. Message was ${atx.getMessage}")
          instanceDao.setStateFor(instance.id.get, InstanceState.Stopped)
          fireStateChangedEvent(instance)
        case casx: ContainerAlreadyStoppedException => //Container already stopped in docker. Update state to be consistent.
          log.warning(s"Container was already stopped. Message was ${casx.getMessage}")
          instanceDao.setStateFor(instance.id.get, InstanceState.Stopped)
          fireStateChangedEvent(instance)
        case ex => //Docker not reachable, etc, do not update state
          log.warning(s"Failed to stop container with id $id. Message is: ${ex.getMessage}")
          fireDockerOperationErrorEvent(Some(instance), s"Stop failed with message: ${ex.getMessage}")
      }

      OperationResult.Ok
    } else {
      log.warning(s"Cannot stop paused docker container for instance with id $id")
      OperationResult.InvalidStateForOperation
    }
  }

  // scalastyle:on method.length cyclomatic.complexity

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
      log.debug(s"Handling /start for instance with id $id...")
      val instance = instanceDao.getInstance(id).get
      if (instance.instanceState == InstanceState.Stopped) {
        implicit val timeout: Timeout = configuration.dockerOperationTimeout

        (dockerActor ? StartMessage(instance.dockerId.get)).map {
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
      log.debug(s"Handling /delete for instance with id $id...")
      val instance = instanceDao.getInstance(id).get

      //It is not safe to delete instances when other running instances depend on it!
      val usedBy = instance.linksTo.find(link => link.linkState == LinkState.Assigned)
      val notSafeToDelete = usedBy.isDefined && instanceDao.getInstance(usedBy.get.idFrom).get.instanceState == InstanceState.Running
      if (notSafeToDelete) {
        OperationResult.BlockingDependency
      } else if (instance.instanceState != InstanceState.Running) {
        implicit val timeout: Timeout = configuration.dockerOperationTimeout

        (dockerActor ? DeleteMessage(instance.dockerId.get)).map {
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

  /**
    * Retrieves links from the instance with the specified id.
    *
    * @param id Id of the specified instance
    * @return Success(listOfLinks) if id is present, Failure otherwise
    */
  def handleGetLinksFrom(id: Long): Try[List[InstanceLink]] = {
    if (!instanceDao.hasInstance(id)) {
      Failure(new RuntimeException(s"Cannot get links from $id, that id is unknown."))
    } else {
      Success(instanceDao.getLinksFrom(id))
    }
  }

  /**
    * Retrieves links to the instance with the specified id.
    *
    * @param id Id of the specified instance
    * @return Success(listOfLinks) if id is present, Failure otherwise
    */
  def handleGetLinksTo(id: Long): Try[List[InstanceLink]] = {
    if (!instanceDao.hasInstance(id)) {
      Failure(new RuntimeException(s"Cannot get links to $id, that id is unknown."))
    } else {
      Success(instanceDao.getLinksTo(id))
    }
  }

  /**
    * Retrieves the current instance network, containing all instances and instance links.
    *
    * @return InstanceNetwork
    */
  def handleGetNetwork(): List[Instance] = {
    instanceDao.allInstances()
  }

  /**
    * Add label to instance with specified id
    *
    * @param id    Instance id
    * @param label Label to add
    * @return OperationResult
    */
  def handleAddLabel(id: Long, label: String): OperationResult.Value = {
    if (!instanceDao.hasInstance(id)) {
      OperationResult.IdUnknown
    } else {
      instanceDao.addLabelFor(id, label) match {
        case Success(_) =>
          fireStateChangedEvent(instanceDao.getInstance(id).get)
          OperationResult.Ok
        case Failure(_) => OperationResult.InternalError
      }
    }
  }

  /**
    * Remove label to instance with specified id
    * @param id Instance id
    * @param label Label to add
    * @return
    */
  def handleRemoveLabel(id: Long, label: String): OperationResult.Value = {
    if (!instanceDao.hasInstance(id)) {
      OperationResult.IdUnknown
    } else {
      instanceDao.removeLabelFor(id, label) match {
        case Success(_) =>
          fireStateChangedEvent(instanceDao.getInstance(id).get)
          OperationResult.Ok
        case Failure(_) => OperationResult.InternalError
      }
    }
  }

  /**
    *
    * Returns a source streaming the container logs of the instance with the specified id
    *
    * @param id Id of the instance
    * @return Tuple of OperationResult and Option[Source[...] ]
    */
  def handleGetLogs(id: Long, stdErrSelected: Boolean): (OperationResult.Value, Option[String]) = {
    if (!instanceDao.hasInstance(id)) {
      (OperationResult.IdUnknown, None)
    } else if (!isInstanceDockerContainer(id)) {
      (OperationResult.NoDockerContainer, None)
    } else {
      val instance = instanceDao.getInstance(id).get

      val f: Future[(OperationResult.Value, Option[String])] =
        (dockerActor ? LogsMessage(instance.dockerId.get, stdErrSelected, stream = false)) (configuration.dockerOperationTimeout).map {
          logVal: Any =>
            val logResult = logVal.asInstanceOf[Try[String]]
            logResult match {
              case Success(logContent) =>
                (OperationResult.Ok, Some(logContent))
              case Failure(ex) =>
                log.warning(s"Failed to get logs from actor, exception: ${ex.getMessage}")
                (OperationResult.InternalError, None)
            }

        }.recover {
          case ex: Exception =>
            fireDockerOperationErrorEvent(Some(instance), errorMessage = s"Failed to get logs with message: ${ex.getMessage}")
            (OperationResult.InternalError, None)
        }
      Await.result(f, configuration.dockerOperationTimeout.duration)
    }
  }

  def handleStreamLogs(id: Long, stdErrSelected: Boolean): (OperationResult.Value, Option[Publisher[Message]]) = {
    if (!instanceDao.hasInstance(id)) {
      (OperationResult.IdUnknown, None)
    } else if (!isInstanceDockerContainer(id)) {
      (OperationResult.NoDockerContainer, None)
    } else {
      val instance = instanceDao.getInstance(id).get

      val f: Future[(OperationResult.Value, Option[Publisher[Message]])] =
        (dockerActor ? LogsMessage(instance.dockerId.get, stdErrSelected, stream = true)) (configuration.dockerOperationTimeout).map {
          publisherVal: Any =>
            val publisherResult = publisherVal.asInstanceOf[Try[Publisher[Message]]]
            publisherResult match {
              case Success(publisher) =>
                (OperationResult.Ok, Some(publisher))
              case Failure(ex) =>
                log.warning(s"Failed to stream logs from actor, exception: ${ex.getMessage}")
                (OperationResult.InternalError, None)
            }

        }.recover {
          case ex: Exception =>
            fireDockerOperationErrorEvent(Some(instance), errorMessage = s"Failed to stream logs with message: ${ex.getMessage}")
            (OperationResult.InternalError, None)
        }
      Await.result(f, configuration.dockerOperationTimeout.duration)
    }
  }

  // scalastyle:off method.length cyclomatic.complexity
  /**
    * Tries to match caller to specified component type based on links stored in the dao. If one link is present, it will
    * be selected regardless of its state. If multiple links are present, the assigned link will be returned. If none of
    * the links is assigned, matching will fail. If the component types stored in the links do not match the required
    * component type, matching will fail.
    *
    * @param callerId      Id of the calling instance
    * @param componentType ComponentType to look for
    * @return Try[Instance], Success if matching was successful, Failure otherwise
    */
  private def tryLinkMatching(callerId: Long, componentType: ComponentType): Try[Instance] = {
    log.debug(s"Matching first try: Analyzing links for $callerId...")

    val links = instanceDao.getLinksFrom(callerId).filter(link => link.linkState == LinkState.Assigned)

    links.size match {
      case 0 =>
        log.debug(s"Matching first try failed: No links present.")
        Failure(new RuntimeException("No links for instance."))
      case 1 =>
        val instanceAssigned = instanceDao.getInstance(links.head.idTo)

        if (instanceAssigned.isDefined && instanceAssigned.get.componentType == componentType) {
          log.info(s"Finished matching first try: Successfully matched based on 1 link found. Target is ${instanceAssigned.get}.")
          Success(instanceAssigned.get)
        } else if (instanceAssigned.isDefined && instanceAssigned.get.componentType != componentType) {
          log.error(s"Matching first try failed: One link found, but type ${instanceAssigned.get.componentType} did not match expected type $componentType")
          val link = InstanceLink(links.head.idFrom, links.head.idTo, LinkState.Outdated)
          instanceDao.updateLink(link)
          fireLinkStateChangedEvent(link)
          Failure(new RuntimeException("Invalid target type."))
        } else {
          log.error(s"Matching first try failed: There was one link present, but the target id ${links.head.idTo} was not found.")
          val link = InstanceLink(links.head.idFrom, links.head.idTo, LinkState.Outdated)
          instanceDao.updateLink(link)
          fireLinkStateChangedEvent(link)
          Failure(new RuntimeException("Invalid link for instance."))
        }
      case x =>
        //Multiple links. Try to match to the one assigned link
        links.find(link => link.linkState == LinkState.Assigned) match {
          case Some(instanceLink) =>
            val instanceAssigned = instanceDao.getInstance(instanceLink.idTo)

            if (instanceAssigned.isDefined && instanceAssigned.get.componentType == componentType) {
              log.info(s"Finished matching first try: Matched based on one assigned link found out of $x total links. Target is ${instanceAssigned.get}.")
              Success(instanceAssigned.get)
            } else if (instanceAssigned.isDefined && instanceAssigned.get.componentType != componentType) {
              log.error(s"Matching first try failed: One link found, but type ${instanceAssigned.get.componentType} did not match expected type $componentType")
              val link = InstanceLink(links.head.idFrom, links.head.idTo, LinkState.Outdated)
              instanceDao.updateLink(link)
              fireLinkStateChangedEvent(link)
              Failure(new RuntimeException("Invalid target type."))
            } else {
              log.error(s"Matching first try failed: There was one assigned link present, but the target id ${instanceLink.idTo} was not found.")
              val link = InstanceLink(links.head.idFrom, links.head.idTo, LinkState.Outdated)
              instanceDao.updateLink(link)
              fireLinkStateChangedEvent(link)
              Failure(new RuntimeException("Invalid link for instance."))
            }
          case None =>
            log.error(s"Matching first try failed: There were multiple links present, but none of them was assigned.")
            Failure(new RuntimeException("No links assigned."))

        }
    }
  }

  // scalastyle:on method.length cyclomatic.complexity

  /**
    * Tries to match caller to instance of the specified type based on which instance has the most labels in common with
    * the caller. Will fail if no such instance is found.
    *
    * @param callerId      Id of the calling instance
    * @param componentType ComponentType to match to
    * @return Success(Instance) if successful, Failure otherwise.
    */
  private def tryLabelMatching(callerId: Long, componentType: ComponentType): Try[Instance] = {
    log.debug(s"Matching second try: Analyzing labels for $callerId...")

    val possibleMatches = instanceDao.getInstancesOfType(componentType)

    possibleMatches.size match {
      case 0 =>
        log.debug(s"Matching second try failed: There are no instances of type $componentType present.")
        Failure(new RuntimeException(s"Type $componentType not present."))
      case _ =>
        val labels = instanceDao.getInstance(callerId).get.labels

        val intersectionList = possibleMatches
          .filter(instance => instance.labels.intersect(labels).nonEmpty)
          .sortBy(instance => instance.labels.intersect(labels).size)
          .reverse

        if (intersectionList.nonEmpty) {
          val result = intersectionList.head
          val noOfSharedLabels = result.labels.intersect(labels).size
          log.info(s"Finished matching second try: Successfully matched to  $result based on $noOfSharedLabels shared labels.")
          Success(result)
        } else {
          log.debug(s"Matching second try failed: There are no instance with shared labels to $labels.")
          Failure(new RuntimeException(s"No instance with shared labels."))
        }
    }
  }

  private def tryDefaultMatching(componentType: ComponentType): Try[Instance] = {
    log.debug(s"Matching fallback: Searching for instances of type $componentType ...")
    getNumberOfInstances(Some(componentType)) match {
      case 0 =>
        log.warning(s"Matching failed: Cannot match to any instance of type $componentType, no such instance present.")
        Failure(new RuntimeException(s"Cannot match to any instance of type $componentType, no instance present."))
      case 1 =>
        val instance: Instance = instanceDao.getInstancesOfType(componentType).head
        log.info(s"Finished fallback matching: Only one instance of that type present, matching to instance with id ${instance.id.get}.")
        Success(instance)
      case x =>
        log.info(s"Matching fallback: Found $x instances of type $componentType.")

        instanceDao.getInstancesOfType(componentType).find(instance => instance.instanceState == InstanceState.Running) match {
          case Some(instance) =>
            log.info(s"Finished fallback matching: A running instance of type $componentType was found. Matching to $instance")
            Success(instance)
          case None =>
            log.info(s"Matching fallback: Found $x instance of type $componentType, but none of them is running.")

            //Match to instance with maximum number of consecutive positive matching results
            var maxConsecutivePositiveResults = 0
            var instanceToMatch: Option[Instance] = None

            for (instance <- instanceDao.getInstancesOfType(componentType)) {
              if (countConsecutivePositiveMatchingResults(instance.id.get) > maxConsecutivePositiveResults) {
                maxConsecutivePositiveResults = countConsecutivePositiveMatchingResults(instance.id.get)
                instanceToMatch = Some(instance)
              }
            }

            if (instanceToMatch.isDefined) {
              log.info(s"Finished fallback matching: Matching to id ${instanceToMatch.get.id},it has $maxConsecutivePositiveResults positive results in a row.")
              Success(instanceToMatch.get)
            } else {
              instanceToMatch = Some(instanceDao.getInstancesOfType(componentType).head)
              log.info(s"Finished fallback matching: No difference in available instances found, matching to ${instanceToMatch.get}")
              Success(instanceToMatch.get)
            }
        }
    }
  }

  /**
    * Handles a call to /command. container id and command must be present,
    * Will run the command into the container with provide parameters
    *
    * @param id         container id the command will run on
    * @param command    the command to run
    *                   Format is a single character [a-Z] or ctrl-<@value> where <v@alue> is one of: a-z, @, [, , or _
    * @param privileged runs the process with extended privileges
    * @param user       A string value specifying the user, and optionally, group to run the process inside the container,
    *                   Format is one of: "user", "user:group", "uid", or "uid:gid".
    * @return
    */
  def handleCommand(id: Long, command: String, privileged: Option[Boolean], user: Option[String]): OperationResult.Value = {
    if (!instanceDao.hasInstance(id)) {
      OperationResult.IdUnknown
    } else if (!isInstanceDockerContainer(id)) {
      OperationResult.NoDockerContainer
    } else {
      val instance = instanceDao.getInstance(id).get
      log.info(s"Handling /command for instance with id $id...")
      implicit val timeout: Timeout = configuration.dockerOperationTimeout

      (dockerActor ? RunCommandMessage(instance.dockerId.get, command, privileged, user)).map {
        _ => log.info(s"Command '$command' ran successfully in container with id $id.")
      }.recover {
        case ex: Exception =>
          log.warning(s"Failed to run command $command in container with id $id : (${ex.getMessage}).")
          fireDockerOperationErrorEvent(Some(instance), s"Failed to run command (${ex.getMessage}).")
      }

      OperationResult.Ok

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

  /**
    * Add user to user database
    *
    * @param user The user to add
    * @return Id assigned to that user
    */
  def handleAddUser(user: DelphiUser): Try[Long] = {

    val noIdUser = DelphiUser(id = None, userName = user.userName, secret = user.secret, userType = user.userType)

    authDao.addUser(noIdUser) match {
      case Success(userId) =>
        log.info(s"Successfully handled create user request")
        Success(userId)
      case Failure(x) => Failure(x)
    }
  }

  /**
    * Remove a user with id
    *
    * @param id
    * @return
    */
  def handleRemoveUser(id: Long): Try[Long] = {

    authDao.removeUser(id) match {
      case Success(_) =>
        log.info(s"Successfully handled remove user request")
        Success(id)
      case Failure(x) => Failure(x)
    }
  }

  /**
    * Get a user with id
    *
    * @param id
    * @return
    */
  def getUser(id: Long): Option[DelphiUser] = {
    authDao.getUserWithId(id)
  }

  /**
    * Get all user
    *
    * @return
    */
  def getAllUsers(): List[DelphiUser] = {
    authDao.getAllUser()
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
    if (affectedInstance.isDefined) {
      instanceDao.addEventFor(affectedInstance.get.id.get, event)
    }
  }

  private def fireLinkAddedEvent(link: InstanceLink): Unit = {
    val instanceFrom = instanceDao.getInstance(link.idFrom).get
    val instanceTo = instanceDao.getInstance(link.idTo).get

    val event = RegistryEventFactory.createLinkAddedEvent(link, instanceFrom, instanceTo)

    eventActor ! event

    instanceDao.addEventFor(link.idFrom, event)
    instanceDao.addEventFor(link.idTo, event)
  }

  private def fireLinkStateChangedEvent(link: InstanceLink): Unit = {
    val instanceFrom = instanceDao.getInstance(link.idFrom).get
    val instanceTo = instanceDao.getInstance(link.idTo).get

    val event = RegistryEventFactory.createLinkStateChangedEvent(link, instanceFrom, instanceTo)

    eventActor ! event

    instanceDao.addEventFor(link.idFrom, event)
    instanceDao.addEventFor(link.idTo, event)
  }


  private def countConsecutivePositiveMatchingResults(id: Long): Int = {
    if (!instanceDao.hasInstance(id) || instanceDao.getMatchingResultsFor(id).get.isEmpty) {
      0
    } else {
      val matchingResults = instanceDao.getMatchingResultsFor(id).get.reverse
      var count = 0

      matchingResults.takeWhile(result => result).foreach(_ => count = count + 1)

      count
    }

  }

  private def assignmentAllowed(instanceType: ComponentType): Boolean = {
    instanceType == ComponentType.Crawler || instanceType == ComponentType.WebApi || instanceType == ComponentType.WebApp
  }

  private def compatibleTypes(instanceType: ComponentType, dependencyType: ComponentType): Boolean = {
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
    val BlockingDependency: Value = Value("BlockingDependency")
    val Ok: Value = Value("Ok")
    val InternalError: Value = Value("InternalError")
  }

}

