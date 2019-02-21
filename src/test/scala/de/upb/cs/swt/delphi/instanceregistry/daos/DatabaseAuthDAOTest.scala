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

import de.upb.cs.swt.delphi.instanceregistry.Configuration
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.DelphiUser
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.DelphiUserEnums.DelphiUserType
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

class DatabaseAuthDAOTest extends FlatSpec with Matchers with BeforeAndAfterEach{

  val config = new Configuration()
  val dao : DatabaseAuthDAO = new DatabaseAuthDAO(config)
  dao.setDatabaseConfiguration("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MYSQL","", "org.h2.Driver")

  "The Auth Dao" must "be able to add a user with different username" in {
    val username = dao.addUser(buildUser(id = 1, userName = "test1"))
    assert(username.isSuccess)
    assert(dao.hasUserWithUsername("test1"))
    assert(dao.addUser(buildUser(id = 2, userName = "test1")).isFailure)
  }

  it must "return user with correct id" in {
    val user = dao.getUserWithId(1)
    assert(user.isDefined)
    assert(user.get.id.isDefined)
    assert(user.get.id.get == 1)
  }

  it must "return all user" in {
    val idOption = dao.addUser(buildUser(id = 2, userName = "test2"))
    assert(idOption.isSuccess)
    assert(dao.getAllUser().size == 2)
  }

  it must "return user with correct username" in {
      val user = dao.getUserWithUsername("test1")
      assert(user.isDefined)
      assert(user.get.id.isDefined)
      assert(user.get.userName == "test1")
  }

  it must "be able to delete user with particular username" in {
    assert(dao.hasUserWithUsername("test1"))
    assert(dao.removeUser(1).isSuccess)
  }

  private def buildUser(id : Int, userName : String = "") : DelphiUser = {
    val userType = if(id == 1) DelphiUserType.Admin else DelphiUserType.User
    val name = if(userName == "") "user" + id else userName
    DelphiUser(Some(id), name , Some(hashString("123456")), userType)
  }

  private def hashString(secret: String): String = {
    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString("")
  }
}
