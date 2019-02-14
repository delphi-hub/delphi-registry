// Copyright (C) 2018 The Delphi Team.
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
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

import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.DelphiUserEnums.DelphiUserType

class DatabaseAuthDAO (configuration : Configuration) extends AuthDAO with AppLogging{

  implicit val system : ActorSystem = Registry.system
  implicit val materializer : ActorMaterializer = ActorMaterializer()
  implicit val ec : ExecutionContext = system.dispatcher

  private val users : TableQuery[Users] = TableQuery[Users]
  private var dbAuth = Database.forURL(configuration.authDatabaseHost + configuration.authDatabaseName,
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

  override def addUser(delphiUser : DelphiUser) : Try[String] = {
    if(hasUserWithUsername(delphiUser.userName)){
      Failure(new RuntimeException(s"username ${delphiUser.userName} is already exist."))
    } else {
      val id = 0L //Will be set by DB
      val userName = delphiUser.userName
      val secret = delphiUser.secret
      val userType = delphiUser.userType.toString

      val addFuture: Future[Long] = dbAuth.run((users returning users.map(_.id)) += (id, userName, hashString(secret), userType))
      val userId = Await.result(addFuture, Duration.Inf)

      log.info(s"Added user ${delphiUser.userName} with id $userId to database.")
      Success(userName)
    }

  }

  override def removeUser(id: Long) : Try[Unit] = {
    if(hasUserWithId(id)) {
      removeUserWithId(id)
      Success(log.info(s"Successfully removed user with id $id."))
    }else{
      val msg = s"Cannot remove user with id $id, that id is not present."
      log.warning(msg)
      Failure(new RuntimeException(msg))
    }
  }

  override def getUserWithId(id: Long): Option[DelphiUser] = {
    if(hasUserWithId(id)) {
      val result = Await.result(dbAuth.run(users.filter(_.id === id).result.headOption), Duration.Inf)
      Some(dataToObjectUser(result))
    } else {
      None
    }
  }

  override def getAlllUser(): List[DelphiUser] = {
    var listUser = List[DelphiUser]()
    val hasData = Await.result(dbAuth.run(users.exists.result), Duration.Inf)
    if(hasData){
      val resultAll = Await.result(dbAuth.run(users.result), Duration.Inf)
      val listAll = List() ++ resultAll.map(_.value)
      listUser = listAll.map(c => dataToObjectUser(Option(c)))
    }
    listUser
  }

  override def hasUserWithUsername(username: String) : Boolean = {
    Await.result(dbAuth.run(users.filter(_.userName === username).exists.result), Duration.Inf)
  }

  override def hasUserWithId(id: Long): Boolean ={
    Await.result(dbAuth.run(users.filter(_.id === id).exists.result), Duration.Inf)
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
    DelphiUser.apply(Option(id), userName, secret, getDelphiUserTypeFromString(userType))
  }

  private def getDelphiUserTypeFromString(userType: String): DelphiUserType ={
    val result = userType match {
      case "User" => DelphiUserType.User
      case "Admin" =>DelphiUserType.Admin
    }
    result
  }

  private def dbTest(): Boolean = {
    try {
      val dbTimeoutSeconds = 5
      dbAuth.createSession.conn.isValid(dbTimeoutSeconds)
    } catch {
      case e: Throwable => throw e
    }
  }

  private def removeAllUsers(): Unit = {
    val action = users.delete
    dbAuth.run(action)
  }

  private def dataToObjectUser(options : Option[(Long, String, String, String)]): DelphiUser ={
    val optionValue = options.get
    DelphiUser(Some(optionValue._1), optionValue._2, optionValue._3, getUserTypeFromString(optionValue._4))
  }

  private def getUserTypeFromString(userType: String): DelphiUserType ={
    val result = userType match {
      case "Admin" => DelphiUserType.Admin
      case "User" => DelphiUserType.User
    }
    result
  }

  private def hashString(secret: String): String = {
    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString("")
  }

  private def removeUserWithId(id: Long): Unit ={
    val q = users.filter(_.id === id)
    val action = q.delete
    dbAuth.run(action)
  }

  def setDatabaseConfiguration(databaseHost: String = "", databaseName: String = "", databaseDriver: String = "", databaseUsername: String = "", databasePassword: String = ""): Unit ={
    dbAuth = Database.forURL(databaseHost + databaseName, driver = databaseDriver, user = databaseUsername, password = databasePassword)
    initialize()
  }
}
