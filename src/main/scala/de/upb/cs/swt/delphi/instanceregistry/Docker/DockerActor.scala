package de.upb.cs.swt.delphi.instanceregistry.Docker

import akka.actor.{Actor, ActorLogging, ActorSystem, Props, Status}
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.Docker.DockerActor._
import de.upb.cs.swt.delphi.instanceregistry.Registry
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType

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
      val containerConfig = ContainerConfig(Image = DockerImage.getImageName(componentType),
        Env = Seq(s"INSTANCE_ID=$instanceId", s"DELPHI_IR_URI=${Registry.configuration.uriInLocalNetwork}"))

      val createCommand = Try(Await.result(container.create(containerConfig, containerName), Duration.Inf))
      createCommand match {
        case Failure(ex) => sender ! Failure(ex)
        case Success(containerResult) =>
          Await.ready(container.start(containerResult.Id), Duration.Inf)
          log.info(s"Docker Instance created and started")
          val containerInfo = Await.result(container.get(containerResult.Id), Duration.Inf)

          val instancePort = componentType match {
            case ComponentType.Crawler => Registry.configuration.defaultCrawlerPort
            case ComponentType.WebApi => Registry.configuration.defaultWebApiPort
            case ComponentType.WebApp => Registry.configuration.defaultWepAppPort
            case t => throw new RuntimeException(s"Invalid component type $t, cannot deploy container.")
          }

          log.info("ip address is " + containerInfo.IPAddress)
          sender ! Success(containerResult.Id, containerInfo.IPAddress, instancePort)
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

    case logs(containerId: String) =>
     log.info(s"Fetching Container logs")
      container.logs(containerId)

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

  case class logs(containerId: String)

}
