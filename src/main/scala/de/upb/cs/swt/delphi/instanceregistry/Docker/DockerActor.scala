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
package de.upb.cs.swt.delphi.instanceregistry.Docker

import akka.actor.{Actor, ActorLogging, ActorSystem, Props, Status}
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.Docker.DockerActor._
import de.upb.cs.swt.delphi.instanceregistry.Registry
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success, Try}

class DockerActor(connection: DockerConnection) extends Actor with ActorLogging {

  implicit val system: ActorSystem = Registry.system
  implicit val materializer: ActorMaterializer = Registry.materializer
  implicit val ec: ExecutionContext = system.dispatcher
  val container = new ContainerCommands(connection)

  case class DockerClient(connection: DockerConnection)


  log.info(s"DockerActor started")

  // scalastyle:off method.length cyclomatic.complexity
  def receive: PartialFunction[Any, Unit] = {

    case StartMessage(containerId) =>
      log.debug(s"Docker Container started")
      Try(Await.result(container.start(containerId), Duration.Inf)) match {
        case Success(_) =>
          sender ! Status.Success
        case Failure(ex) =>
          sender ! Status.Failure(ex)
      }

    case DeployMessage(componentType, instanceId, containerName) =>

      val instancePort = componentType match {
        case ComponentType.Crawler => Registry.configuration.defaultCrawlerPort
        case ComponentType.WebApi => Registry.configuration.defaultWebApiPort
        case ComponentType.WebApp => Registry.configuration.defaultWepAppPort
        case t => throw new RuntimeException(s"Invalid component type $t, cannot deploy container.")
      }

      val networkConfig = NetworkConfig(Map(Registry.configuration.traefikDockerNetwork ->  EmptyEndpointConfig()))

      val traefikHostUrl = componentType.toString.toLowerCase + instanceId.toString + "." + Registry.configuration.traefikBaseHost

      val containerConfig = ContainerConfig(
        Image = DockerImage.getImageName(componentType),
        Env = Seq(
          s"INSTANCE_ID=$instanceId",
          s"DELPHI_IR_URI=${Registry.configuration.uriInLocalNetwork}",
          s"DELPHI_JWT_SECRET=${Registry.configuration.jwtSecretKey}"
        ),
        Labels = Map("traefik.frontend.rule" -> s"Host:$traefikHostUrl"),
        ExposedPorts = Map(s"$instancePort/tcp" -> EmptyExposedPortConfig()),
        NetworkingConfig = networkConfig
      )

      val createCommand = Try(Await.result(container.create(containerConfig, containerName), Duration.Inf))
      createCommand match {
        case Failure(ex) => sender ! Failure(ex)
        case Success(containerResult) =>
          Await.ready(container.start(containerResult.Id), Duration.Inf)

          val containerInfo = Await.result(container.get(containerResult.Id), Duration.Inf)

          log.info(s"Docker Instance created and started, ip is ${containerInfo.IPAddress}, host is $traefikHostUrl")

          sender ! Success(containerResult.Id, containerInfo.IPAddress, instancePort, traefikHostUrl)
      }

    case StopMessage(containerId) =>
      log.debug(s"Stopping docker container..")

      Try(Await.result(container.stop(containerId), Duration.Inf)) match {
        case Success(_) =>
          sender ! Status.Success
        case Failure(ex) =>
          sender ! Status.Failure(ex)
      }


    case DeleteMessage(containerId) =>
      log.debug(s"Deleting docker container..")
      Try(Await.result(container.remove(containerId, force = false, removeVolumes = false), Duration.Inf)) match {
        case Success(_) =>
          sender ! Status.Success
        case Failure(ex) =>
          sender ! Status.Failure(ex)
      }

    case PauseMessage(containerId) =>
      log.debug(s"Pausing docker container..")
      Try(Await.result(container.pause(containerId), Duration.Inf)) match {
        case Success(_) =>
          sender ! Status.Success
        case Failure(ex) =>
          sender ! Status.Failure(ex)
      }

    case UnpauseMessage(containerId) =>
      log.debug(s"Unpausing docker container..")
      Try(Await.result(container.unpause(containerId), Duration.Inf)) match {
        case Success(_) =>
          sender ! Status.Success
        case Failure(ex) =>
          sender ! Status.Failure(ex)
      }

    case RestartMessage(containerId) =>
      log.debug(s"Restarting docker container..")
      Try(Await.result(container.restart(containerId), Duration.Inf)) match {
        case Success(_) =>
          sender ! Status.Success
        case Failure(ex) =>
          sender ! Status.Failure(ex)
      }

    case RunCommandMessage(containerId, command, privileged, user) =>
      log.debug(s"running command in docker container..")
      val createCommand = Try(Await.result(container.commandCreate(containerId, command, privileged, user), Duration.Inf))

      createCommand match {
        case Failure(ex) => sender ! Failure(ex)
        case Success(commandResult) =>
          log.info(commandResult.Id)
          Try(Await.ready(container.commandRun(containerId, commandResult.Id), Duration.Inf)) match {
            case Success(_) =>
              sender ! Success(commandResult.Id)
            case Failure(ex) =>
              sender ! Status.Failure(ex)
          }
      }

    case LogsMessage(containerId: String, stdErrSelected: Boolean, stream: Boolean) =>

      log.info(s"Fetching Container logs: stdErrSelected -> $stdErrSelected, stream -> $stream")

      if (!stream) {
        val logResult = Try(Await.result(container.retrieveLogs(containerId, stdErrSelected), Duration.Inf))
        logResult match {
          case Failure(ex) =>
            log.warning(s"Failed to get container logs with ${ex.getMessage}")
            sender ! Failure(ex)
          case Success(logContent) =>
            sender ! Success(logContent)
        }
      } else {
        sender ! container.streamLogs(containerId, stdErrSelected)
      }


    case x => log.warning("Received unknown message: [{}] ", x)
  }
  //scalastyle:on method.length cyclomatic.complexity
}

object DockerActor {

  def props(connection: DockerConnection): Props = Props(new DockerActor(connection: DockerConnection))

  case class StartMessage(containerId: String)

  case class DeployMessage(componentType: ComponentType, instanceId: Long, containerName: Option[ContainerName] = None)

  case class StopMessage(containerId: String)

  case class DeleteMessage(containerId: String)

  case class PauseMessage(containerId: String)

  case class UnpauseMessage(containerId: String)

  case class RestartMessage(containerId: String)

  case class LogsMessage(containerId: String, stdErrSelected: Boolean, stream: Boolean)

  case class RunCommandMessage(containerId: String, command: String, privileged: Option[Boolean], user: Option[String])

}
