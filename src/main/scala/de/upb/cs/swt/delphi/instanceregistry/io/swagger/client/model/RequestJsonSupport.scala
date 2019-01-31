package de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat}

trait RequestJsonSupport extends SprayJsonSupport with DefaultJsonProtocol{

  implicit val DelphiUserFormat = new JsonFormat[delphiUserEnums.CommandType] {

    def write(compType: delphiUserEnums.CommandType) = JsString(compType.toString)

    def read(value: JsValue): delphiUserEnums.CommandType = value match {
      case JsString(s) => s match {
        case "userName" => delphiUserEnums.CommandType.userName
        case "secret" => delphiUserEnums.CommandType.secret
        case "userType" => delphiUserEnums.CommandType.userType
        case x => throw new RuntimeException(s"Unexpected string value $x.")
      }
      case y => throw new RuntimeException(s"Unexpected type $y while deserializing")
    }
  }

  implicit val AuthDelphiUserFormat: JsonFormat[DelphiUser] = jsonFormat3(DelphiUser)
}

final case class DelphiUser(userName: String, secret: String, userType: String)

object delphiUserEnums {

  type CommandType = CommandType.Value

  object CommandType extends Enumeration {
    val userName: Value = Value("userName")
    val secret: Value = Value("secret")
    val userType: Value = Value("userType")
  }

}
