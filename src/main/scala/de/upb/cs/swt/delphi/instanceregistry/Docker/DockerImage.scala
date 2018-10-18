package de.upb.cs.swt.delphi.instanceregistry.Docker

import de.upb.cs.swt.delphi.instanceregistry.AppLogging

class DockerImage extends AppLogging {
  def getImageName(ComponentType: Any): String = ComponentType match {

    case "Crawler" => "24santoshr/delphi_crawler"
    case "WebApi" => "24santoshr/webapi"
    case "WebApp" => "24santoshr/webapp"
    case "DelphiManagement" => "24santoshr/delphi-registry"
    case _ => "Unspecified Component type"
  }
}



