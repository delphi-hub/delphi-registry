package de.upb.cs.swt.delphi.instanceregistry

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.Docker._
import de.upb.cs.swt.delphi.instanceregistry.connection.Server
import de.upb.cs.swt.delphi.instanceregistry.daos._

import scala.concurrent.ExecutionContext
import scala.language.postfixOps

object Registry extends AppLogging {
  implicit val system: ActorSystem = ActorSystem("delphi-registry")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher


  val configuration = new Configuration()

  private val dao : InstanceDAO  = {
    if (configuration.useInMemoryDB) {
      new DynamicInstanceDAO(configuration)
    } else {
      new DatabaseInstanceDAO(configuration)
    }
  }

  private val authDao: AuthDAO = {
    if (configuration.useInMemoryDB) {
      new DynamicAuthDAO(configuration)
    } else {
      new DatabaseAuthDAO(configuration)
    }
  }

  private val requestHandler = new RequestHandler(configuration, authDao, dao, DockerConnection.fromEnvironment(configuration))

  private val server: Server = new Server(requestHandler)


  def main(args: Array[String]): Unit = {
    requestHandler.initialize()
    log.info("Starting server ...")
    server.startServer(configuration.bindHost, configuration.bindPort)
    log.info("Shutting down ...")
    requestHandler.shutdown()
    system.terminate()
  }
}
