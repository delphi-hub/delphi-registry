# Delphi Registry
The management and administration component for the Delphi platform. The registry provides a REST interface for querying the current state of your Delphi setup as well as for changing it.

We are currently in pre-alpha state! There is no release and the code in
this repository is purely experimental!

|branch | status |
| :---: | :---: | 
| master | <img src="https://travis-ci.org/delphi-hub/delphi-registry.svg?branch=master"> |
| develop | <img src="https://travis-ci.org/delphi-hub/delphi-registry.svg?branch=develop"> |

## What is the registry component?
The Delphi registry is a server that provides access to all information and operations needed to set up, run and manage the Delphi system. By default, the REST interface is exposed at *0.0.0.0:8087*, and contains endpoints for:
* Retrieving a list of all instances of a certain type (Crawler, WebApi, WebApp, ElasticSearch)
* Retrieving the whole network graph (all instances and links between instances)
* Deploying new instances of a certain type to a docker host
* Starting / Stopping / Pausing / Resuming / Deleting instances deployed on the docker host
* Re-Assigning dependencies to instances (e.g. assigning a certain ElasticSearch instance to a Crawler)

## Requirements
In order to compile or execute the instance registry, you must have the latest version of the *Scala Build Tool* (SBT) installed. You can get it [here](https://www.scala-sbt.org/).
The Delphi registry requires a docker host to deploy containers to. The following images must be registered at the docker registry:
* The Delphi Crawler ( ```delphi-crawler:1.0.0-SNAPSHOT``` )
* The Delphi WebApi ( ```delphi-webapi:1.0.0-SNAPSHOT``` )
* The Delphi WebApp ( ```delphi-webapp:1.0.0-SNAPSHOT``` )

To obtain these images, checkout the respective repositories ([here](https://github.com/delphi-hub/delphi-crawler), [here](https://github.com/delphi-hub/delphi-webapi) and [here](https://github.com/delphi-hub/delphi-webapp)) and execute the command 

```
sbt docker:publishLocal
```
inside their root directory. This will build the docker images and register them directly at the local docker registry.
The registry requires an initial instance of ElasticSearch to be running.

## Adapt the configuration file
Before you can start the application, you have to make sure your configuration file contains valid data. The file can be found at *src/main/scala/de/upb/cs/swt/delphi/instanceregistry/Configuration.scala*, and most of its attributes are string or integer values. The following table describes the attributes in more detail.

|Attribute | Type | Default Value |Explanation |
| :---: | :---: | :---: | :--- |
|```bindHost``` | ```String``` | ```"0.0.0.0"``` | Host address that the registry server should be bound to |
|```bindPort``` | ```Int``` | ```8087``` | Port that the registry server should be reachable at |
|```defaultCrawlerPort``` | ```Int``` | ```8882``` | Port that Delphi Crawlers are reachable at. This may only be adapted if you manually changed the default port of crawlers before registering the respective image. |
|```defaultWebApiPort``` | ```Int``` | ```8080``` | Port that Delphi WebAPIs are reachable at. This may only be adapted if you manually changed the default port of WebAPIs before registering the respective image. |
|```defaultWebAppPort``` | ```Int``` | ```8085``` | Port that Delphi WebApps are reachable at. This may only be adapted if you manually changed the default port of WebApps before registering the respective image. |
|```crawlerDockerImageName``` | ```String``` | ```"delphi-crawler:1.0.0-SNAPSHOT"``` | Name of the Docker image for Delphi Crawlers. May only be changed if you manually specified a different name when creating the image.|
|```webApiDockerImageName``` | ```String``` | ```"delphi-webapi:1.0.0-SNAPSHOT"``` | Name of the Docker image for Delphi WebAPIs. May only be changed if you manually specified a different name when creating the image.|
|```webAppDockerImageName``` | ```String``` | ```"delphi-webapp:1.0.0-SNAPSHOT"``` | Name of the Docker image for Delphi WebApps. May only be changed if you manually specified a different name when creating the image.|
|```defaultElasticSearchInstanceHost``` | ```String``` | ```"elasticsearch://172.17.0.1"``` | Host that the default ElasticSearch instance is located at.|
|```defaultElasticSearchInstancePort``` | ```Int``` | ```9200``` | Port that the default ElasticSearch instance is reachable at.|
|```uriInLocalNetwork``` | ```String``` | ```"http://172.17.0.1:8087"``` | URI that the registry is reachable at for all docker containers. In most of the use-cases this is going to be the gateway of the default docker bridge.|
|```maxLabelLength``` | ```Int``` | ```50``` | Maximum number of characters for instance labels. Longer labels will be rejected.|
|```dockerOperationTimeout``` | ```Timeout``` | ```Timeout(20 seconds)``` | Default timeout for docker operations. If any of the async Docker operations (deploy, stop, pause, ..) takes longer than this, it will be aborted.|
|```useInMemoryDB``` | ```Boolean``` | ```false``` | If set to true, all instance data will be kept in memory instead of using a MySQL database.|
|```databaseHost``` | ```String``` | ```"jdbc:mysql://localhost/"``` | Host that the MySQL database is reachable at (only necessary if *useInMemoryDB* is false).|
|```databaseName``` | ```String``` | ```""``` | Name of the MySQL database to use (only necessary if *useInMemoryDB* is false).|
|```databaseDriver``` | ```String``` | ```"com.mysql.jdbc.Driver"``` | Driver to use for the MySQL connection (only necessary if *useInMemoryDB* is false).|
|```databaseUsername``` | ```String``` | ```""``` | Username to use for the MySQL connection (only necessary if *useInMemoryDB* is false).|
|```databasePassword``` | ```String``` | ```""``` | Password to use for the MySQL connection (only necessary if *useInMemoryDB* is false).|

By default, Docker is expected to be reachable at *http://localhost:9095*, but you can override this setting by specifying the docker host URI in the environment variable *DOCKER_HOST*.
To change the port of your http docker API to 9095, execute
```
edit /lib/systemd/system/docker.service
ExecStart=/usr/bin/dockerd -H fd:// -H=tcp://0.0.0.0:9095
systemctl daemon-reload
sudo service docker restart
```





## Run the application
There are two ways of running the registry application. You can either run the application directly, or build a docker image defined by the *build.sbt* file, and run a container based on this image. Either way, you have to set the correct configuration values before starting the application (see section **Adapt the configuration file** above for more information). We are currently working on a setup script that will prepare all images that need to be present on your docker host. Until its finished, you have to register the images manually, as described in the **Requirements** section.
### Run the registry directly
If you want to execute the registry directly on your local machine, simply go to the root folder of the repository and execute ```sbt run```. The application will stream all logging output to the terminal. You can terminate any time by pressing *RETURN*.
### Run the registry in Docker
For building a docker image containing the registry, go to the root folder of the repository and execute ```sbt docker:publishLocal```. This will build the application, create a docker image named ```delphi-registry:1.0.0-SNAPSHOT```, and register the image at your local docker registry.


## Contributing

Contributions are *very* welcome!

Before contributing, please read our [Code of Conduct](CODE_OF_CONDUCT.md).

We use Pull Requests to collect contributions. Especially look out for "help wanted" issues
[![GitHub issues by-label](https://img.shields.io/github/issues/delphi-hub/delphi-registry/help%20wanted.svg)](https://github.com/delphi-hub/delphi-registry/issues?q=is%3Aopen+is%3Aissue+label%3A%22help+wanted%22),
but feel free to work on other issues as well.
You can ask for clarification in the issues directly, or use our Gitter
chat for a more interactive experience.

[![GitHub issues](https://img.shields.io/github/issues/delphi-hub/delphi-registry.svg)](https://github.com/delphi-hub/delphi-registry/issues)


## License

The Delphi registry is open source and available under Apache 2 License.

[![GitHub license](https://img.shields.io/github/license/delphi-hub/delphi-registry.svg)](https://github.com/delphi-hub/delphi-registry/blob/master/LICENSE)
