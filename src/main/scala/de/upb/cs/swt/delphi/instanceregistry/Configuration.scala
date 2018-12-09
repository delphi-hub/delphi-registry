package de.upb.cs.swt.delphi.instanceregistry

import akka.util.Timeout

import scala.concurrent.duration.DurationInt

class Configuration( ) {
  //Where to host the http server
  val bindHost: String = "0.0.0.0"
  val bindPort: Int = 8087


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

  //Database configurations
  val useInMemoryDB = false
  val databaseHost = "jdbc:mysql://localhost/"
  val databaseName = ""
  val databaseDriver = "com.mysql.jdbc.Driver"
  val databaseUsername = ""
  val databasePassword = ""


  }


