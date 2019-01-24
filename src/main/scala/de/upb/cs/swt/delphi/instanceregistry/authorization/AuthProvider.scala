package de.upb.cs.swt.delphi.instanceregistry.authorization

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

import akka.actor.ActorSystem
import akka.http.scaladsl.model.DateTime
import akka.http.scaladsl.server.directives.Credentials
import de.upb.cs.swt.delphi.instanceregistry.authorization.AccessTokenEnums.UserType
import de.upb.cs.swt.delphi.instanceregistry.daos.{AuthDAO, DatabaseAuthDAO}
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Registry}
import spray.json._

import scala.util.{Failure, Success, Try}

object AuthProvider extends AppLogging {

  implicit val system : ActorSystem = Registry.system
  val authDAO : AuthDAO = new DatabaseAuthDAO(Registry.configuration)

  def authenticateBasicJWT(credentials: Credentials) : Option[String] = {
    credentials match {
      case p @ Credentials.Provided(userName)  => {
        if (userSecret(userName).isEmpty) {
          None
        } else if(p.verify(userSecret(userName), hashString)){
          Some(userName)
        } else {
          None
        }
      }
      case _ => None
    }
  }

  def hashString: String => String = { secret: String =>
    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString("")
  }

  private def userSecret(userName: String): String ={
    val user = authDAO.getUserWithUsername(userName)
    if(user.isDefined){
      user.get.secret
    } else {
      ""
    }
  }

  def generateJwt(useGenericName: String): String = {
    val validFor: Long = 1
    val user = authDAO.getUserWithUsername(useGenericName)

    val claim = JwtClaim()
      .issuedNow
      .expiresIn(validFor * 60)
      .startsNow
      . + ("user_id", user.get.userName)
      . + ("user_type", user.get.userType)

    val secretKey = Registry.configuration.jwtSecretKey
    Jwt.encode(claim, secretKey, JwtAlgorithm.HS256)
  }

  def authenticateOAuth(credentials: Credentials) : Option[AccessToken] = {
    credentials match {
      case _ @ Credentials.Provided(tokenString) =>
        log.debug(s"Validation authorization for token $tokenString")
        Jwt.decodeRawAll(tokenString, Registry.configuration.jwtSecretKey, Seq(JwtAlgorithm.HS256)) match {
          case Success((_, payload, _)) =>
            parsePayload(payload) match {
              case Success(token) =>
                log.info(s"Successfully parsed token to $token")
                Some(token)
              case Failure(ex) =>
                log.error(ex, s"Failed to parse token with message ${ex.getMessage}")
                None
            }
          case Failure(ex) =>
            log.warning(s"Failed to validate jwt token with message ${ex.getMessage}")
            None
        }
      case _ =>
        log.warning("Authorization not possible, no credentials provided.")
        None
    }
  }

  def authenticateOAuthRequire(credentials: Credentials, userType: UserType = UserType.Admin) : Option[AccessToken] = {
    authenticateOAuth(credentials) match {
      case Some(token) =>
        if(canAccess(token.userType, userType)){
          Some(token)
        } else {
          log.warning(s"Rejecting token because required user type $userType is not present")
          None
        }
      case _ => None
    }
  }

  private def parsePayload(jwtPayload: String) : Try[AccessToken] = {
    Try[AccessToken] {
      val json = jwtPayload.parseJson.asJsObject

      val expiresAtRaw = json.fields("exp").asInstanceOf[JsNumber].value.toLongExact
      val notBeforeRaw = json.fields("nbf").asInstanceOf[JsNumber].value.toLongExact
      val issuedAtRaw = json.fields("iat").asInstanceOf[JsNumber].value.toLongExact

      val userTypeRaw = json.fields("user_type").asInstanceOf[JsString].value
      val userIdRaw = json.fields("user_id").asInstanceOf[JsString].value

      val userTypeEnum: UserType = UserType.withName(userTypeRaw) //Will throw exception if not valid, which is fine inside try
      val expiresAtDate: DateTime = DateTime(expiresAtRaw * 1000L) //Convert to milliseconds for akka DateTime
      val notBeforeDate: DateTime = DateTime(notBeforeRaw * 1000L)
      val issuedAtDate: DateTime = DateTime(issuedAtRaw * 1000L)

      AccessToken(
        userId = userIdRaw,
        userType = userTypeEnum,
        expiresAt = expiresAtDate,
        issuedAt = issuedAtDate,
        notBefore = notBeforeDate
      )
    }
  }

  private def canAccess(tokenType: UserType, requiredType: UserType) = {
    if(tokenType == UserType.Admin){
      true
    } else {
      tokenType == requiredType
    }
  }
}
