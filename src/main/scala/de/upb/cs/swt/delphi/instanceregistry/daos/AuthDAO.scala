package de.upb.cs.swt.delphi.instanceregistry.daos

import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.{DelphiUser}

trait AuthDAO {

  /**
    * Initializes the DAO
    */
  def initialize() : Unit

  /**
    * Gets the user with the specified username from the DAO
    * @param userName
    * @return
    */
  def getUserWithUsername(userName: String) : Option[DelphiUser]

  /**
    * Checks whether the DAO holds an user with the specified username.
    * @param userName
    * @return
    */
  def hasUserWithUsername(userName: String) : Boolean

  /**
    * Shuts the DAO down
    */
  def shutdown(): Unit
}
