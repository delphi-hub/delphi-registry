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
package de.upb.cs.swt.delphi.instanceregistry

import akka.util.Timeout

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class Configuration( ) {
  //Where to host the http server
  val bindHost: String = "0.0.0.0"
  val bindPort: Int = 8087

  //Traefik data
  val traefikBaseHost: String = "delphi.de"
  val traefikDockerNetwork: String = "delphi"
  val traefikUri: String = "http://172.17.0.1:80"


  val recoveryFileName : String = "dump.temp"

  //Default ports for the Delphi components
  val defaultCrawlerPort: Int = 8882
  val defaultWebApiPort: Int = 8080
  val defaultWepAppPort: Int  = 8085

  //Names of the docker images for the Delphi components
  val crawlerDockerImageName: String = "delphi-crawler:1.0.0-SNAPSHOT"
  val webApiDockerImageName: String = "delphi-webapi:1.0.0-SNAPSHOT"
  val webAppDockerImageName: String = "delphi-webapp:1.0.0-SNAPSHOT"

  //Where the initial ElasticSearch instance is located at
  val defaultElasticSearchInstanceHost: String = "elasticsearch://172.17.0.1"
  val defaultElasticSearchInstancePort: Int = 9200

  //Where this registry can be contacted at inside the LAN
  val uriInLocalNetwork: String = "http://172.17.0.1:8087"

  val maxLabelLength: Int = 50

  val dockerOperationTimeout: Timeout = Timeout(20 seconds)
  val dockerUri: String = sys.env.getOrElse("DELPHI_DOCKER_HOST", "http://localhost:9095")

  val jwtSecretKey: String = sys.env.getOrElse("JWT_SECRET", "changeme")

  //Database configurations
  val useInMemoryInstanceDB = true
  val instanceDatabaseHost = "jdbc:mysql://localhost/"
  val instanceDatabaseName = ""
  val instanceDatabaseDriver = "com.mysql.jdbc.Driver"
  val instanceDatabaseUsername = ""
  val instanceDatabasePassword = ""

  //Auth database configuration
  val useInMemoryAuthDB = true
  val authDatabaseHost = "jdbc:mysql://localhost/"
  val authDatabaseName = ""
  val authDatabaseDriver = "com.mysql.jdbc.Driver"
  val authDatabaseUsername = ""
  val authDatabasePassword = ""

  //Authentication valid for the time
  val authenticationValidFor = 30 //minutes

  //Refresh token is valid for the time
  val refreshTokenValidFor = 1440 //minutes

  //Request Limiter
  val maxTotalNoRequest: Int = 2000
  val maxIndividualIpReq: Int = 200
  val ipLogRefreshRate: FiniteDuration = 2.minutes

}


