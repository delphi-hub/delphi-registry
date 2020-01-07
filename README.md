# Delphi Registry
The management and administration component for the Delphi platform. The registry provides a REST interface for querying the current state of your Delphi setup as well as for changing it.

We are currently in pre-alpha state! There is no release and the code in
this repository is purely experimental!

|branch | status | codacy | snyk |
| :---: | :---: | :---: | :---: |  
| master | <img src="https://travis-ci.org/delphi-hub/delphi-registry.svg?branch=master"> | [![Codacy Badge](https://api.codacy.com/project/badge/Grade/0ae70fb89bc4419c8a32b5e8583940e7)](https://www.codacy.com/manual/delphi-hub/delphi-registry?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=delphi-hub/delphi-registry&amp;utm_campaign=Badge_Grade) | [![Known Vulnerabilities](https://snyk.io/test/github/delphi-hub/delphi-registry/badge.svg?targetFile=build.sbt)](https://snyk.io/test/github/delphi-hub/delphi-registry?targetFile=build.sbt)
| develop | <img src="https://travis-ci.org/delphi-hub/delphi-registry.svg?branch=develop"> | [![Codacy Badge](https://api.codacy.com/project/badge/Grade/0ae70fb89bc4419c8a32b5e8583940e7?branch=develop)](https://www.codacy.com/manual/delphi-hub/delphi-registry?branch=develop&utm_source=github.com&amp;utm_medium=referral&amp;utm_content=delphi-hub/delphi-registry&amp;utm_campaign=Badge_Grade) |  [![Known Vulnerabilities](https://snyk.io/test/github/delphi-hub/delphi-registry/develop/badge.svg?targetFile=build.sbt)](https://snyk.io/test/github/delphi-hub/delphi-registry/develop?targetFile=build.sbt)

## What is the registry component?
The Delphi registry is a server that provides access to all information and operations needed to set up, run and manage the Delphi system. By default, the REST interface is exposed at *0.0.0.0:8087*, and contains endpoints for:

* Retrieving a list of all instances of a certain type (Crawler, WebApi, WebApp, ElasticSearch)
* Retrieving the whole network graph (all instances and links between instances)
* Deploying new instances of a certain type to a docker host
* Starting / Stopping / Pausing / Resuming / Deleting instances deployed on the docker host
* Re-Assigning dependencies to instances (e.g. assigning a certain ElasticSearch instance to a Crawler)

# Easy Installation Guide
* [Quick Setup (Linux)](#quick-setup-linux)
   * [Docker Host Setup](#docker-host-setup)
   * [Registry Host Setup](#registry-host-setup)
* [Requirements](#requirements)
   * [Windows](#windows)
   * [Linux](#linux)
   * [Configuration of Traefik](#configuration-of-traefik)
 * [Adapt the configuration file](#adapt-the-configuration-file)
 * [Docker Configuration](#docker-configuration)
   * [Docker configuration for Linux](#docker-configuration-for-linux)
   * [Docker configuration for OSX](#docker-configuration-for-osx)
 * [Running Registry application](#running-registry-application)
   * [Run the registry directly](#run-the-registry-directly)
   * [Run the registry in Docker](#run-the-registry-in-docker)
 * [Authorization](#authorization)

# Quick Setup (Linux)

Potentially there two different machines involved in the registry setup, the Docker host machine (*Docker Host*) and the machine the registry is hosted at (*Registry Host*). However, you can also use the same machine for hosting both applications.

## Docker Host Setup
On the *Docker Host*, execute the following steps:

1) Clone this repository to your machine
2) Execute the setup script ```Setup/Delphi_install.sh```
3) Deploy [ElasticSearch](https://www.elastic.co/de/products/elasticsearch) by executing ```docker run -p 9200:9200 -p 9300:9300 -e "discovery.type=single-node" docker.elastic.co/elasticsearch/elasticsearch:6.6.0```
4) Expose your Docker HTTP api on port 9095
    * Execute ```sudo nano /lib/systemd/system/docker.service```
    * Change the line that starts with ```ExecStart``` to look like this: ```ExecStart=/usr/bin/dockerd -H fd:// -H=tcp://0.0.0.0:9095```
    * Save your changes and execute ```systemctl daemon-reload``` and ```sudo service docker restart```
5) Note down the IP address of your machine in the LAN ( execute ```ifconfig``` )

## Registry Host Setup
On the *Registry Host*, execute the following steps:

1) Clone this repository to your local machine
2) Note down the IP address of your machine in the LAN ( execute ```ifconfig``` )
3) Edit the configuration file located at ```src/main/scala/de/upb/cs/swt/delphi/instanceregistry/Configuration.scala```
    * Set ```traefikUri``` to ```http://<DOCKER-HOST-IP-HERE>:80```
    * Set ```defaultElasticSearchInstanceHost``` to ```http://<DOCKER-HOST-IP-HERE>```
    * Set ```uriInLocalNetwork``` to ```http://<REGISTRY-HOST-IP-HERE>:8087```
    * Set ```dockerUri``` to ```http://<DOCKER-HOST-IP-HERE>:9095```
    * Set ```jwtSecretKey``` to a secret password not known to anybody else
4) Save your changes. Navigate to the root folder of the repository and execute the application by calling ```sbt run```

The setup of the registry is now complete. You should now be able to connect to the registry component at ```http://<REGISTRY-HOST-IP-HERE>:8087```. You can verify your setup by calling ```curl <REGISTRY-HOST-IP-HERE>:8087/configuration```, which should lead to a ```401 Unauthorized``` response. To find out more about how to authorize yourself, please have look at the **Authorization** section of this document.

## Requirements
In order to compile or execute the instance registry, you must have the latest version of the *Scala Build Tool* (SBT) installed. You can get it [here](https://www.scala-sbt.org/).

The Delphi registry requires a docker host to deploy containers. The following images must be registered at the docker registry:
* The Delphi Crawler ( ```delphi-crawler:1.0.0-SNAPSHOT``` )
* The Delphi WebApi ( ```delphi-webapi:1.0.0-SNAPSHOT``` )
* The Delphi WebApp ( ```delphi-webapp:1.0.0-SNAPSHOT``` )
### Windows  
For Windows users, to obtain these images, checkout the respective repositories ([here](https://github.com/delphi-hub/delphi-crawler), [here](https://github.com/delphi-hub/delphi-webapi) and [here](https://github.com/delphi-hub/delphi-webapp)) and execute the command 

```
sbt docker:publishLocal
```
inside their root directory. This will build the docker images and register them directly at the local docker registry. <br /> 
### Linux  
For Linux users, checkout Delphi Registry repository and execute the command

```
sudo bash ./Delphi_install.sh
``` 
inside the ```/Setup``` directory. This installation script will create the required repositories, build the docker images, and register them directly at the local docker registry.
The registry requires an initial instance of ElasticSearch to be running.
### Configuration of Traefik  
To allow access to Delphi components deployed via Docker, the registry supports the reverse-proxy [Traefik](https://traefik.io/). While it is running, it will automatically detected containers deployed by the registry, and provide access to them using the host specified in each instances' ```Host``` attribute.
Windows users can install Traefik (using Docker) based on [this tutorial](https://docs.traefik.io/#the-traefik-quickstart-using-docker). For Linux users, Traefik will be installed and started by the installation script mentioned above.

**Note:** Traefik must be running inside the same Docker network as the containers it is associated with. By default the name for this network is expected to be ```delphi```. Windows users have to manually create it using ```docker network create delphi``` before starting Traefik. If you want to change this network name, please follow these steps:

1. Go to ```docker-compose.yml``` file (for Windows: Create during tutorial; for Linux found in ```/Setup```)

2. Change the item *services->traefik->networks* to your new network name

3. Change the item *networks->delphi->external:true* to *networks->your-network-name->external:true*. Save and close the file

4. Change the ````traefikDockerNetwork`` setting in the configuration file to your new network name (see section below for details)

## Adapt the configuration file
Before you can start the application, you have to make sure your configuration file contains valid data. The file can be found at *src/main/scala/de/upb/cs/swt/delphi/instanceregistry/Configuration.scala*, and most of its attributes are string or integer values. The following table describes the attributes in more detail.

|Attribute | Type | Default Value |Explanation |
| :---: | :---: | :---: | :--- |
|```bindHost``` | ```String``` | ```"0.0.0.0"``` | Host address that the registry server should be bound to |
|```bindPort``` | ```Int``` | ```8087``` | Port that the registry server should be reachable at |
|```traefikBaseHost``` | ```String``` | ```"delphi.de"``` | The host part of the URL that traefik is configured to append to instance URLs. |
|```traefikDockerNetwork``` | ```String``` | ```"delphi"``` | The Docker network Traefik is configured to use. |
|```traefikUri``` | ```String``` | ```"http://172.17.0.1:80"``` | The URI that the Traefik reverse-proxy is hosted at.|
|```defaultCrawlerPort``` | ```Int``` | ```8882``` | Port that Delphi Crawlers are reachable at. This may only be adapted if you manually changed the default port of crawlers before registering the respective image. |
|```defaultWebApiPort``` | ```Int``` | ```8080``` | Port that Delphi WebAPIs are reachable at. This may only be adapted if you manually changed the default port of WebAPIs before registering the respective image. |
|```defaultWebAppPort``` | ```Int``` | ```8085``` | Port that Delphi WebApps are reachable at. This may only be adapted if you manually changed the default port of WebApps before registering the respective image. |
|```crawlerDockerImageName``` | ```String``` | ```"delphi-crawler:1.0.0-SNAPSHOT"``` | Name of the Docker image for Delphi Crawlers. May only be changed if you manually specified a different name when creating the image.|
|```webApiDockerImageName``` | ```String``` | ```"delphi-webapi:1.0.0-SNAPSHOT"``` | Name of the Docker image for Delphi WebAPIs. May only be changed if you manually specified a different name when creating the image.|
|```webAppDockerImageName``` | ```String``` | ```"delphi-webapp:1.0.0-SNAPSHOT"``` | Name of the Docker image for Delphi WebApps. May only be changed if you manually specified a different name when creating the image.|
|```defaultElasticSearchInstanceHost``` | ```String``` | ```"elasticsearch://172.17.0.1"``` | Host that the default ElasticSearch instance is located at.|
|```defaultElasticSearchInstancePort``` | ```Int``` | ```9200``` | Port that the default ElasticSearch instance is reachable at.|
|```uriInLocalNetwork``` | ```String``` | ```"http://172.17.0.1:8087"``` | URI that the registry is reachable at for all docker containers. In most of the use-cases this is going to be the gateway of the default docker bridge.<br>**Note:** For OSX you have to set this value to the DNS name of the docker host, which is ```http://host.docker.internal:8087``` (If the registry is running on the host).|
|```maxLabelLength``` | ```Int``` | ```50``` | Maximum number of characters for instance labels. Longer labels will be rejected.|
|```dockerOperationTimeout``` | ```Timeout``` | ```Timeout(20 seconds)``` | Default timeout for docker operations. If any of the async Docker operations (deploy, stop, pause, ..) takes longer than this, it will be aborted.|
|```dockerUri``` | ```String``` | ```http://localhost:9095``` | Default uri to connect to docker. It will be used if the environment variable ```DELPHI_DOCKER_HOST``` is not specified.|
|```jwtSecretKey``` | ```String``` | ```changeme``` | Secret key to use for JWT signature (HS256). This setting can be overridden by specifying the ```JWT_SECRET``` environment variable.|
|```useInMemoryInstanceDB``` | ```Boolean``` | ```true``` | If set to true, all instance data will be kept in memory instead of using a MySQL database.|
|```instanceDatabaseHost``` | ```String``` | ```"jdbc:mysql://localhost/"``` | Host that the MySQL instance database is reachable at (only necessary if *useInMemoryInstanceDB* is false).|
|```instanceDatabaseName``` | ```String``` | ```""``` | Name of the MySQL instance database to use (only necessary if *useInMemoryInstanceDB* is false).|
|```instanceDatabaseDriver``` | ```String``` | ```"com.mysql.jdbc.Driver"``` | Driver to use for the MySQL connection (only necessary if *useInMemoryInstanceDB* is false).|
|```instanceDatabaseUsername``` | ```String``` | ```""``` | Username to use for the MySQL instance DB connection (only necessary if *useInMemoryInstanceDB* is false).|
|```instanceDatabasePassword``` | ```String``` | ```""``` | Password to use for the MySQL instance DB connection (only necessary if *useInMemoryInstanceDB* is false).|
|```useInMemoryAuthDB``` | ```Boolean``` | ```true``` | If set to true, all user data will be kept in memory instead of using a MySQL database.|
|```authDatabaseHost``` | ```String``` | ```"jdbc:mysql://localhost/"``` | Host that the MySQL users database is reachable at (only necessary if *useInMemoryAuthDB* is false).|
|```authDatabaseName``` | ```String``` | ```""``` | Name of the MySQL user database to use (only necessary if *useInMemoryAuthDB* is false).|
|```authDatabaseDriver``` | ```String``` | ```"com.mysql.jdbc.Driver"``` | Driver to use for the MySQL users DB connection (only necessary if *useInMemoryAuthDB* is false).|
|```authDatabaseUsername``` | ```String``` | ```""``` | Username to use for the MySQL users DB connection (only necessary if *useInMemoryAuthDB* is false).|
|```authDatabasePassword``` | ```String``` | ```""``` | Password to use for the MySQL users DB connection (only necessary if *useInMemoryAuthDB* is false).|
|```authenticationValidFor``` | ```Int``` | ```30``` | Default duration that user tokens are valid for (in minutes).|
|```maxTotalNoRequest``` | ```Int``` | ```2000``` | Maximum number of requests that are allowed to be executed during the current refresh period regardless of their origin.|
|```maxIndividualIpReq``` | ```Int``` | ```200``` | Maximum number of requests that are allowed to be executed during the current refresh period for one specific origin ip.|
|```ipLogRefreshRate``` | ```FiniteDuration``` | ```2.minutes``` | Duration of the log refresh period.|


## Docker configuration
By default, Docker is expected to be reachable at ```http://localhost:9095``` (see configuration attribute ```defaultDockerUri``` above), but you can override this setting by specifying the docker host URI in the environment variable *DELPHI_DOCKER_HOST*. You can also change the port that your Docker HTTP service is hosted on by executing the steps below on the Docker host machine.

### Docker configuration for Linux
To change the port to 9095, go to the docker service file:

```
sudo nano /lib/systemd/system/docker.service
```

Change the line that starts with ```ExecStart``` to look like this:

```
ExecStart=/usr/bin/dockerd -H fd:// -H=tcp://0.0.0.0:9095
```

Save your changes, close the editor and execute

```
systemctl daemon-reload
sudo service docker restart
```

### Docker configuration for OSX
Docker does not expose it's HTTP api on OSX for security reasons (as described [here](https://github.com/docker/for-mac/issues/770#issuecomment-252560286)), but you can run a docker container to redirect the API calls. To accept calls on your local machine's port 9095, execute:

```
docker run -d -v /var/run/docker.sock:/var/run/docker.sock -p 127.0.0.1:9095:1234 bobrik/socat TCP-LISTEN:1234,fork UNIX-CONNECT:/var/run/docker.sock
```

## Running Registry application
There are two ways of running the registry application. You can either run the application directly, or build a docker image defined by the *build.sbt* file, and run a container based on this image. Either way, you have to set the correct configuration values before starting the application (see section **Adapt the configuration file** above for more information). Make sure the Docker images of all Delphi components are present at the host's registry, as described in the **Requirements** section.

**Note:** For OSX you have to set Java's ```prefereIPv4Stack``` option to ```true``` before executing any of the steps below. In order to do so, execute ```export _JAVA_OPTIONS="-Djava.net.preferIPv4Stack=true"``` in the terminal before calling ```sbt```.

### Run the registry directly
If you want to execute the registry directly on your local machine, simply go to the root folder of the repository and execute ```sbt run```. The application will stream all logging output to the terminal. You can terminate any time by pressing *RETURN*.
### Run the registry in Docker
Before creating an Docker image from the registry, make sure that all the network settings in the configuration file are set correctly in regards to the Docker networking infrastructure. Especially the settings ```dockerUri``` and ```uriInLocalNetwork``` are important. On Linux Docker Containers can connect to their host using the default Docker network gateway (```172.17.0.2```), on OSX the host has a defined DNS name (```host.docker.internal```).

For Windows users, to build a docker image containing the registry, go to the root folder of the repository and execute ```sbt docker:publishLocal```. This will build the application, create a docker image named ```delphi-registry:1.0.0-SNAPSHOT```, and register the image at your local docker registry.<br />
For Linux users, the installation script mentioned in **Requirements** section will create docker image for registry named ```delphi-registry:1.0.0-SNAPSHOT```, and registers the image at your local docker registry.

## Authorization
This application relies on *JSON Web Tokens* (JWTs) using the *HMAC with SHA-256* (HS256) algorithm for authorization purposes. A valid, base64-encoded token must be put into the ```Authorization``` header of every HTTP request that is being issued to the registry. The HTTP header must look like this:
```
Authorization: Bearer <JWT>
```
You can find more about JWTs [here](https://jwt.io). To create valid JWTs for this application, the following fields have to be specified:

|Attribute | Type | Explanation |
| :---: | :---: | :--- |
|```iat``` (Issued at) | ```Int``` | Time this token was issued at. Specified in seconds since Jan 01 1970.|
|```nbf``` (Not valid before) | ```Int``` | Time this token becomes valid at. Specified in seconds since Jan 01 1970.|
|```exp``` (Expiration time) | ```Int``` | Time this token expires at. Specified in seconds since Jan 01 1970.|
|```user_id``` | ```String``` | Id of the user this token was issued to.|
|```user_type``` | ```String``` | Type of user that this token was issued to. Valid values are ```Admin``` (full access), ```User``` (read access) and ```Component``` (access to report operations).|

Please note that values of type ```Int``` must **not** be surrounded by quotation marks.

The secret key that is used for validating the tokens can either be set in the configuration file (see section below), or by setting the envirnment variable ```JWT_SECRET```. The default value is ```changeme``` and **has to be replaced for productive use!**

You can create tokens for development purposes using the JWT debugger at [jwt.io](https://jwt.io). The following token is valid for the default key ```changeme``` until end of march, and belongs to a user called ```DebugUser``` of user type ```Admin```. **Only use it for development purposes!**

```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpYXQiOjE1NDcxMDYzOTksIm5iZiI6MTU0NzEwNjM5OSwiZXhwIjoxNTU0MDE0Nzk5LCJ1c2VyX2lkIjoiRGVidWdVc2VyIiwidXNlcl90eXBlIjoiQWRtaW4ifQ.TeDa8JkFANVEufPaxXv3AXSojcaiKdOlBKeU5cLaHpg
```

Using the above token, a valid call to the registry at ```localhost:8087``` using *curl* looks like this:

```
curl -X POST -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpYXQiOjE1NDcxMDYzOTksIm5iZiI6MTU0NzEwNjM5OSwiZXhwIjoxNTU0MDE0Nzk5LCJ1c2VyX2lkIjoiRGVidWdVc2VyIiwidXNlcl90eXBlIjoiQWRtaW4ifQ.TeDa8JkFANVEufPaxXv3AXSojcaiKdOlBKeU5cLaHpg" -H "Content-type: application/json" -d '{"ComponentType":"WebApi"}' localhost:8087/instances/deploy
```

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

