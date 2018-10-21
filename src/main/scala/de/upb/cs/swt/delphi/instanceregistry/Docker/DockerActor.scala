package de.upb.cs.swt.delphi.instanceregistry.Docker

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.Docker.DockerActor._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

class DockerActor(connection: DockerConnection) extends Actor with ActorLogging {

  implicit val system: ActorSystem = ActorSystem("Docker-Client")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher
  val container = new ContainerCommands(connection)

  log.info(s"DockerActor started")

  def receive: PartialFunction[Any, Unit] = {

    case start(containerId) =>
      log.info(s"Docker Container stopped")
      container.start(containerId)

    case create(containerConfig, containerName) =>
      val createContainer = {
        Await.result(container.create(containerConfig, containerName), Duration.Inf)
      }
      val startContainer = Await.result(container.start(createContainer.Id), Duration.Inf)
      log.info(s"Docker Instance created and started")
      val getInfo = Await.result(container.get(createContainer.Id), Duration.Inf)
      //TODO Fetch IP Address
      log.info("ip address is" + getInfo.IPAddress)
      sender ! createContainer.Id

    case stop(containerId) =>
      log.info(s"Docker Container stopped")
      container.stop(containerId)

    case delete(containerId) =>
      log.info(s"Docker Container removed")
      container.remove(containerId, false, false)

    case x => log.warning("Received unknown message: [{}] ", x)
  }
}

object DockerActor {

  def props(connection: DockerConnection) = Props(new DockerActor(connection: DockerConnection))

  case class start(containerId: String)

  case class create(containerConfig: ContainerConfig, containerName: Option[ContainerName] = None)

  case class stop(containerId: String)

  case class delete(containerId: String)


}
