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
package de.upb.cs.swt.delphi.instanceregistry.authorization

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import akka.actor.ActorSystem
import akka.http.scaladsl.model.DateTime
import akka.http.scaladsl.server.directives.Credentials
import de.upb.cs.swt.delphi.instanceregistry.authorization.AccessTokenEnums.UserType
import de.upb.cs.swt.delphi.instanceregistry.daos.AuthDAO
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.{DelphiUser, UserToken}
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Registry}
import spray.json._

import scala.util.{Failure, Success, Try}

class AuthProvider(authDAO: AuthDAO) extends AppLogging {

  implicit val system : ActorSystem = Registry.system

  def authenticateBasicJWT(credentials: Credentials) : Option[String] = {
    credentials match {
      case p @ Credentials.Provided(userName)  =>
        if (getSecretForUser(userName).isEmpty) {
          None
        } else if(p.verify(getSecretForUser(userName).get, hashString)){
          Some(userName)
        } else {
          None
        }
      case _ => None
    }
  }

  def hashString: String => String = { secret: String =>
    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString("")
  }

  private def getSecretForUser(userName: String): Option[String] ={
    val user = authDAO.getUserWithUsername(userName)
    if(user.isDefined){
      Some(user.get.secret.get)
    } else {
      None
    }
  }

  def isValidDelphiToken(token: String): Boolean ={
    Jwt.decodeRawAll(token, Registry.configuration.jwtSecretKey, Seq(JwtAlgorithm.HS256)) match {
      case Success((_, payload, _)) =>
        parseDelphiTokenPayload(payload) match {
          case Success(_) =>
            log.info(s"Successfully parsed Delphi Authorization token")
            true
          case Failure(ex) =>
            log.error(ex, s"Failed to parse Delphi Authorization with message ${ex.getMessage}")
            false
        }
      case Failure(ex) =>
        log.warning(s"Failed to validate jwt token with message ${ex.getMessage}")
        false
    }
  }

  def generateJwt(userName: String): UserToken = {
    val user = authDAO.getUserWithUsername(userName)
    getUserToken(user.get)
  }

  def generateJwtByUserId(userId: Long): UserToken ={
    val user = authDAO.getUserWithId(userId)
    getUserToken(user.get)
  }

  def getUserToken(user: DelphiUser): UserToken ={
    val validFor: Long = Registry.configuration.authenticationValidFor
    val refreshTokenValidFor: Long = Registry.configuration.refreshTokenValidFor
    val claim = JwtClaim()
      .issuedNow
      .expiresIn(validFor * 60)
      .startsNow
      . + ("user_id", user.id.get.toString)
      . + ("user_type", user.userType)

    val refreshClaim = JwtClaim()
      .issuedNow
      .expiresIn(refreshTokenValidFor * 60)
      .startsNow
      . + ("user_id", user.id.get)

    val secretKey = Registry.configuration.jwtSecretKey
    val token = Jwt.encode(claim, secretKey, JwtAlgorithm.HS256)
    val refreshToken = Jwt.encode(refreshClaim, secretKey, JwtAlgorithm.HS256)

    UserToken(token, refreshToken)
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

  def checkRefreshToken(credentials: Credentials) : Option[Number] = {
    credentials match {
      case _ @ Credentials.Provided(tokenString) =>
        log.debug(s"Validation authorization for token $tokenString")
        Jwt.decodeRawAll(tokenString, Registry.configuration.jwtSecretKey, Seq(JwtAlgorithm.HS256)) match {
          case Success((_, payload, _)) =>
            parseRefreshTokenPayload(payload) match {
              case Success(userId) =>
                log.info(s"Successfully parsed token")
                Some(userId)
              case Failure(ex) =>
                log.error(ex, s"mm Failed to parse token with message ${ex.getMessage}")
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

  private def parseDelphiTokenPayload(jwtPayload: String) : Try[(String, String)] = {
    Try[(String, String)] {
      val json = jwtPayload.parseJson.asJsObject
      val userId = json.fields("user_id").asInstanceOf[JsString].value
      val userType = json.fields("user_type").asInstanceOf[JsString].value

      (userId, userType)
    }
  }

  private def parseRefreshTokenPayload(jwtPayload: String): Try[Number]  = {
    Try[Number] {
      val json = jwtPayload.parseJson.asJsObject
      val userId = json.fields("user_id").asInstanceOf[JsNumber].value
      userId
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
