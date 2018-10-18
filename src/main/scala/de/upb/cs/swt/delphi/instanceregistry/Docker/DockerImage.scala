package de.upb.cs.swt.delphi.instanceregistry.Docker

import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType

class DockerImage(componentType: ComponentType) {
  def getImageName(s: Any): DockerImage.ComponentType = s match {

    case "Crawler" => DockerImage.ComponentType.Crawler
    case "WebApi" => DockerImage.ComponentType.WebApi
    case "WebApp" => DockerImage.ComponentType.WebApp
    case "DelphiManagement" => DockerImage.ComponentType.DelphiManagement

  }
}

object DockerImage {
  type ComponentType = ComponentType.Value

  object ComponentType extends Enumeration {
    val Crawler: Value = Value("24santoshr/delphi_crawler")
    val WebApi: Value = Value("24santoshr/webapi")
    val WebApp: Value = Value("24santoshr/webapp")
    val DelphiManagement: Value = Value("24santoshr/delphi-registry")
  }

}

