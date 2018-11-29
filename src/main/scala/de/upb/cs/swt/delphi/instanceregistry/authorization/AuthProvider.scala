package de.upb.cs.swt.delphi.instanceregistry.authorization

import akka.actor.ActorSystem
import akka.http.scaladsl.server.directives.Credentials
import pdi.jwt.{Jwt, JwtAlgorithm}
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Registry}
import spray.json._

import scala.util.{Failure, Success, Try}

object AuthProvider extends AppLogging {

  implicit val system : ActorSystem = Registry.system

  def authenticateOAuth(credentials: Credentials) : Option[AccessToken] = {
    credentials match {
      case _ @ Credentials.Provided(tokenString) =>
        log.info(s"Validation authorization for token $tokenString")

        Jwt.decodeRawAll(tokenString, Registry.configuration.jwtSecretKey, Seq(JwtAlgorithm.HS256)) match {
          case Success((header, payload, _)) =>
            log.info(s"Token valid, headers are: $header")

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

  private def parsePayload(jwtPayload: String) : Try[AccessToken] = {
    Try[AccessToken] {
      val token = jwtPayload.parseJson.asJsObject
      val scopeRaw = token.fields("scope").asInstanceOf[JsArray].elements.map(jsScope => jsScope.toString)
      val userIdRaw = token.fields("user_id").asInstanceOf[JsString].toString
      val userTypeRaw = token.fields("user_type").asInstanceOf[JsString].toString
      val expiresAtRaw = token.fields("exp").asInstanceOf[JsNumber].value.toLongExact
      val notBeforeRaw = token.fields("nbf").asInstanceOf[JsNumber].value.toLongExact
      val issuedAtRaw = token.fields("iat").asInstanceOf[JsNumber].value.toLongExact

      AccessToken(
        userId = userIdRaw,
        userType = userTypeRaw,
        expiresAt = expiresAtRaw,
        issuedAt = issuedAtRaw,
        notBefore = notBeforeRaw,
        scope = List() ++ scopeRaw
      )
    }
  }
}
