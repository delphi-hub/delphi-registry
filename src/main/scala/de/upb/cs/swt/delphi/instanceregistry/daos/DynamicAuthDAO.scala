package de.upb.cs.swt.delphi.instanceregistry.daos

import java.io.File

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.Authenticate
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Configuration, Registry}
import scala.collection.mutable
import scala.concurrent.ExecutionContext

class DynamicAuthDAO (configuration : Configuration) extends AuthDAO with AppLogging{
  implicit val system : ActorSystem = Registry.system
  implicit val materializer : ActorMaterializer = Registry.materializer
  implicit val ec : ExecutionContext = system.dispatcher

  private val users : mutable.Set[Authenticate] = new mutable.HashSet[Authenticate]()

  override def getUserWithUsername(userName: String): Option[Authenticate] =
  {
    if(hasUserWithUsername(userName)) {
      val query = users filter {i => i.userName == userName}
      val user  = query.iterator.next()
      Some(dataToObjectAuthenticate(user.userName, user.secret, user.userType))
    } else {
      None
    }

  }

  override def hasUserWithUsername(username: String) : Boolean = {
    val query = users filter {i => i.userName == username}
    query.nonEmpty
  }

  override def initialize() : Unit = {
    log.info("Initializing dynamic Auth DAO...")
    clearData()
    log.info("Successfully initialized Auth DAO.")

  }

  override def shutdown(): Unit = {
    log.info("Shutting down dynamic Auth DAO...")
    clearData()
    deleteRecoveryFile()
    log.info("Shutdown complete dynamic Auth DAO.")
  }

  private def dataToObjectAuthenticate(userName: String, secret: String, userType: String): Authenticate = {
    Authenticate.apply(userName, secret, userType)
  }


  private[daos] def clearData() : Unit = {
    users.clear()
  }

  private[daos] def deleteRecoveryFile() : Unit = {
    log.info("Deleting data recovery file...")
    if(new File(configuration.recoveryFileName).delete()){
      log.info(s"Successfully deleted data recovery file ${configuration.recoveryFileName}.")
    } else {
      log.warning(s"Failed to delete data recovery file ${configuration.recoveryFileName}.")
    }
  }

}
