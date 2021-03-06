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
package de.upb.cs.swt.delphi.instanceregistry.Docker

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import de.upb.cs.swt.delphi.instanceregistry.Docker.ContainerStatusEnums.CommandType
import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat}


trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

  implicit val containerStatusFormat: JsonFormat[CommandType] = new JsonFormat[ContainerStatusEnums.CommandType] {

    def write(compType: ContainerStatusEnums.CommandType) = JsString(compType.toString)

    def read(value: JsValue): ContainerStatusEnums.CommandType = value match {
      case JsString(s) => s match {
        case "Command" => ContainerStatusEnums.CommandType.Command
        case "Id" => ContainerStatusEnums.CommandType.Id
        case "Image" => ContainerStatusEnums.CommandType.Image
        case "Names" => ContainerStatusEnums.CommandType.Names
        case "Labels" => ContainerStatusEnums.CommandType.Labels
        case "Status" => ContainerStatusEnums.CommandType.Status

        case x => throw new RuntimeException(s"Unexpected Container Status value $x.")
      }
      case y => throw new RuntimeException(s"Unexpected type $y while deserializing")
    }
  }

  implicit val containerResponseFormat: JsonFormat[ContainerResponseEnums.CommandType] = new JsonFormat[ContainerResponseEnums.CommandType] {

    def write(compType: ContainerResponseEnums.CommandType) = JsString(compType.toString)

    def read(value: JsValue): ContainerResponseEnums.CommandType = value match {
      case JsString(s) => s match {
        case "Warnings" => ContainerResponseEnums.CommandType.Warnings
        case "Id" => ContainerResponseEnums.CommandType.Id
        case x => throw new RuntimeException(s"Unexpected Container Response value $x.")
      }
      case y => throw new RuntimeException(s"Unexpected type $y while deserializing")
    }
  }

  implicit val containerConfigFormat: JsonFormat[ContainerConfigEnums.CommandType] = new JsonFormat[ContainerConfigEnums.CommandType] {

    def write(compType: ContainerConfigEnums.CommandType) = JsString(compType.toString)

    def read(value: JsValue): ContainerConfigEnums.CommandType = value match {
      case JsString(s) => s match {
        case "Image" => ContainerConfigEnums.CommandType.Image
        case "Env" => ContainerConfigEnums.CommandType.EnvironmentVariables
        case "Cmd" => ContainerConfigEnums.CommandType.Command
        case "Entrypoint" => ContainerConfigEnums.CommandType.EntryPoint
        case x => throw new RuntimeException(s"Unexpected string value $x.")
      }
      case y => throw new RuntimeException(s"Unexpected type $y while deserializing")
    }
  }

  implicit val networksFormat: JsonFormat[NetworksEnums.CommandType] = new JsonFormat[NetworksEnums.CommandType] {

    def write(compType: NetworksEnums.CommandType) = JsString(compType.toString)

    def read(value: JsValue): NetworksEnums.CommandType = value match {
      case JsString(s) => s match {
        case "IPAddress" => NetworksEnums.CommandType.IPAddress
        case x => throw new RuntimeException(s"Unexpected string value $x.")
      }
      case y => throw new RuntimeException(s"Unexpected type $y while deserializing")
    }
  }
  implicit val statusFormat: JsonFormat[ContainerStatus] = jsonFormat6(ContainerStatus)
  implicit val exposedPortConfigFormat :JsonFormat[EmptyExposedPortConfig] = jsonFormat0(EmptyExposedPortConfig)
  implicit val endpointsConfigFormat :JsonFormat[EmptyEndpointConfig] = jsonFormat0(EmptyEndpointConfig)
  implicit val networkConfigFormat :JsonFormat[NetworkConfig] = jsonFormat1(NetworkConfig)
  implicit val networkFormat :JsonFormat[Networks] = jsonFormat1(Networks)

  //For some reason you cannot type below variables explicitly, it will result in syntax errors when marshalling..

  //noinspection TypeAnnotation
  implicit val responseFormat = jsonFormat2(CreateContainerResponse)
  //noinspection TypeAnnotation
  implicit val configFormat  = jsonFormat7(ContainerConfig)
}


object ContainerStatusEnums {

  type CommandType = CommandType.Value

  object CommandType extends Enumeration {
    val Command: Value = Value("Command")
    val Id: Value = Value("Id")
    val Image: Value = Value("Image")
    val Names: Value = Value("Names")
    val Ports: Value = Value("Ports")
    val Labels: Value = Value("Labels")
    val Status: Value = Value("Status")
  }

}

object ContainerResponseEnums {

  type CommandType = CommandType.Value

  object CommandType extends Enumeration {
    val Id: Value = Value("Id")
    val Warnings: Value = Value("Warnings")
  }

}

object ContainerConfigEnums {

  type CommandType = CommandType.Value

  object CommandType extends Enumeration {
    val Image: Value = Value("Image")
    val EntryPoint: Value = Value("Entrypoint")
    val Command: Value = Value("Cmd")
    val EnvironmentVariables: Value = Value("Env")
  }

}
object NetworksEnums {
  type CommandType = CommandType.Value

  object CommandType extends Enumeration {
    val IPAddress: Value = Value("IPAddress")

  }

}