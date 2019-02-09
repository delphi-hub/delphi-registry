package de.upb.cs.swt.delphi.instanceregistry.daos

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import de.upb.cs.swt.delphi.instanceregistry.Configuration
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.DelphiUser
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

class DynamicAuthDAOTest extends FlatSpec with Matchers with BeforeAndAfterEach{

  val dao : DynamicAuthDAO = new DynamicAuthDAO(new Configuration())

  "The Auth Dao" must "be able to add a user with different username" in {
    val username = dao.addUser(buildUser(id = 1, userName = "test1"))
    assert(username.isSuccess)
    assert(dao.hasUserWithUsername("test1"))
    assert(dao.addUser(buildUser(id = 2, userName = "test1")).isFailure)
  }

  it must "return user with correct username" in {

    val user = dao.getUserWithUsername("test1")
    assert(user.isDefined)
    assert(user.get.id.isDefined)
    assert(user.get.userName == "test1")
  }

  it must "be able to delete user with particular username" in {
    assert(dao.hasUserWithUsername("test1"))
    assert(dao.removeUser("test1").isSuccess)
  }

  private def buildUser(id : Int, userName : String = "") : DelphiUser = {
    val userType = if(id == 1) "Admin" else "User"
    val name = if(userName == "") "user"+id else userName
    DelphiUser(Some(id), name , hashString("123456"), userType)
  }

  private def hashString(secret: String): String = {
    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString("")
  }
}
