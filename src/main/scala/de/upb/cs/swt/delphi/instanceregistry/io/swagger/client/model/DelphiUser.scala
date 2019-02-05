package de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, JsonFormat}

trait UserJsonSupport extends SprayJsonSupport with DefaultJsonProtocol{
  implicit val AuthDelphiUserFormat: JsonFormat[DelphiUser] = jsonFormat4(DelphiUser)
}

final case class DelphiUser(id: Option[Long], userName: String, secret: String, userType: String)

