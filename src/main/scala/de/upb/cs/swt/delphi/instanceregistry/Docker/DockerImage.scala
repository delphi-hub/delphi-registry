package de.upb.cs.swt.delphi.instanceregistry.Docker

import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Registry}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums

object DockerImage extends AppLogging {

  def getImageName(ComponentType: InstanceEnums.ComponentType): String = ComponentType match {

    case InstanceEnums.ComponentType.Crawler => Registry.configuration.crawlerDockerImageName
    case InstanceEnums.ComponentType.WebApi => Registry.configuration.webApiDockerImageName
    case InstanceEnums.ComponentType.WebApp => Registry.configuration.webAppDockerImageName
    case _ => "Invalid Component type"
  }
}



