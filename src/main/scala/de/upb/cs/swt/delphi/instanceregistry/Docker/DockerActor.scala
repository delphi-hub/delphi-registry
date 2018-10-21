package de.upb.cs.swt.delphi.instanceregistry.Docker

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.Docker.DockerActor._
import de.upb.cs.swt.delphi.instanceregistry.Registry
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

class DockerActor(connection: DockerConnection) extends Actor with ActorLogging {

  implicit val system: ActorSystem = Registry.system
  implicit val materializer: ActorMaterializer = Registry.materializer
  implicit val ec: ExecutionContext = system.dispatcher
  val container = new ContainerCommands(connection)

  case class DockerClient(connection: DockerConnection)


  log.info(s"DockerActor started")

  def receive: PartialFunction[Any, Unit] = {

    case start(containerId) =>
      log.info(s"Docker Container stopped")
      container.start(containerId)

    case create(componentType, instanceId, containerName) =>
      val containerConfig = ContainerConfig(Image = DockerImage.getImageName(componentType), Env = Seq(s"INSTANCE_ID=$instanceId"))
      val createContainer = {
        Await.result(container.create(containerConfig, containerName), Duration.Inf)
      }
      Await.ready(container.start(createContainer.Id), Duration.Inf)
      log.info(s"Docker Instance created and started")
      val containerInfo = Await.result(container.get(createContainer.Id), Duration.Inf)


      val instancePort = componentType match {
        case ComponentType.Crawler => Registry.configuration.defaultCrawlerPort
        case ComponentType.WebApi => Registry.configuration.defaultWebApiPort
        case ComponentType.WebApp => Registry.configuration.defaultWepAppPort
        case t => throw new RuntimeException(s"Invalid component type $t, cannot deploy container.")
      }

      log.info("ip address is" + containerInfo.IPAddress)
      sender ! (createContainer.Id, containerInfo.IPAddress, instancePort)

    case stop(containerId) =>
      log.info(s"Docker Container stopped")
      Await.ready(container.stop(containerId), Duration.Inf)
      sender ! {}

    case delete(containerId) =>
      log.info(s"Docker Container removed")
      container.remove(containerId, force = false, removeVolumes = false)

    case pause(containerId) =>
      Await.ready(container.pause(containerId), Duration.Inf)

    case restart(containerId) =>
      Await.ready(container.restart(containerId), Duration.Inf)
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

  case class restart(containerId: String)

}
