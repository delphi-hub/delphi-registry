package de.upb.cs.swt.delphi.instanceregistry.Docker

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.Docker.DockerActor.{create, delete, start, stop}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

class DockerActor(connection: DockerConnection) extends Actor with ActorLogging {

  implicit val system: ActorSystem = ActorSystem("Docker-Client")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher
  val container = new ContainerCommands(connection)


  log.info(s"DockerActor started")

  def receive: PartialFunction[Any, Unit] = {
    case start(containerId) => {
      log.info(s"Docker Container started")
      container.start(containerId)
    }
    case create(containerConfig, containerName) => {
      val s = Await.result(container.create(containerConfig, containerName), Duration.Inf)
      log.info(s"Docker Instance created" + s.Id)
      //      val f = Future[Seq[CreateContainerResponse]]  {s.Id, s.Warnings}

      

      //system.terminate()
    }
    case stop(containerId) => {
      log.info(s"Docker Container stopped")
      container.stop(containerId)
    }
    case delete(containerId) => {
      log.info(s"Docker Container removed")
      container.remove(containerId, false, false)
    }
    case x => log.warning("Received unknown message: [{}] ", x)
  }
}

object DockerActor {


  def props(connection: DockerConnection) = Props(new DockerActor(connection: DockerConnection))


  case class start(containerId: String)

  case class create(containerConfig: ContainerConfig,
                    containerName: Option[ContainerName] = None)

  case class stop(containerId: String)

  case class delete(containerId: String)

}
