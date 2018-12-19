package de.upb.cs.swt.delphi.instanceregistry.authorization

import akka.actor.ActorSystem
import akka.http.scaladsl.model.DateTime
import akka.http.scaladsl.server.directives.Credentials
import de.upb.cs.swt.delphi.instanceregistry.authorization.AccessTokenEnums.UserType
import pdi.jwt.{Jwt, JwtAlgorithm}
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Registry}
import spray.json._

import scala.util.{Failure, Success, Try}

object AuthProvider extends AppLogging {

  implicit val system : ActorSystem = Registry.system

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
