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

import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.DelphiUser

import scala.util.Try

trait AuthDAO {

  /**
    * Add user
    * @return
    */
  def addUser(delphiUser : DelphiUser) : Try[String]

  /**
    * Remove user with username
    * @param username
    * @return
    */
  def removeUser(username: String) : Try[Unit]

  /**
    * Initializes the DAO
    */
  def initialize() : Unit

  /**
    * Gets the user with the specified username from the DAO
    * @param userName Name of the user to retrieve
    * @return Retrieved User, or None, if name not present
    */
  def getUserWithUsername(userName: String) : Option[DelphiUser]

  /**
    * Checks whether the DAO holds an user with the specified username.
    * @param userName Name to check
    * @return True if name is present, false otherwise
    */
  def hasUserWithUsername(userName: String) : Boolean

  /**
    * Shuts the DAO down
    */
  def shutdown(): Unit
}
