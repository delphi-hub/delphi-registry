package de.upb.cs.swt.delphi.instanceregistry

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.Docker._
import de.upb.cs.swt.delphi.instanceregistry.connection.Server

import scala.concurrent.ExecutionContext
import scala.language.postfixOps

object Registry extends AppLogging {
  implicit val system: ActorSystem = ActorSystem("delphi-registry")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher


  val configuration = new Configuration()
  val requestHandler = new RequestHandler(configuration, DockerConnection.fromEnvironment())


  def main(args: Array[String]): Unit = {

    requestHandler.initialize()
    log.info("Starting server ...")
    Server.startServer(configuration.bindHost, configuration.bindPort)
    log.info("Shutting down ...")
    requestHandler.shutdown()
    system.terminate()
  }
}
