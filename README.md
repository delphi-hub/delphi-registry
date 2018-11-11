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
The Delphi registry requires a docker host to deploy containers to. By default, docker is expected to be reachable at *http://localhost:9095*, but you can override this setting by specifying the docker host URI in the environment variable *DOCKER_HOST*.
To change the port of your http docker API to 9095, execute
```
edit /lib/systemd/system/docker.service
ExecStart=/usr/bin/dockerd -H fd:// -H=tcp://0.0.0.0:9095
systemctl daemon-reload
sudo service docker restart
```


The following images must be registered at the docker registry:
* The Delphi Crawler ( ```delphi-crawler:1.0.0-SNAPSHOT``` )
* The Delphi WebApi ( ```delphi-webapi:1.0.0-SNAPSHOT``` )
* The Delphi WebApp ( ```delphi-webapp:1.0.0-SNAPSHOT``` )

To obtain these images, checkout the respective repositories ([here](https://github.com/delphi-hub/delphi-crawler), [here](https://github.com/delphi-hub/delphi-webapi) and [here](https://github.com/delphi-hub/delphi-webapp)) and execute the command 

```
sbt docker:publishLocal
```
inside their root directory. This will build the docker images and register them directly at the local docker registry.
The registry requires an initial instance of ElasticSearch to be running. The default location for this is *elasticsearch://172.17.0.1:9200*, however this can be changed in the *Configuration.scala* file at *src/main/scala/de/upb/cs/swt/delphi/instanceregistry*.

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
