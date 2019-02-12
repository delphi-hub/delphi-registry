// Copyright (C) 2018 The Delphi Team.
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

// http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package de.upb.cs.swt.delphi.instanceregistry.connection

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.{HttpApp, Route}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import de.upb.cs.swt.delphi.instanceregistry.authorization.AccessTokenEnums.UserType
import de.upb.cs.swt.delphi.instanceregistry.authorization.AccessToken
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model._
import de.upb.cs.swt.delphi.instanceregistry.requestLimiter.{IpLogActor, RequestLimitScheduler}
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Registry, RequestHandler}
import spray.json.JsonParser.ParsingException
import spray.json._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

/**
  * Web server configuration for Instance Registry API.
  */
class Server(handler: RequestHandler) extends HttpApp
  with InstanceJsonSupport
  with UserJsonSupport
  with EventJsonSupport
  with InstanceLinkJsonSupport
  with ConfigurationInfoJsonSupport
  with AppLogging {

  implicit val system: ActorSystem = Registry.system
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  private val ipLogActor = system.actorOf(IpLogActor.props)
  private val requestLimiter = new RequestLimitScheduler(ipLogActor)

  override def routes: server.Route = {
    requestLimiter.acceptOnValidLimit {
      apiRoutes
    }
  }

  // scalastyle:off method.length

  //Routes that map http endpoints to methods in this object
  def apiRoutes: server.Route =

  /****************BASIC OPERATIONS ****************/
    pathPrefix("instances") {
      pathEnd {
        fetchInstancesOfType()
      } ~
        path("register") {
          entity(as[String]) {
            jsonString => register(jsonString)
          }
        } ~
        path("network") {
          network()
        } ~
        path("deploy") {
          entity(as[JsValue]) { json => deployContainer(json.asJsObject) }
        } ~
        path("count") {
          numberOfInstances()
        } ~
        pathPrefix(LongNumber) { Id =>
          pathEnd {
            retrieveInstance(Id)
          } ~
            path("deregister") {
              deregister(Id)
            } ~
            path("matchingInstance") {
              matchingInstance(Id)
            } ~
            path("matchingResult") {
              entity(as[JsValue]) {
                json => matchInstance(Id, json.asJsObject)
              }
            } ~
            path("eventList") {
              eventList(Id)
            } ~
            path("linksFrom") {
              linksFrom(Id)
            } ~
            path("linksTo") {
              linksTo(Id)
            } ~
            path("reportStart") {
              reportStart(Id)
            } ~
            path("reportStop") {
              reportStop(Id)
            } ~
            path("reportFailure") {
              reportFailure(Id)
            } ~
            path("pause") {
              pause(Id)
            } ~
            path("resume") {
              resume(Id)
            } ~
            path("stop") {
              stop(Id)
            } ~
            path("start") {
              start(Id)
            } ~
            path("delete") {
              deleteContainer(Id)
            } ~
            path("assignInstance") {
              entity(as[JsValue]) {
                json => assignInstance(Id, json.asJsObject)
              }
            } ~
            path("label") {
              entity(as[JsValue]) { json => addLabel(Id, json.asJsObject) }
            } ~
            path("logs") {
              retrieveLogs(Id)
            } ~
            path("attach") {
              streamLogs(Id)
            } ~
            path("command") {
              entity(as[JsValue]) { json => runCommandInContainer(Id, json.asJsObject) }
            }
        }
    } ~
    pathPrefix("users") {
      path("add") {
        entity(as[String]) {
          jsonString => addUser(jsonString)
        }
      } ~
      path("authenticate") {
        authenticate()
      }
    } ~
    path("events") {
      streamEvents()
    } ~
    path("configuration") {
      configurationInfo()
    }

  //scalastyle:on method.length

  /**
    * Registers a new instance at the registry. This endpoint is intended for instances that are not running inside
    * a docker container, as the Id, DockerId and InstanceState are being ignored.
    *
    * @param InstanceString String containing the serialized instance that is registering
    * @return Server route that either maps to a 200 OK response if successful, or to the respective error codes
    */
  def register(InstanceString: String): server.Route = Route.seal {

    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Component)) { token =>

      post {
        log.debug(s"POST /instances/register has been called, parameter is: $InstanceString")

        try {
          val paramInstance: Instance = InstanceString.parseJson.convertTo[Instance](instanceFormat)
          handler.handleRegister(paramInstance) match {
            case Success(id) =>
              complete {
                id.toString
              }
            case Failure(ex) =>
              log.warning(s"Failed to handle registration of instance. ${ex.getMessage}")
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "An internal server error occurred."))
          }
        } catch {
          case dx: DeserializationException =>
            log.warning(s"Deserialization exception: ${dx.msg}")
            complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not deserialize parameter instance with message: ${dx.getMessage}."))
          case px: ParsingException =>
            log.warning(s"Failed to parse JSON while registering: ${px.summary}")
            complete(HttpResponse(StatusCodes.BadRequest, entity = s"Failed to parse JSON entity with message: ${px.getMessage}"))
          case x: Exception =>
            log.warning("Uncaught exception while deserializing")
            complete(HttpResponse(StatusCodes.InternalServerError, entity = "An internal server error occurred."))
        }
      }
    }

  }

  /**
    * Removes an instance. The id of the instance that is calling deregister must be passed as an query argument named
    * 'Id' (so the call is /deregister?Id=42). This endpoint is intended for instances that are not running inside
    * a docker container, as the respective instance will be permanently deleted from the registry.
    *
    * @return Server route that either maps to a 200 OK response if successful, or to the respective error codes.
    */
  def deregister(id: Long): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Component)) { token =>
      post {
        log.debug(s"POST instance/$id/deregister has been called")

        handler.handleDeregister(id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot remove instance with id $id, that id is not known to the server.")
            complete {
              HttpResponse(StatusCodes.NotFound, entity = s"Id $id not known to the server")
            }
          case handler.OperationResult.IsDockerContainer =>
            log.warning(s"Cannot remove instance with id $id, this instance is running inside a docker container")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Cannot remove instance with id $id, this instance is " +
                s"running inside a docker container. Call /delete to remove it from the server and delete the container.")
            }
          case handler.OperationResult.Ok =>
            log.info(s"Successfully removed instance with id $id")
            complete {
              s"Successfully removed instance with id $id"
            }
        }
      }
    }
  }

  /**
    * Returns a list of instances with the specified ComponentType. The ComponentType must be passed as an query argument
    * named 'ComponentType' (so the call is /instances?ComponentType=Crawler).
    *
    * @return Server route that either maps to a 200 OK response containing the list of instances, or the resp. error codes.
    */
  def fetchInstancesOfType(): server.Route = parameters('ComponentType.as[String].?) { compTypeString =>
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.User)) { token =>
      get {
        log.debug(s"GET /instances?ComponentType=$compTypeString has been called")

        val noValue = "<novalue>"

        val compTypeStr = compTypeString.getOrElse(noValue)

        val compType: ComponentType = ComponentType.values.find(v => v.toString == compTypeStr).orNull

        if (compType != null) {
          complete {
            handler.getAllInstancesOfType(Some(compType))
          }
        } else if (compTypeStr == noValue) {
          complete {
            handler.getAllInstancesOfType(None).toList
          }
        }
        else {
          log.warning(s"Failed to deserialize parameter string $compTypeString to ComponentType.")
          complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not deserialize parameter string $compTypeString to ComponentType"))
        }
      }
    }
  }

  /**
    * Returns the number of instances for the specified ComponentType. The ComponentType must be passed as an query
    * argument named 'ComponentType' (so the call is /numberOfInstances?ComponentType=Crawler).
    *
    * @return Server route that either maps to a 200 OK response containing the number of instance, or the resp. error codes.
    */
  def numberOfInstances(): server.Route = parameters('ComponentType.as[String].?) { compTypeString =>
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.User)) { token =>
      get {
        log.debug(s"GET instances/count?ComponentType=$compTypeString has been called")

        val noValue = "<novalue>"

        val compTypeStr = compTypeString.getOrElse(noValue)

        val compType: ComponentType = ComponentType.values.find(v => v.toString == compTypeStr).orNull

        if (compType != null) {
          complete {
            handler.getNumberOfInstances(Some(compType)).toString()
          }
        } else if (compTypeStr == noValue) {
          complete {
            handler.getNumberOfInstances(None).toString()
          }
        }
        else {
          log.warning(s"Failed to deserialize parameter string $compTypeString to ComponentType.")
          complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not deserialize parameter string $compTypeString to ComponentType"))
        }
      }
    }
  }

  /**
    * Returns an instance with the specified id. Id is passed as query argument named 'Id' (so the resulting call is
    * /instance?Id=42)
    *
    * @return Server route that either maps to 200 OK and the respective instance as entity, or 404.
    */
  def retrieveInstance(id: Long): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.User)) { token =>
      get {
        log.debug(s"GET /instances/$id has been called")

        val instanceOption = handler.getInstance(id)

        if (instanceOption.isDefined) {
          complete(instanceOption.get.toJson(instanceFormat))
        } else {
          complete {
            HttpResponse(StatusCodes.NotFound, entity = s"Id $id was not found on the server.")
          }
        }
      }
    }
  }

  /**
    * Returns an instance of the specified ComponentType that can be used to resolve dependencies. The ComponentType must
    * be passed as an query argument named 'ComponentType' (so the call is /matchingInstance?ComponentType=Crawler).
    *
    * @return Server route that either maps to 200 OK response containing the instance, or the resp. error codes.
    */
  def matchingInstance(id: Long): server.Route = parameters('ComponentType.as[String]) { compTypeString =>
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Component)) { token =>
      get {
        log.debug(s"GET instance/$id/matchingInstance?ComponentType=$compTypeString has been called")

        val compType: ComponentType = ComponentType.values.find(v => v.toString == compTypeString).orNull
        log.debug(s"Looking for instance of type $compType ...")

        if (compType != null) {
          handler.getMatchingInstanceOfType(id, compType) match {
            case (_, Success(matchedInstance)) =>
              log.info(s"Matched request from $id to $matchedInstance.")
              handler.handleInstanceLinkCreated(id, matchedInstance.id.get) match {
                case handler.OperationResult.IdUnknown =>
                  log.warning(s"Could not handle the creation of instance link, id $id was not found.")
                  complete(HttpResponse(StatusCodes.NotFound, entity = s"Could not find instance with id $id."))
                case handler.OperationResult.InvalidTypeForOperation =>
                  log.warning(s"Could not handle the creation of instance link, incompatible types found.")
                  complete {
                    HttpResponse(StatusCodes.BadRequest, entity = s"Invalid dependency type $compType")
                  }
                case handler.OperationResult.Ok =>
                  complete(matchedInstance.toJson(instanceFormat))
                case handler.OperationResult.InternalError =>
                  complete {
                    HttpResponse(StatusCodes.InternalServerError, entity = s"An internal error occurred")
                  }
              }
            case (handler.OperationResult.IdUnknown, _) =>
              log.warning(s"Cannot match to instance of type $compType, id $id was not found.")
              complete(HttpResponse(StatusCodes.NotFound, entity = s"Cannot match to instance of type $compType, id $id was not found."))
            case (_, Failure(x)) =>
              log.warning(s"Could not find matching instance for type $compType, message was ${x.getMessage}.")
              complete(HttpResponse(StatusCodes.NotFound, entity = s"Could not find matching instance of type $compType for instance with id $id."))
          }
        } else {
          log.warning(s"Failed to deserialize parameter string $compTypeString to ComponentType.")
          complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not deserialize parameter string $compTypeString to ComponentType"))
        }
      }
    }
  }

  /**
    * Applies a matching result to the instance with the specified id. The matching result and id are passed as query
    * parameters named 'Id' and 'MatchingSuccessful' (so the call is /matchingResult?Id=42&MatchingSuccessful=True).
    *
    * @return Server route that either maps to 200 OK or to the respective error codes
    */
  def matchInstance(affectedInstanceId: Long, json: JsObject): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Component)) { token =>

      post {
        log.debug(s"POST /instances/$affectedInstanceId/matchingResult has been called with entity $json")

        Try[(Boolean, Long)] {
          val callerId = json.fields("SenderId").toString.toLong
          val result = json.fields("MatchingSuccessful").toString.toBoolean
          (result, callerId)
        } match {
          case Success((result, callerId)) =>

            handler.handleMatchingResult(callerId, affectedInstanceId, result) match {
              case handler.OperationResult.IdUnknown =>
                log.warning(s"Cannot apply matching result for id $callerId to id $affectedInstanceId, at least one id could not be found")
                complete {
                  HttpResponse(StatusCodes.NotFound, entity = s"One of the ids $callerId and $affectedInstanceId was not found.")
                }
              case handler.OperationResult.Ok =>
                complete {
                  s"Matching result $result processed."
                }
            }

          case Failure(ex) =>
            log.warning(s"Failed to unmarshal parameters with message ${ex.getMessage}. Data: $json")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = "Wrong data format supplied.")
            }
        }

      }
    }
  }

  /**
    * Returns a list of registry events that are associated to the instance with the specified id. The id is passed as
    * query argument named 'Id' (so the resulting call is /eventList?Id=42).
    *
    * @return Server route mapping to either 200 OK and the list of event, or the resp. error codes.
    */
  def eventList(id: Long): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.User)) { token =>
      get {
        log.debug(s"GET instances/$id//eventList has been called")

        handler.getEventList(id) match {
          case Success(list) => complete {
            list
          }
          case Failure(_) => complete {
            HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")
          }
        }
      }
    }
  }

  /**
    * Returns general configuration information containing the docker uri and the traefik host
    *
    * @return ConfigurationInfo object
    */
  def configurationInfo(): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Admin)) { token =>
      get {
        log.debug(s"GET /configuration has been called")

        complete {
          handler.generateConfigurationInfo().toJson(ConfigurationInfoFormat).toString
        }
      }
    }
  }

  /**
    * Deploys a new container of the specified type. Also adds the resulting instance to the database. The mandatory
    * parameter 'ComponentType' is passed as a query argument. The optional parameter 'InstanceName' may also be passed as
    * query argument (so the resulting call may be /deploy?ComponentType=Crawler&InstanceName=MyCrawler).
    *
    * @return Server route that either maps to 202 ACCEPTED and the generated id of the instance, or the resp. error codes.
    */
  def deployContainer(json: JsObject): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Admin)) { token =>
      post {

        log.debug(s"POST /instances/deploy has been called with data: $json")

        val name = Try(json.fields("InstanceName").toString.replace("\"", "")).toOption

        Try(json.fields("ComponentType").toString.replace("\"", "")) match {
          case Success(compTypeString) =>
            val compType: ComponentType = ComponentType.values.find(v => v.toString == compTypeString).orNull

            if (compType != null) {
              log.debug(s"Trying to deploy container of type $compType" + (if (name.isDefined) {
                s" with name ${name.get}..."
              } else {
                "..."
              }))
              handler.handleDeploy(compType, name) match {
                case Success(id) =>
                  complete {
                    HttpResponse(StatusCodes.Accepted, entity = id.toString)
                  }
                case Failure(x) =>
                  complete {
                    HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error. Message: ${x.getMessage}")
                  }
              }

            } else {
              log.warning(s"Failed to deserialize parameter string $compTypeString to ComponentType.")
              complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not deserialize parameter string $compTypeString to ComponentType"))
            }
          case Failure(ex) =>
            log.warning(s"Failed to unmarshal parameters with message ${ex.getMessage}. Data: $json")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = "Wrong data format supplied.")
            }
        }
      }
    }
  }

  /**
    * Called to report that the instance with the specified id was started successfully. The Id is passed as query
    * parameter named 'Id' (so the resulting call is /reportStart?Id=42)
    *
    * @return Server route that either maps to 200 OK or the respective error codes
    */
  def reportStart(id: Long): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Component)) { token =>
      post {
        handler.handleReportStart(id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot report start for id $id, that id was not found.")
            complete {
              HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")
            }
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot report start for id $id, that instance is not running in a docker container.")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running in a docker container.")
            }
          case handler.OperationResult.Ok =>
            complete {
              "Report successfully processed."
            }
          case r =>
            complete {
              HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")
            }
        }
      }
    }
  }

  /**
    * Called to report that the instance with the specified id was stopped successfully. The Id is passed as query
    * parameter named 'Id' (so the resulting call is /reportStop?Id=42)
    *
    * @return Server route that either maps to 200 OK or the respective error codes
    */
  def reportStop(id: Long): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Component)) { token =>
      post {
        handler.handleReportStop(id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot report start for id $id, that id was not found.")
            complete {
              HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")
            }
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot report start for id $id, that instance is not running in a docker container.")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running in a docker container.")
            }
          case handler.OperationResult.Ok =>
            complete {
              "Report successfully processed."
            }
          case r =>
            complete {
              HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")
            }
        }
      }
    }
  }

  /**
    * Called to report that the instance with the specified id encountered a failure. The Id is passed as query
    * parameter named 'Id' (so the resulting call is /reportFailure?Id=42)
    *
    * @return Server route that either maps to 200 OK or the respective error codes
    */
  def reportFailure(id: Long): server.Route =  {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Component)) { token =>
      post {

        log.debug(s"POST /instances/$id/reportFailure has been called")

        handler.handleReportFailure(id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot report failure for id $id, that id was not found.")
            complete {
              HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")
            }
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot report failure for id $id, that instance is not running in a docker container.")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running in a docker container.")
            }
          case handler.OperationResult.Ok =>
            complete {
              "Report successfully processed."
            }
          case r =>
            complete {
              HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")
            }
        }
      }
    }
  }

  /**
    * Called to pause the instance with the specified id. The associated docker container is paused. The Id is passed
    * as a query argument named 'Id' (so the resulting call is /pause?Id=42).
    *
    * @return Server route that either maps to 202 ACCEPTED or the expected error codes.
    */
  def pause(id: Long): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Admin)) { token =>
      post {
        log.debug(s"POST /instances/$id/pause has been called")
        handler.handlePause(id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot pause id $id, that id was not found.")
            complete {
              HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")
            }
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot pause id $id, that instance is not running in a docker container.")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running in a docker container.")
            }
          case handler.OperationResult.InvalidStateForOperation =>
            log.warning(s"Cannot pause id $id, that instance is not running.")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running .")
            }
          case handler.OperationResult.Ok =>
            complete {
              HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")
            }
          case r =>
            complete {
              HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")
            }
        }
      }
    }
  }

  /**
    * Called to resume the instance with the specified id. The associated docker container is resumed. The Id is passed
    * as a query argument named 'Id' (so the resulting call is /resume?Id=42).
    *
    * @return Server route that either maps to 202 ACCEPTED or the expected error codes.
    */
  def resume(id: Long): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Admin)) { token =>
      post {
        log.debug(s"POST /instances/$id/resume has been called")
        handler.handleResume(id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot resume id $id, that id was not found.")
            complete {
              HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")
            }
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot resume id $id, that instance is not running in a docker container.")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running in a docker container.")
            }
          case handler.OperationResult.InvalidStateForOperation =>
            log.warning(s"Cannot resume id $id, that instance is not paused.")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not paused.")
            }
          case handler.OperationResult.Ok =>
            complete {
              HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")
            }
          case r =>
            complete {
              HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")
            }
        }
      }
    }
  }

  /**
    * Called to stop the instance with the specified id. The associated docker container is stopped. The Id is passed
    * as a query argument named 'Id' (so the resulting call is /stop?Id=42).
    *
    * @return Server route that either maps to 202 ACCEPTED or the expected error codes.
    */
  def stop(id: Long): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Admin)) { token =>
      post {
        log.debug(s"POST /instances/$id/stop has been called")
        handler.handleStop(id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot stop id $id, that id was not found.")
            complete {
              HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")
            }
          case handler.OperationResult.InvalidTypeForOperation =>
            log.warning(s"Cannot stop id $id, this component type cannot be stopped.")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Cannot stop instance of this type.")
            }
          case handler.OperationResult.InvalidStateForOperation =>
            log.warning(s"Cannot stop id $id, the associated container is paused.")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Cannot stop instance while it is paused.")
            }
          case handler.OperationResult.Ok =>
            complete {
              HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")
            }
          case r =>
            complete {
              HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")
            }
        }
      }
    }
  }

  /**
    * Called to start the instance with the specified id. The associated docker container is started. The Id is passed
    * as a query argument named 'Id' (so the resulting call is /start?Id=42).
    *
    * @return Server route that either maps to 202 ACCEPTED or the expected error codes.
    */
  def start(id: Long): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Admin)) { token =>
      post {
        log.debug(s"POST /instances/$id/start has been called")
        handler.handleStart(id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot start id $id, that id was not found.")
            complete {
              HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")
            }
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot start id $id, that instance is not running in a docker container.")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running in a docker container.")
            }
          case handler.OperationResult.InvalidStateForOperation =>
            log.warning(s"Cannot start id $id, that instance is not stopped.")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not stopped.")
            }
          case handler.OperationResult.Ok =>
            complete {
              HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")
            }
          case r =>
            complete {
              HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")
            }
        }
      }
    }
  }

  /**
    * Called to delete the instance with the specified id as well as the associated docker container. The Id is passed
    * as a query argument named 'Id' (so the resulting call is /delete?Id=42).
    *
    * @return Server route that either maps to 202 ACCEPTED or the respective error codes.
    */
  def deleteContainer(id: Long): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Admin)) { token =>
      post {
        log.debug(s"POST /instances/$id/delete has been called")
        handler.handleDeleteContainer(id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot delete id $id, that id was not found.")
            complete {
              HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")
            }
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot delete id $id, that instance is not running in a docker container.")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running in a docker container.")
            }
          case handler.OperationResult.InvalidStateForOperation =>
            log.warning(s"Cannot delete id $id, that instance is still running.")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not stopped.")
            }
          case handler.OperationResult.Ok =>
            complete {
              HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")
            }
          case handler.OperationResult.InternalError =>
            complete {
              HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error")
            }
          case handler.OperationResult.BlockingDependency =>
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Cannot delete this instance, other running instances are depending on it.")
            }
        }
      }
    }
  }

  /**
    * Called to assign a new instance dependency to the instance with the specified id. Both the ids of the instance and
    * the specified dependency are passed as query arguments named 'Id' and 'assignedInstanceId' resp. (so the resulting
    * call is /assignInstance?Id=42&assignedInstanceId=43). Will update the dependency in DB and than restart the container.
    *
    * @return Server route that either maps to 202 ACCEPTED or the respective error codes
    */
  def assignInstance(id: Long, json: JsObject): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Admin)) { token =>

      post {
        log.debug(s"POST /instances/$id/assignInstance has been called with data : $json ")

        Try[Long] {
          json.fields("AssignedInstanceId").toString.toLong
        } match {
          case Success(assignedInstanceId) =>
            handler.handleInstanceAssignment(id, assignedInstanceId) match {
              case handler.OperationResult.IdUnknown =>
                log.warning(s"Cannot assign $assignedInstanceId to $id, one or more ids not found.")
                complete {
                  HttpResponse(StatusCodes.NotFound, entity = s"Cannot assign instance, at least one of the ids $id / $assignedInstanceId was not found.")
                }
              case handler.OperationResult.NoDockerContainer =>
                log.warning(s"Cannot assign $assignedInstanceId to $id, $id is no docker container.")
                complete {
                  HttpResponse(StatusCodes.BadRequest, entity = s"Cannot assign instance, $id is no docker container.")
                }
              case handler.OperationResult.InvalidTypeForOperation =>
                log.warning(s"Cannot assign $assignedInstanceId to $id, incompatible types.")
                complete {
                  HttpResponse(StatusCodes.BadRequest, entity = s"Cannot assign $assignedInstanceId to $id, incompatible types.")
                }
              case handler.OperationResult.Ok =>
                complete {
                  HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")
                }
              case x =>
                complete {
                  HttpResponse(StatusCodes.InternalServerError, entity = s"Unexpected operation result $x")
                }
            }
          case Failure(ex) =>
            log.warning(s"Failed to unmarshal parameters with message ${ex.getMessage}. Data: $json")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = "Wrong data format supplied.")
            }
        }
      }
    }
  }

  /**
    * Called to get a list of links from the instance with the specified id. The id is passed as query argument named
    * 'Id' (so the resulting call is /linksFrom?Id=42).
    *
    * @return Server route that either maps to 200 OK (and the list of links as content), or the respective error code.
    */
  def linksFrom(id: Long): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.User)) { token =>
      get {
        log.debug(s"GET /instances/$id/linksFrom has been called.")

        handler.handleGetLinksFrom(id) match {
          case Success(linkList) =>
            complete {
              linkList
            }
          case Failure(ex) =>
            log.warning(s"Failed to get links from $id with message: ${ex.getMessage}")
            complete {
              HttpResponse(StatusCodes.NotFound, entity = s"Failed to get links from $id, that id is not known.")
            }
        }
      }
    }
  }

  /**
    * Called to get a list of links to the instance with the specified id. The id is passed as query argument named
    * 'Id' (so the resulting call is /linksTo?Id=42).
    *
    * @return Server route that either maps to 200 OK (and the list of links as content), or the respective error code.
    */
  def linksTo(id: Long): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.User)) { token =>
      get {
        log.debug(s"GET instances/$id/linksTo has been called.")

        handler.handleGetLinksTo(id) match {
          case Success(linkList) =>
            complete {
              linkList
            }
          case Failure(ex) =>
            log.warning(s"Failed to get links to $id with message: ${ex.getMessage}")
            complete {
              HttpResponse(StatusCodes.NotFound, entity = s"Failed to get links to $id, that id is not known.")
            }
        }
      }

    }
  }

  /**
    * Called to get the whole network graph of the current registry. Contains a list of all instances and all links
    * currently registered.
    *
    * @return Server route that maps to 200 OK and the current InstanceNetwork as content.
    */
  def network(): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.User)) { token =>
      get {
        log.debug(s"GET /instances/network has been called.")
        complete {
          handler.handleGetNetwork().toJson
        }
      }
    }
  }

  /**
    * Called to add a generic label to the instance with the specified id. The Id and label are passed as query arguments
    * named 'Id' and 'Label', resp. (so the resulting call is /addLabel?Id=42&Label=private)
    *
    * @return Server route that either maps to 200 OK or the respective error codes.
    */
  def addLabel(id: Long, json: JsObject): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Admin)) { token =>

      post {
        log.debug(s"POST /instances/$id/label has been called with data $json.")

        Try[String](json.fields("Label").toString.replace("\"", "")) match {
          case Success(label) =>
            handler.handleAddLabel(id, label) match {
              case handler.OperationResult.IdUnknown =>
                log.warning(s"Cannot add label $label to $id, id not found.")
                complete {
                  HttpResponse(StatusCodes.NotFound, entity = s"Cannot add label, id $id not found.")
                }
              case handler.OperationResult.InternalError =>
                log.warning(s"Error while adding label $label to $id: Label exceeds character limit.")
                complete {
                  HttpResponse(StatusCodes.BadRequest,
                    entity = s"Cannot add label to $id, label exceeds character limit of ${Registry.configuration.maxLabelLength}")
                }
              case handler.OperationResult.Ok =>
                log.info(s"Successfully added label $label to instance with id $id.")
                complete("Successfully added label")
            }
          case Failure(ex) =>
            log.warning(s"Failed to unmarshal parameters with message ${ex.getMessage}. Data: $json")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = "Wrong data format supplied.")
            }
        }
      }
    }
  }

  // scalastyle:off cyclomatic.complexity
  /**
    * Called to run a command in a  docker container. The Id an Command is the required parameter there are other optional parameter can be passed
    * a query with required parameter Command and Id (so the resulting call is /command?Id=42&Command=ls).
    *
    * @return Server route that either maps to 200 Ok or the respective error codes.
    */
  def runCommandInContainer(id: Long, json: JsObject): server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Admin)) { token =>
      post {
        log.debug(s"POST /command has been called")

        val privileged = Try(json.fields("Privileged").toString.toBoolean) match {
          case Success(res) => Some(res)
          case Failure(_) => None
        }

        val user = Try(json.fields("User").toString.replace("\"", "")) match {
          case Success(res) => Some(res)
          case Failure(_) => None
        }

        Try(json.fields("Command").toString.replace("\"", "")) match {
          case Success(command) =>
            handler.handleCommand(id, command, privileged, user) match {
              case handler.OperationResult.IdUnknown =>
                log.warning(s"Cannot run command $command to $id, id not found.")
                complete {
                  HttpResponse(StatusCodes.NotFound, entity = s"Cannot run command, id $id not found.")
                }
              case handler.OperationResult.NoDockerContainer =>
                log.warning(s"Cannot run command $command to $id, $id is no docker container.")
                complete {
                  HttpResponse(StatusCodes.BadRequest, entity = s"Cannot run command, $id is no docker container.")
                }
              case handler.OperationResult.Ok =>
                complete {
                  HttpResponse(StatusCodes.OK)
                }
              case r =>
                complete {
                  HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")
                }
            }
          case Failure(ex) =>
            log.warning(s"Failed to unmarshal parameters with message ${ex.getMessage}. Data: $json")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = "Wrong data format supplied.")
            }
        }
      }
    }
  }
  // scalastyle:on cyclomatic.complexity

  def retrieveLogs(id: Long): server.Route = parameters('StdErr.as[Boolean].?) { stdErrOption =>
    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Admin)) { token =>
      get {
        log.debug(s"GET /logs?Id=$id has been called")

        val stdErrSelected = stdErrOption.isDefined && stdErrOption.get

        handler.handleGetLogs(id, stdErrSelected) match {
          case (handler.OperationResult.IdUnknown, _) =>
            log.warning(s"Cannot get logs, id $id not found.")
            complete {
              HttpResponse(StatusCodes.NotFound, entity = s"Cannot get logs, id $id not found.")
            }
          case (handler.OperationResult.NoDockerContainer, _) =>
            log.warning(s"Cannot get logs, id $id is no docker container.")
            complete {
              HttpResponse(StatusCodes.BadRequest, entity = s"Cannot get logs, id $id is no docker container.")
            }
          case (handler.OperationResult.Ok, Some(logString)) =>
            complete {
              logString
            }
          case (handler.OperationResult.InternalError, _) =>
            complete {
              HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error")
            }
          case _ =>
            complete {
              HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error")
            }
        }
      }
    }
  }

  def streamLogs(id: Long): server.Route = parameters('StdErr.as[Boolean].?) { stdErrOption =>


    val stdErrSelected = stdErrOption.isDefined && stdErrOption.get

    handler.handleStreamLogs(id, stdErrSelected) match {
      case (handler.OperationResult.IdUnknown, _) =>
        complete {
          HttpResponse(StatusCodes.NotFound, entity = s"Cannot stream logs, id $id not found.")
        }
      case (handler.OperationResult.NoDockerContainer, _) =>
        complete {
          HttpResponse(StatusCodes.BadRequest, entity = s"Cannot stream logs, id $id is no docker container.")
        }
      case (handler.OperationResult.Ok, Some(publisher)) =>
        handleWebSocketMessages {
          Flow[Message]
            .via(
              Flow.fromSinkAndSource(Sink.ignore, Source.fromPublisher(publisher))
            )
            .watchTermination() { (_, done) =>
              done.onComplete {
                case Success(_) =>
                  log.info("Log stream route completed successfully")
                case Failure(ex) =>
                  log.error(s"Log stream route completed with failure : $ex")
              }
            }
        }
      case (handler.OperationResult.InternalError, _) =>
        complete {
          HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error")
        }
      case _ =>
        complete {
          HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error")
        }
    }

  }

  /**
    * Creates a WebSocketConnection that streams events that are issued by the registry to all connected clients.
    *
    * @return Server route that maps to the WebSocketConnection
    */
  def streamEvents(): server.Route = {
    handleWebSocketMessages {
      //Flush pending messages from publisher
      Source.fromPublisher(handler.eventPublisher).to(Sink.ignore).run()
      //Create flow from publisher
      Flow[Message]
        .map {
          case TextMessage.Strict(msg: String) => msg
          case _ => log.info("Ignored non-text message.")
        }
        .via(
          Flow.fromSinkAndSource(Sink.foreach(x => log.debug(x.toString)), Source.fromPublisher(handler.eventPublisher)
            .map(event => event.toJson(eventFormat).toString))
        )
        .map { msg: String => TextMessage.Strict(msg + "\n") }
        .watchTermination() { (_, done) =>
          done.onComplete {
            case Success(_) =>
              log.info("Stream route completed successfully")
            case Failure(ex) =>
              log.error(s"Stream route completed with failure : $ex")
          }
        }
    }
  }

  def authenticate() : server.Route = {
    post
    {
      headerValueByName("Delphi-Authorization") { token =>
        log.info(s"Requested with Delphi-Authorization token $token")
        if(handler.authProvider.isValidDelphiToken(token)){
          log.info(s"valid delphi authorization token")
          authenticateBasic(realm = "secure", handler.authProvider.authenticateBasicJWT) { userName =>
              complete(handler.authProvider.generateJwt(userName))
          }
        } else {
          complete{HttpResponse(StatusCodes.Unauthorized, entity = s"Not valid Delphi-authorization")}
        }

      }
    }
  }

  def addUser(UserString: String): server.Route = Route.seal{

    authenticateOAuth2[AccessToken]("Secure Site", handler.authProvider.authenticateOAuthRequire(_, userType = UserType.Admin)) { token =>

      post {
        log.debug(s"POST /users/add has been called, parameter is: $UserString")

        try {
          val paramInstance: DelphiUser = UserString.parseJson.convertTo[DelphiUser](AuthDelphiUserFormat)
          handler.handleAddUser(paramInstance) match {
            case Success(id) =>
              complete {
                id.toString
              }
            case Failure(ex) =>
              log.error(ex, "Failed to handle registration of instance.")
              complete(HttpResponse(StatusCodes.BadRequest, entity = "Username already taken."))
          }
        } catch {
          case dx: DeserializationException =>
            log.error(dx, "Deserialization exception")
            complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not deserialize parameter instance with message ${dx.getMessage}."))
          case px: ParsingException =>
            log.error(px, "Failed to parse JSON while registering")
            complete(HttpResponse(StatusCodes.BadRequest, entity = s"Failed to parse JSON entity with message ${px.getMessage}"))
          case x: Exception =>
            log.error(x, "Uncaught exception while deserializing.")
            complete(HttpResponse(StatusCodes.InternalServerError, entity = "An internal server error occurred."))
        }
      }
    }
  }

}

