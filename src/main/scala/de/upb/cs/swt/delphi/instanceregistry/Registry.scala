package de.upb.cs.swt.delphi.instanceregistry

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
import de.upb.cs.swt.delphi.instanceregistry.Docker.DockerActor.create
import de.upb.cs.swt.delphi.instanceregistry.Docker.{ContainerConfig, DockerActor, DockerConnection}
import de.upb.cs.swt.delphi.instanceregistry.connection.Server

import scala.concurrent.ExecutionContext
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

    implicit val timeout = Timeout(10 seconds)
    //  val future = dockerActor ? create(ContainerConfig("registry_test"))

    //    val future: Future[Any] = dockerActor ? create(ContainerConfig("registry"))
    //val future: Future[Any] = ask(dockerActor, create(ContainerConfig("24santoshr/delphi-registry")))


    dockerActor ! create(ContainerConfig("24santoshr/delphi-registry"))

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
