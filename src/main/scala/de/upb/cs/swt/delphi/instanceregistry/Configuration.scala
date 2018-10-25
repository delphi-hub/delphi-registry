package de.upb.cs.swt.delphi.instanceregistry

class Configuration( ) {
  val bindHost: String = "0.0.0.0"
  val bindPort: Int = 8087
  val recoveryFileName : String = "dump.temp"

  val defaultCrawlerPort: Int = 8882
  val defaultWebApiPort: Int = 8080
  val defaultWepAppPort: Int  = 8085

  val crawlerDockerImageName: String = "delphi-crawler:1.0.0-SNAPSHOT"
  val webApiDockerImageName: String = "delphi-webapi:1.0.0-SNAPSHOT"
  val webAppDockerImageName: String = "delphi-webapp:1.0.0-SNAPSHOT"



}


