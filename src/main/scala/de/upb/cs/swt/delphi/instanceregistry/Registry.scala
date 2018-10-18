package de.upb.cs.swt.delphi.instanceregistry

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
import akka.pattern.ask
import de.upb.cs.swt.delphi.instanceregistry.Docker.DockerActor.create
import de.upb.cs.swt.delphi.instanceregistry.Docker._
import de.upb.cs.swt.delphi.instanceregistry.connection.Server

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.language.postfixOps

object Registry extends AppLogging {
  implicit val system: ActorSystem = ActorSystem("delphi-registry")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher


  //val client = new DockerClient(DockerConnection.fromEnvironment())
  val configuration = new Configuration()
  val requestHandler = new RequestHandler(configuration, DockerConnection.fromEnvironment())


  def main(args: Array[String]): Unit = {


    val dockerActor = system.actorOf(DockerActor.props(DockerConnection.fromEnvironment()))

    implicit val timeout = Timeout(30 seconds)

    val dockerImage = new DockerImage()
    val componentType = "DelphiManagement"

    log.info("image is " + dockerImage.getImageName(componentType))

    // val dockerId = "84f884f28764cf2f793a642d3bcb5cd3ea10c3540486ec9da7a2934b4613eb28"

    val future = dockerActor ? create(ContainerConfig(dockerImage.getImageName(componentType)))

    val (s_id, s_warn) = Await.result(future, timeout.duration).asInstanceOf[(String, Option[String])]


    log.info("vales are "+s_id, s_warn)

    // val future: Future[Any] = dockerActor ? delete(dockerId)
    //     ask(dockerActor, create(ContainerConfig("24santoshr/delphi-registry")))

    //    dockerActor ! ContainerConfig(dockerImage.getImageName(componentType)))

    //    log.info("image name is "+DockerImage())

    //    dockerActor ! create(ContainerConfig(dockerImage.getImageName(componentType)))

    //  dockerActor ! create(ContainerConfig("24santoshr/delphi-registry"))

    //val result = Await.result(future, Duration.Inf).asInstanceOf[Any]

    log.info("Docker Container created ...")

    requestHandler.initialize()
    log.info("Starting server ...")
    Server.startServer(configuration.bindHost, configuration.bindPort)
    log.info("Shutting down ...")
    requestHandler.shutdown()
    system.terminate()
  }
}
