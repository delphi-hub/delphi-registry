package de.upb.cs.swt.delphi.instanceregistry.Docker

import akka.actor.{Actor, ActorLogging, ActorSystem, Props, Status}
import akka.http.scaladsl.model.ws.Message
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.Docker.DockerActor._
import de.upb.cs.swt.delphi.instanceregistry.Registry
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType
import org.reactivestreams.Publisher

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class DockerActor(connection: DockerConnection) extends Actor with ActorLogging {

  implicit val system: ActorSystem = Registry.system
  implicit val materializer: ActorMaterializer = Registry.materializer
  implicit val ec: ExecutionContext = system.dispatcher
  val container = new ContainerCommands(connection)

  case class DockerClient(connection: DockerConnection)


  log.info(s"DockerActor started")

  def receive: PartialFunction[Any, Unit] = {

    case start(containerId) =>
      log.info(s"Docker Container started")
     Try(Await.result(container.start(containerId), Duration.Inf)) match {
       case Success(_) =>
         sender ! Status.Success
       case Failure(ex) =>
         sender ! Status.Failure(ex)
     }

    case create(componentType, instanceId, containerName) =>

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
          log.info(s"Docker Instance created and started, host is $traefikHostUrl")
          sender ! Success(containerResult.Id, traefikHostUrl, instancePort)
      }

    case stop(containerId) =>
      log.info(s"Stopping docker container..")

      Try(Await.result(container.stop(containerId), Duration.Inf)) match {
        case Success(_) =>
          sender ! Status.Success
        case Failure(ex) =>
          sender ! Status.Failure(ex)
      }



    case delete(containerId) =>
      log.info(s"Deleting docker container..")
      Try(Await.result(container.remove(containerId, force = false, removeVolumes = false), Duration.Inf)) match {
        case Success(_) =>
          sender ! Status.Success
        case Failure(ex) =>
          sender ! Status.Failure(ex)
      }

    case pause(containerId) =>
      log.info(s"Pausing docker container..")
      Try(Await.result(container.pause(containerId), Duration.Inf)) match {
        case Success(_) =>
          sender ! Status.Success
        case Failure(ex) =>
          sender ! Status.Failure(ex)
      }

    case unpause(containerId) =>
      log.info(s"Unpausing docker container..")
      Try(Await.result(container.unpause(containerId), Duration.Inf)) match {
        case Success(_) =>
          sender ! Status.Success
        case Failure(ex) =>
          sender ! Status.Failure(ex)
      }

    case restart(containerId) =>
      log.info(s"Restarting docker container..")
      Try(Await.result(container.restart(containerId), Duration.Inf)) match {
        case Success(_) =>
          sender ! Status.Success
        case Failure(ex) =>
          sender ! Status.Failure(ex)
      }

    case runCommand(containerId, command, attachStdin, attachStdout, attachStderr, detachKeys, privileged, tty, user) =>
      log.info(s"running command in docker container..")
      val createCommand = Try(Await.result(container.commandCreate(containerId, command, attachStdin, attachStdout, attachStderr, detachKeys, privileged, tty, user), Duration.Inf))
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

    case logs(containerId: String, stdErrSelected: Boolean, stream: Boolean) =>

      log.info(s"Fetching Container logs: stdErrSelected -> $stdErrSelected, stream -> $stream")

      if(!stream){
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
}

object DockerActor {

  def props(connection: DockerConnection) = Props(new DockerActor(connection: DockerConnection))

  case class start(containerId: String)

  case class create(componentType: ComponentType, instanceId: Long, containerName: Option[ContainerName] = None)

  case class stop(containerId: String)

  case class delete(containerId: String)

  case class pause(containerId: String)

  case class unpause(containerId: String)

  case class restart(containerId: String)

  case class logs(containerId: String, stdErrSelected: Boolean, stream: Boolean)

  case class runCommand(
                         containerId: String,
                         command: String,
                         attachStdin: Option[Boolean],
                         attachStdout: Option[Boolean],
                         attachStderr: Option[Boolean],
                         detachKeys: Option[String],
                         privileged: Option[Boolean],
                         tty: Option[Boolean],
                         user: Option[String]
                       )
}
