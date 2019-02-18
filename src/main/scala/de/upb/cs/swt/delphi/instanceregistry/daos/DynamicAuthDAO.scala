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
import java.security.MessageDigest

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.DelphiUser
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.DelphiUserEnums.DelphiUserType
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Configuration, Registry}

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

class DynamicAuthDAO (configuration : Configuration) extends AuthDAO with AppLogging{
  implicit val system : ActorSystem = Registry.system
  implicit val materializer : ActorMaterializer = Registry.materializer
  implicit val ec : ExecutionContext = system.dispatcher

  private val users : mutable.Set[DelphiUser] = new mutable.HashSet[DelphiUser]()

  override def getUserWithUsername(userName: String): Option[DelphiUser] =
  {
    if(hasUserWithUsername(userName)) {
      val query = users filter {i => i.userName == userName}
      val user  = query.iterator.next()
      Some(dataToObjectAuthenticate(user.id.get, user.userName, user.secret, user.userType.toString))
    } else {
      None
    }

  }

  override def addUser(delphiUser : DelphiUser) : Try[String] = {
    if(hasUserWithUsername(delphiUser.userName)){
      Failure(new RuntimeException(s"username ${delphiUser.userName} is already exist."))
    } else{
      val id = nextId()
      val newUser = DelphiUser(Some(id), delphiUser.userName, hashString(delphiUser.secret), delphiUser.userType)
      users.add(newUser)

      log.info(s"Added user ${newUser.userName} with id ${newUser.id.get} to database.")
      Success(newUser.userName)
    }

  }

  override def removeUser(id: Long): Try[Unit] = {
    if(hasUserWithId(id)){
      users.remove(users.find(i => i.id.get == id).get)
      Success(log.info(s"Successfully removed user with id $id."))
    } else {
      val msg = s"Cannot remove user with id $id, that id is not present."
      log.warning(msg)
      Failure(new RuntimeException(msg))
    }
  }

  override def getUserWithId(id: Long): Option[DelphiUser] = {
    if(hasUserWithId(id)) {
      val query = users filter {i => i.id.get == id}
      val result  = query.iterator.next()
      Some(result)
    } else {
      None
    }
  }

  override def getAllUser(): List[DelphiUser] = {
    List() ++ users
  }

  override def hasUserWithUsername(username: String) : Boolean = {
    val query = users filter {i => i.userName == username}
    query.nonEmpty
  }

  override def hasUserWithId(id: Long) : Boolean = {
    val query = users filter {i => i.id.get == id}
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
    log.info("Shutdown complete dynamic Auth DAO.")
  }

  private def dataToObjectAuthenticate(id:Long, userName: String, secret: String, userType: String): DelphiUser = {
    DelphiUser.apply(Option(id), userName, secret, getDelphiUserTypeFromString(userType))
  }


  private[daos] def clearData() : Unit = {
    users.clear()
  }

  private def getDelphiUserTypeFromString(userType: String): DelphiUserType ={
    val result = userType match {
      case "User" => DelphiUserType.User
      case "Admin" =>DelphiUserType.Admin
    }
    result
  }

  private def nextId(): Long = {
    if(users.isEmpty){
      0L
    } else {
      (users.map(i => i.id.getOrElse(0L)) max) + 1L
    }
  }

  private def hashString(secret: String): String = {
    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString("")
  }
}
