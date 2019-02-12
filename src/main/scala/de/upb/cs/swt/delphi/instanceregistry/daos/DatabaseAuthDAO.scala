// Copyright (C) 2018 The Delphi Team.
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

// http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package de.upb.cs.swt.delphi.instanceregistry.daos


import java.nio.charset.StandardCharsets

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

import scala.concurrent.Future
import scala.util.{Failure, Success}
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Configuration, Registry}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model._
import slick.lifted.TableQuery

import scala.concurrent.{Await, ExecutionContext}
import slick.jdbc.MySQLProfile.api._
import slick.jdbc.meta.MTable

import scala.concurrent.duration.Duration
import scala.util.Try
import java.security.MessageDigest

class DatabaseAuthDAO (configuration : Configuration) extends AuthDAO with AppLogging{

  implicit val system : ActorSystem = Registry.system
  implicit val materializer : ActorMaterializer = ActorMaterializer()
  implicit val ec : ExecutionContext = system.dispatcher

  private val users : TableQuery[Users] = TableQuery[Users]
  private val dbAuth = Database.forURL(configuration.authDatabaseHost + configuration.authDatabaseName,
    driver = configuration.authDatabaseDriver,
    user = configuration.authDatabaseUsername,
    password = configuration.authDatabasePassword)

  override def getUserWithUsername(userName: String): Option[DelphiUser] =
  {
    if(hasUserWithUsername(userName)) {
      val result = Await.result(dbAuth.run(users.filter(_.userName === userName).result.headOption), Duration.Inf)
      Some(dataToObjectAuthenticate(result.get._1, result.get._2, result.get._3, result.get._4))
    } else {
      None
    }
  }

  override def addUser(delphiUser : DelphiUser) : Try[Long] = {
    if(hasUserWithUsername(delphiUser.userName)){
      Failure(new RuntimeException(s"username ${delphiUser.userName} is already exist."))
    } else {
      val id = 0L //Will be set by DB
      val userName = delphiUser.userName
      val secret = delphiUser.secret
      val userType = delphiUser.userType

      val addFuture: Future[Long] = dbAuth.run((users returning users.map(_.id)) += (id, userName, hashString(secret), userType))
      val userId = Await.result(addFuture, Duration.Inf)

      log.info(s"Added user ${delphiUser.userName} with id $userId to database.")
      Success(userId)
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

  private def dataToObjectAuthenticate(id:Long, userName: String, secret: String, userType: String): DelphiUser = {
    DelphiUser.apply(Option(id), userName, secret, userType)
  }

  private def dbTest(): Boolean = {
    try {
      val dbTimeoutSeconds = 5
      dbAuth.createSession.conn.isValid(dbTimeoutSeconds)
    } catch {
      case e: Throwable => throw e
    }
  }

  private def hashString(secret: String): String = {
    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString("")
  }
}
