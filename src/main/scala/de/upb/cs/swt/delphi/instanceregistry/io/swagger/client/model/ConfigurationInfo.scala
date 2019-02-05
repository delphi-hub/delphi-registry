package de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, JsonFormat}

trait ConfigurationInfoJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val ConfigurationInfoFormat: JsonFormat[ConfigurationInfo] = jsonFormat2(ConfigurationInfo)
}

final case class ConfigurationInfo (DockerHttpUri: String, TraefikProxyUri: String)
