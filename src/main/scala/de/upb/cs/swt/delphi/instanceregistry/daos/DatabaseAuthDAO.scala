package de.upb.cs.swt.delphi.instanceregistry.daos


import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Configuration, Registry}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model._
import slick.lifted.TableQuery

import scala.concurrent.{Await, ExecutionContext}
import slick.jdbc.MySQLProfile.api._
import slick.jdbc.meta.MTable

import scala.concurrent.duration.Duration

class DatabaseAuthDAO (configuration : Configuration) extends AuthDAO with AppLogging{

  implicit val system : ActorSystem = Registry.system
  implicit val materializer : ActorMaterializer = ActorMaterializer()
  implicit val ec : ExecutionContext = system.dispatcher

  private val users : TableQuery[Users] = TableQuery[Users]
  private val dbAuth = Database.forURL(configuration.authDatabaseHost + configuration.authDatabaseName, driver = configuration.authDatabaseDriver, user = configuration.authDatabaseUsername, password = configuration.authDatabasePassword)

  override def getUserWithUsername(userName: String): Option[DelphiUser] =
  {
    if(hasUserWithUsername(userName)) {
      val result = Await.result(dbAuth.run(users.filter(_.userName === userName).result.headOption), Duration.Inf)
      Some(dataToObjectAuthenticate(result.get._2, result.get._3, result.get._4))
    } else {
      None
    }
  }

  override def hasUserWithUsername(username: String) : Boolean = {
    Await.result(dbAuth.run(users.filter(_.userName === username).exists.result), Duration.Inf)
  }

  override def initialize() : Unit = {
    if(dbTest()){
      log.info("Initializing sql auth DAO...")
      val authTables = List(users)
      val authExisting = dbAuth.run(MTable.getTables)
      val authCreateAction = authExisting.flatMap( v => {
        val names = v.map(mt => mt.name.name)
        val createIfNotExist = authTables.filter( table =>
          !names.contains(table.baseTableRow.tableName)).map(_.schema.create)
        dbAuth.run(DBIO.sequence(createIfNotExist))
      })
      Await.result(authCreateAction, Duration.Inf)
      log.info("Successfully initialized.")
    } else {
      log.error("Not found any database with the provided settings.")

      val terminationFuture = system.terminate()

      terminationFuture.onComplete {
        sys.exit(0)
      }
    }

  }

  override def shutdown(): Unit = {
    log.info("Shutting down dynamic auth DAO...")
    log.info("Shutdown complete.")
  }

  private def dataToObjectAuthenticate(userName: String, secret: String, userType: String): DelphiUser = {
    DelphiUser.apply(userName, secret, userType)
  }

  private def dbTest(): Boolean = {
    try {
      dbAuth.createSession.conn.isValid(5)
    } catch {
      case e: Throwable => throw e
    }
  }

  private def removeAllUsers(): Unit = {
    val action = users.delete
    dbAuth.run(action)
  }
}
