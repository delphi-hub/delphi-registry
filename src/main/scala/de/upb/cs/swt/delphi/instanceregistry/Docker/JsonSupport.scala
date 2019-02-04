package de.upb.cs.swt.delphi.instanceregistry.Docker

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat}


trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

  implicit val ContainerStatusFormat = new JsonFormat[ContainerStatusEnums.CommandType] {

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

  implicit val ContainerResponseFormat = new JsonFormat[ContainerResponseEnums.CommandType] {

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

  implicit val ContainerConfigFormat = new JsonFormat[ContainerConfigEnums.CommandType] {

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

  implicit val NetworksFormat = new JsonFormat[NetworksEnums.CommandType] {

    def write(compType: NetworksEnums.CommandType) = JsString(compType.toString)

    def read(value: JsValue): NetworksEnums.CommandType = value match {
      case JsString(s) => s match {
        case "IPAddress" => NetworksEnums.CommandType.IPAddress
        case x => throw new RuntimeException(s"Unexpected string value $x.")
      }
      case y => throw new RuntimeException(s"Unexpected type $y while deserializing")
    }
  }
  implicit val StatusFormat = jsonFormat6(ContainerStatus)
  implicit val ResponseFormat = jsonFormat2(CreateContainerResponse)
  implicit val ExposedPortConfigFormat = jsonFormat0(EmptyExposedPortConfig)
  implicit val EndpointsConfigFormat = jsonFormat0(EmptyEndpointConfig)
  implicit val NetworkConfigFormat = jsonFormat1(NetworkConfig)
  implicit val ConfigFormat = jsonFormat7(ContainerConfig)
  implicit val NetworkFormat = jsonFormat1(Networks)
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
    val EnvironmentVariables = Value("Env")
  }

}
object NetworksEnums {
  type CommandType = CommandType.Value

  object CommandType extends Enumeration {
    val IPAddress: Value = Value("IPAddress")

  }

}