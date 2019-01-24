package de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat}

trait RequestJsonSupport extends SprayJsonSupport with DefaultJsonProtocol{

  implicit val AuthenticateFormat = new JsonFormat[AuthenticateEnums.CommandType] {

    def write(compType: AuthenticateEnums.CommandType) = JsString(compType.toString)

    def read(value: JsValue): AuthenticateEnums.CommandType = value match {
      case JsString(s) => s match {
        case "userName" => AuthenticateEnums.CommandType.userName
        case "secret" => AuthenticateEnums.CommandType.secret
        case "userType" => AuthenticateEnums.CommandType.userType
        case x => throw new RuntimeException(s"Unexpected string value $x.")
      }
      case y => throw new RuntimeException(s"Unexpected type $y while deserializing")
    }
  }

  implicit val UserAuthenticate: JsonFormat[Authenticate] = jsonFormat3(Authenticate)
}

final case class Authenticate(userName: String, secret: String, userType: String)

object AuthenticateEnums {

  type CommandType = CommandType.Value

  object CommandType extends Enumeration {
    val userName: Value = Value("userName")
    val secret: Value = Value("secret")
    val userType: Value = Value("userType")
  }

}
