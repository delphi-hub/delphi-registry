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
package de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.DelphiUserEnums.DelphiUserType
import spray.json.{DefaultJsonProtocol, DeserializationException, JsString, JsValue, JsonFormat}

trait UserJsonSupport extends SprayJsonSupport with DefaultJsonProtocol{

  implicit val userTypeFormat : JsonFormat[DelphiUserType] = new JsonFormat[DelphiUserType] {

    /**
      * Custom write method for serializing an UserType
      * @param userType
      * @return
      */
    def write(userType : DelphiUserType) = JsString(userType.toString)

    /**
      * Custom read method for deserialization of an UserType
      * @param value
      * @return
      */
    def read(value: JsValue) : DelphiUserType = value match {
      case JsString(s) => s match {
        case "User" => DelphiUserType.User
        case "Admin" => DelphiUserType.Admin
        case x => throw DeserializationException(s"Unexpected string value $x for delphi user type.")
      }
      case y => throw DeserializationException(s"Unexpected type $y during deserialization delphi user type.")
    }
  }

  implicit val authDelphiUserFormat: JsonFormat[DelphiUser] = jsonFormat4(DelphiUser)
}

final case class DelphiUser(id: Option[Long], userName: String, secret: Option[String], userType: DelphiUserType)

object DelphiUserEnums {

  //Type to use when working with component types
  type DelphiUserType = DelphiUserType.Value

  /**
    * userType enumeration defining the valid types of delphi users
    */
  object DelphiUserType extends Enumeration {
    val User  : Value = Value("User")
    val Admin : Value = Value("Admin")
  }
}
