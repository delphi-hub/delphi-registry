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



