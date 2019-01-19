package de.upb.cs.swt.delphi.instanceregistry.connection

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.{HttpApp, Route}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import de.upb.cs.swt.delphi.instanceregistry.authorization.AccessTokenEnums.UserType
import de.upb.cs.swt.delphi.instanceregistry.authorization.{AccessToken, AuthProvider}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.{EventJsonSupport, Instance, InstanceJsonSupport, InstanceLinkJsonSupport}
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Registry, RequestHandler}
import spray.json.JsonParser.ParsingException
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import de.upb.cs.swt.delphi.instanceregistry.requestLimiter.{IpLogActor, RequestLimitScheduler}

import scala.collection.immutable.Range

/**
  * Web server configuration for Instance Registry API.
  */
class Server (handler: RequestHandler) extends HttpApp
  with InstanceJsonSupport
  with EventJsonSupport
  with InstanceLinkJsonSupport
  with AppLogging {

  implicit val system : ActorSystem = Registry.system
  implicit val materializer : ActorMaterializer = ActorMaterializer()
  implicit val ec : ExecutionContext = system.dispatcher

  private val ipLogActor = system.actorOf(IpLogActor.props)
  private val requestLimiter = new RequestLimitScheduler(ipLogActor)

  override def routes: server.Route = {
    requestLimiter.acceptOnValidLimit {
      apiRoutes
    }
  }
  //Routes that map http endpoints to methods in this object
  def apiRoutes : server.Route =
      /****************BASIC OPERATIONS****************/

  pathPrefix("instances") {
    path("register") {entity(as[String]) { jsonString => register(jsonString) }} ~
    path("network") { network()} ~
    path("deploy") { deployContainer()} ~
    path("count") {numberOfInstances()} ~
    pathPrefix(LongNumber) { Id =>
      path("deregister") { deregister(Id) } ~
        path("matchingInstance") { matchingInstance(Id) } ~
        path("eventList") { eventList(Id) } ~
        path("linksFrom") { linksFrom(Id) } ~
        path("linksTo") { linksTo(Id)} ~
        path("reportStart") { reportStart(Id)} ~
        path("reportStop") { reportStop(Id)} ~
        path("reportFailure") { reportFailure(Id)} ~
        path("pause") { pause(Id)} ~
        path("resume") { resume(Id)} ~
        path("stop") { stop(Id)}
    }
  }~
      path("instances") { fetchInstancesOfType() } ~
      path("instance") { retrieveInstance() } ~
      path("matchingResult") { matchInstance()} ~
      path("addLabel") { addLabel()} ~
      /****************DOCKER OPERATIONS****************/
      path("start") { start()} ~
      path("delete") { deleteContainer()} ~
      path("assignInstance") { assignInstance()} ~
      path("command") { runCommandInContainer()} ~
      /****************EVENT OPERATIONS****************/
      path("events") { streamEvents()}




  /**
    * Registers a new instance at the registry. This endpoint is intended for instances that are not running inside
    * a docker container, as the Id, DockerId and InstanceState are being ignored.
    * @param InstanceString String containing the serialized instance that is registering
    * @return Server route that either maps to a 200 OK response if successful, or to the respective error codes
    */
  def register(InstanceString: String) : server.Route = Route.seal {

    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Component)) { token =>

      post
      {
        log.debug(s"POST /instances/register has been called, parameter is: $InstanceString")

        try {
          val paramInstance : Instance = InstanceString.parseJson.convertTo[Instance](instanceFormat)
          handler.handleRegister(paramInstance) match {
            case Success(id) =>
              complete{id.toString}
            case Failure(ex) =>
              log.error(ex, "Failed to handle registration of instance.")
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "An internal server error occurred."))
          }
        } catch {
          case dx : DeserializationException =>
            log.error(dx, "Deserialization exception")
            complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not deserialize parameter instance with message ${dx.getMessage}."))
          case px : ParsingException =>
            log.error(px, "Failed to parse JSON while registering")
            complete(HttpResponse(StatusCodes.BadRequest, entity = s"Failed to parse JSON entity with message ${px.getMessage}"))
          case x : Exception =>
            log.error(x, "Uncaught exception while deserializing.")
            complete(HttpResponse(StatusCodes.InternalServerError, entity = "An internal server error occurred."))
        }
      }
    }

  }

  /**
    * Removes an instance. The id of the instance that is calling deregister must be passed as an query argument named
    * 'Id' (so the call is /deregister?Id=42). This endpoint is intended for instances that are not running inside
    * a docker container, as the respective instance will be permanently deleted from the registry.
    * @return Server route that either maps to a 200 OK response if successful, or to the respective error codes.
    */
  def deregister(Id : Long) : server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Component)) { token =>
      post {
        log.debug(s"POST instance/$Id/deregister has been called")

        handler.handleDeregister(Id) match {
          case handler.OperationResult.IdUnknown  =>
            log.warning(s"Cannot remove instance with id $Id, that id is not known to the server.")
            complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $Id not known to the server")}
          case handler.OperationResult.IsDockerContainer =>
            log.warning(s"Cannot remove instance with id $Id, this instance is running inside a docker container")
            complete{HttpResponse(StatusCodes.BadRequest, entity = s"Cannot remove instance with id $Id, this instance is " +
              s"running inside a docker container. Call /delete to remove it from the server and delete the container.")}
          case handler.OperationResult.Ok =>
            log.info(s"Successfully removed instance with id $Id")
            complete {s"Successfully removed instance with id $Id"}
        }
      }
    }
  }

  /**
    * Returns a list of instances with the specified ComponentType. The ComponentType must be passed as an query argument
    * named 'ComponentType' (so the call is /instances?ComponentType=Crawler).
    * @return Server route that either maps to a 200 OK response containing the list of instances, or the resp. error codes.
    */
  def fetchInstancesOfType () : server.Route = parameters('ComponentType.as[String]) { compTypeString =>
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.User)) { token =>
      get {
        log.debug(s"GET /instances?ComponentType=$compTypeString has been called")

        val compType : ComponentType = ComponentType.values.find(v => v.toString == compTypeString).orNull

        if(compType != null) {
          complete{handler.getAllInstancesOfType(compType)}
        } else {
          log.error(s"Failed to deserialize parameter string $compTypeString to ComponentType.")
          complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not deserialize parameter string $compTypeString to ComponentType"))
        }
      }
    }
  }

  /**
    * Returns the number of instances for the specified ComponentType. The ComponentType must be passed as an query
    * argument named 'ComponentType' (so the call is /numberOfInstances?ComponentType=Crawler).
    * @return Server route that either maps to a 200 OK response containing the number of instance, or the resp. error codes.
    */
  def numberOfInstances() : server.Route = parameters('ComponentType.as[String].?) { compTypeString =>
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.User)) { token =>
      get {
        log.debug(s"GET instances/count?ComponentType=$compTypeString has been called")

        val noValue = "<novalue>"

        val compTypeStr = compTypeString.getOrElse(noValue)

        val compType : ComponentType = ComponentType.values.find(v => v.toString == compTypeStr).orNull

        if(compType != null) {
          complete{handler.getNumberOfInstances(compType).toString()}
        } else if (compTypeStr ==noValue) {
          complete{handler.getAllInstancesCount().toString()}
        }
        else {
          log.error(s"Failed to deserialize parameter string $compTypeString to ComponentType.")
          complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not deserialize parameter string $compTypeString to ComponentType"))
        }
      }
    }
  }

  /**
    * Returns an instance with the specified id. Id is passed as query argument named 'Id' (so the resulting call is
    * /instance?Id=42)
    * @return Server route that either maps to 200 OK and the respective instance as entity, or 404.
    */
  def retrieveInstance() : server.Route = parameters('Id.as[Long]) { id =>
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.User)){ token =>
      get {
        log.debug(s"GET /instance?Id=$id has been called")

        val instanceOption = handler.getInstance(id)

        if(instanceOption.isDefined){
          complete(instanceOption.get.toJson(instanceFormat))
        } else {
          complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $id was not found on the server.")}
        }
      }
    }
  }

  /**
    * Returns an instance of the specified ComponentType that can be used to resolve dependencies. The ComponentType must
    * be passed as an query argument named 'ComponentType' (so the call is /matchingInstance?ComponentType=Crawler).
    * @return Server route that either maps to 200 OK response containing the instance, or the resp. error codes.
    */
  def matchingInstance(Id :Long) : server.Route = parameters('ComponentType.as[String]){ (compTypeString) =>
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Component)) { token =>
      get{
        log.debug(s"GET instance/$Id/matchingInstance?ComponentType=$compTypeString has been called")

        val compType : ComponentType = ComponentType.values.find(v => v.toString == compTypeString).orNull
        log.info(s"Looking for instance of type $compType ...")

        if(compType != null){
          handler.getMatchingInstanceOfType(Id, compType) match {
            case (_, Success(matchedInstance)) =>
              log.info(s"Matched request from $Id to $matchedInstance.")
              handler.handleInstanceLinkCreated(Id, matchedInstance.id.get) match {
                case handler.OperationResult.IdUnknown =>
                  log.warning(s"Could not handle the creation of instance link, id $Id was not found.")
                  complete(HttpResponse(StatusCodes.NotFound, entity = s"Could not find instance with id $Id."))
                case handler.OperationResult.InvalidTypeForOperation =>
                  log.warning(s"Could not handle the creation of instance link, incompatible types found.")
                  complete{HttpResponse(StatusCodes.BadRequest, entity = s"Invalid dependency type $compType")}
                case handler.OperationResult.Ok =>
                  complete(matchedInstance.toJson(instanceFormat))
                case handler.OperationResult.InternalError =>
                  complete{HttpResponse(StatusCodes.InternalServerError, entity = s"An internal error occurred")}
              }
            case (handler.OperationResult.IdUnknown, _) =>
              log.warning(s"Cannot match to instance of type $compType, id $Id was not found.")
              complete(HttpResponse(StatusCodes.NotFound, entity = s"Cannot match to instance of type $compType, id $Id was not found."))
            case (_, Failure(x)) =>
              log.warning(s"Could not find matching instance for type $compType, message was ${x.getMessage}.")
              complete(HttpResponse(StatusCodes.NotFound, entity = s"Could not find matching instance of type $compType for instance with id $Id."))
          }
        } else {
          log.error(s"Failed to deserialize parameter string $compTypeString to ComponentType.")
          complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not deserialize parameter string $compTypeString to ComponentType"))
        }
      }
    }
  }

  /**
    * Applies a matching result to the instance with the specified id. The matching result and id are passed as query
    * parameters named 'Id' and 'MatchingSuccessful' (so the call is /matchingResult?Id=42&MatchingSuccessful=True).
    * @return Server route that either maps to 200 OK or to the respective error codes
    */
  def matchInstance() : server.Route = parameters('CallerId.as[Long], 'MatchedInstanceId.as[Long], 'MatchingSuccessful.as[Boolean]){ (callerId, matchedInstanceId, matchingResult) =>
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Component)) { token =>
      post {
        log.debug(s"POST /matchingResult?callerId=$callerId&matchedInstanceId=$matchedInstanceId&MatchingSuccessful=$matchingResult has been called")

        handler.handleMatchingResult(callerId, matchedInstanceId, matchingResult) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot apply matching result for id $callerId to id $matchedInstanceId, at least one id could not be found")
            complete{HttpResponse(StatusCodes.NotFound, entity = s"One of the ids $callerId and $matchedInstanceId was not found.")}
          case handler.OperationResult.Ok =>
            complete{s"Matching result $matchingResult processed."}
        }
      }
    }
  }

  /**
    * Returns a list of registry events that are associated to the instance with the specified id. The id is passed as
    * query argument named 'Id' (so the resulting call is /eventList?Id=42).
    * @return Server route mapping to either 200 OK and the list of event, or the resp. error codes.
    */
  def eventList(Id : Long) : server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.User)){ token =>
      get {
        log.debug(s"GET instances/$Id//eventList has been called")

        handler.getEventList(Id) match {
          case Success(list) => complete{list}
          case Failure(_) => complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $Id not found.")}
        }
      }
    }
  }

  /**
    * Deploys a new container of the specified type. Also adds the resulting instance to the database. The mandatory
    * parameter 'ComponentType' is passed as a query argument. The optional parameter 'InstanceName' may also be passed as
    * query argument (so the resulting call may be /deploy?ComponentType=Crawler&InstanceName=MyCrawler).
    * @return Server route that either maps to 202 ACCEPTED and the generated id of the instance, or the resp. error codes.
    */
  def deployContainer() : server.Route = parameters('ComponentType.as[String], 'InstanceName.as[String].?) { (compTypeString, name) =>
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Admin)){ token =>
      post {
        if(name.isEmpty){
          log.debug(s"POST /deploy?ComponentType=$compTypeString has been called")
        } else {
          log.debug(s"POST /deploy?ComponentType=$compTypeString&name=${name.get} has been called")
        }
        val compType: ComponentType = ComponentType.values.find(v => v.toString == compTypeString).orNull

        if (compType != null) {
          log.info(s"Trying to deploy container of type $compType" + (if(name.isDefined){s" with name ${name.get}..."}else {"..."}))
          handler.handleDeploy(compType, name) match {
            case Success(id) =>
              complete{HttpResponse(StatusCodes.Accepted, entity = id.toString)}
            case Failure(x) =>
              complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error. Message: ${x.getMessage}")}
          }

        } else {
          log.error(s"Failed to deserialize parameter string $compTypeString to ComponentType.")
          complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not deserialize parameter string $compTypeString to ComponentType"))
        }
      }
    }
  }

  /**
    * Called to report that the instance with the specified id was started successfully. The Id is passed as query
    * parameter named 'Id' (so the resulting call is /reportStart?Id=42)
    * @return Server route that either maps to 200 OK or the respective error codes
    */
  def reportStart(Id : Long) : server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Component)) { token =>
      post {
        handler.handleReportStart(Id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot report start for id $Id, that id was not found.")
            complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $Id not found.")}
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot report start for id $Id, that instance is not running in a docker container.")
            complete{HttpResponse(StatusCodes.BadRequest, entity = s"Id $Id is not running in a docker container.")}
          case handler.OperationResult.Ok =>
            complete{"Report successfully processed."}
          case r =>
            complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")}
        }
      }
    }
  }

  /**
    * Called to report that the instance with the specified id was stopped successfully. The Id is passed as query
    * parameter named 'Id' (so the resulting call is /reportStop?Id=42)
    * @return Server route that either maps to 200 OK or the respective error codes
    */
  def reportStop(Id : Long) : server.Route =  {
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Component)) { token =>
      post {
        handler.handleReportStop(Id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot report start for id $Id, that id was not found.")
            complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $Id not found.")}
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot report start for id $Id, that instance is not running in a docker container.")
            complete{HttpResponse(StatusCodes.BadRequest, entity = s"Id $Id is not running in a docker container.")}
          case handler.OperationResult.Ok =>
            complete{"Report successfully processed."}
          case r =>
            complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")}
        }
      }
    }
  }

  /**
    * Called to report that the instance with the specified id encountered a failure. The Id is passed as query
    * parameter named 'Id' (so the resulting call is /reportFailure?Id=42)
    * @return Server route that either maps to 200 OK or the respective error codes
    */
  def reportFailure(Id : Long) : server.Route = parameters('ErrorLog.as[String].?) {(errorLog) =>
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Component)) { token =>
      post{
        if(errorLog.isEmpty){
          log.debug(s"POST /instances/$Id/reportFailure has been called")
        } else {
          log.debug(s"POST /instances/$Id/reportFailure&ErrorLog=${errorLog.get} has been called")
        }

        handler.handleReportFailure(Id, errorLog) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot report failure for id $Id, that id was not found.")
            complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $Id not found.")}
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot report failure for id $Id, that instance is not running in a docker container.")
            complete{HttpResponse(StatusCodes.BadRequest, entity = s"Id $Id is not running in a docker container.")}
          case handler.OperationResult.Ok =>
            complete{"Report successfully processed."}
          case r =>
            complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")}
        }
      }
    }
  }

  /**
    * Called to pause the instance with the specified id. The associated docker container is paused. The Id is passed
    * as a query argument named 'Id' (so the resulting call is /pause?Id=42).
    * @return Server route that either maps to 202 ACCEPTED or the expected error codes.
    */
  def pause(Id : Long) : server.Route =  {
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Admin)) {token =>
      post{
        log.debug(s"POST /instances/$Id/pause has been called")
        handler.handlePause(Id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot pause id $Id, that id was not found.")
            complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $Id not found.")}
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot pause id $Id, that instance is not running in a docker container.")
            complete{HttpResponse(StatusCodes.BadRequest, entity = s"Id $Id is not running in a docker container.")}
          case handler.OperationResult.InvalidStateForOperation =>
            log.warning(s"Cannot pause id $Id, that instance is not running.")
            complete{HttpResponse(StatusCodes.BadRequest, entity = s"Id $Id is not running .")}
          case handler.OperationResult.Ok =>
            complete{HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")}
          case r =>
            complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")}
        }
      }
    }
  }

  /**
    * Called to resume the instance with the specified id. The associated docker container is resumed. The Id is passed
    * as a query argument named 'Id' (so the resulting call is /resume?Id=42).
    * @return Server route that either maps to 202 ACCEPTED or the expected error codes.
    */
  def resume(Id : Long) : server.Route =  {
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Admin)){ token =>
      post {
        log.debug(s"POST /instances/$Id/resume has been called")
        handler.handleResume(Id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot resume id $Id, that id was not found.")
            complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $Id not found.")}
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot resume id $Id, that instance is not running in a docker container.")
            complete{HttpResponse(StatusCodes.BadRequest, entity = s"Id $Id is not running in a docker container.")}
          case handler.OperationResult.InvalidStateForOperation =>
            log.warning(s"Cannot resume id $Id, that instance is not paused.")
            complete {HttpResponse(StatusCodes.BadRequest, entity = s"Id $Id is not paused.")}
          case handler.OperationResult.Ok =>
            complete{HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")}
          case r =>
            complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")}
        }
      }
    }
  }

  /**
    * Called to stop the instance with the specified id. The associated docker container is stopped. The Id is passed
    * as a query argument named 'Id' (so the resulting call is /stop?Id=42).
    * @return Server route that either maps to 202 ACCEPTED or the expected error codes.
    */
  def stop(Id : Long) : server.Route =  {
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Admin)){ token =>
      post {
        log.debug(s"POST /instances/$Id/stop has been called")
        handler.handleStop(Id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot stop id $Id, that id was not found.")
            complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $Id not found.")}
          case handler.OperationResult.InvalidTypeForOperation =>
            log.warning(s"Cannot stop id $Id, this component type cannot be stopped.")
            complete{HttpResponse(StatusCodes.BadRequest, entity = s"Cannot stop instance of this type.")}
          case handler.OperationResult.Ok =>
            complete{HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")}
          case r =>
            complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")}
        }
      }
    }
  }

  /**
    * Called to start the instance with the specified id. The associated docker container is started. The Id is passed
    * as a query argument named 'Id' (so the resulting call is /start?Id=42).
    * @return Server route that either maps to 202 ACCEPTED or the expected error codes.
    */
  def start() : server.Route = parameters('Id.as[Long]) { id =>
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Admin)){ token =>
      post{
        log.debug(s"POST /start?Id=$id has been called")
        handler.handleStart(id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot start id $id, that id was not found.")
            complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")}
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot start id $id, that instance is not running in a docker container.")
            complete{HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running in a docker container.")}
          case handler.OperationResult.InvalidStateForOperation =>
            log.warning(s"Cannot start id $id, that instance is not stopped.")
            complete {HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not stopped.")}
          case handler.OperationResult.Ok =>
            complete{HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")}
          case r =>
            complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")}
        }
      }
    }
  }

  /**
    * Called to delete the instance with the specified id as well as the associated docker container. The Id is passed
    * as a query argument named 'Id' (so the resulting call is /delete?Id=42).
    * @return Server route that either maps to 202 ACCEPTED or the respective error codes.
    */
  def deleteContainer() : server.Route = parameters('Id.as[Long]) { id =>
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Admin)){ token =>
      post{
        log.debug(s"POST /delete?Id=$id has been called")
        handler.handleDeleteContainer(id) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot delete id $id, that id was not found.")
            complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")}
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot delete id $id, that instance is not running in a docker container.")
            complete{HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running in a docker container.")}
          case handler.OperationResult.InvalidStateForOperation =>
            log.warning(s"Cannot delete id $id, that instance is still running.")
            complete {HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not stopped.")}
          case handler.OperationResult.Ok =>
            complete{HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")}
          case handler.OperationResult.InternalError =>
            complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error")}
          case handler.OperationResult.BlockingDependency =>
            complete{HttpResponse(StatusCodes.BadRequest, entity = s"Cannot delete this instance, other running instances are depending on it.")}
        }
      }
    }
  }

  /**
    * Called to assign a new instance dependency to the instance with the specified id. Both the ids of the instance and
    * the specified dependency are passed as query arguments named 'Id' and 'assignedInstanceId' resp. (so the resulting
    * call is /assignInstance?Id=42&assignedInstanceId=43). Will update the dependency in DB and than restart the container.
    * @return Server route that either maps to 202 ACCEPTED or the respective error codes
    */
  def assignInstance() : server.Route = parameters('Id.as[Long], 'AssignedInstanceId.as[Long]) { (id, assignedInstanceId) =>
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Admin)){ token =>
      post {
        log.debug(s"POST /assignInstance?Id=$id&assignedInstanceId=$assignedInstanceId has been called")

        handler.handleInstanceAssignment(id, assignedInstanceId) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot assign $assignedInstanceId to $id, one or more ids not found.")
            complete{HttpResponse(StatusCodes.NotFound, entity = s"Cannot assign instance, at least one of the ids $id / $assignedInstanceId was not found.")}
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot assign $assignedInstanceId to $id, $id is no docker container.")
            complete{HttpResponse(StatusCodes.BadRequest,entity = s"Cannot assign instance, $id is no docker container.")}
          case handler.OperationResult.InvalidTypeForOperation =>
            log.warning(s"Cannot assign $assignedInstanceId to $id, incompatible types.")
            complete{HttpResponse(StatusCodes.BadRequest,entity = s"Cannot assign $assignedInstanceId to $id, incompatible types.")}
          case handler.OperationResult.Ok =>
            complete{HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")}
          case x =>
            complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Unexpected operation result $x")}
        }
      }
    }
  }

  /**
    * Called to get a list of links from the instance with the specified id. The id is passed as query argument named
    * 'Id' (so the resulting call is /linksFrom?Id=42).
    * @return Server route that either maps to 200 OK (and the list of links as content), or the respective error code.
    */
  def linksFrom(Id : Long) : server.Route =  {
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.User)){ token =>
      get {
        log.debug(s"GET /instances/$Id/linksFrom has been called.")

        handler.handleGetLinksFrom(Id) match {
          case Success(linkList) =>
            complete{linkList}
          case Failure(ex) =>
            log.warning(s"Failed to get links from $Id with message: ${ex.getMessage}")
            complete{HttpResponse(StatusCodes.NotFound, entity = s"Failed to get links from $Id, that id is not known.")}
        }
      }
    }
  }

  /**
    * Called to get a list of links to the instance with the specified id. The id is passed as query argument named
    * 'Id' (so the resulting call is /linksTo?Id=42).
    * @return Server route that either maps to 200 OK (and the list of links as content), or the respective error code.
    */
  def linksTo(Id : Long) : server.Route =  {
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.User)){ token =>
      get {
        log.debug(s"GET instances/$Id/linksTo has been called.")

        handler.handleGetLinksTo(Id) match {
          case Success(linkList) =>
            complete{linkList}
          case Failure(ex) =>
            log.warning(s"Failed to get links to $Id with message: ${ex.getMessage}")
            complete{HttpResponse(StatusCodes.NotFound, entity = s"Failed to get links to $Id, that id is not known.")}
        }
      }

    }
  }

  /**
    * Called to get the whole network graph of the current registry. Contains a list of all instances and all links
    * currently registered.
    * @return Server route that maps to 200 OK and the current InstanceNetwork as content.
    */
  def network() : server.Route = {
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.User)){ token =>
      get {
        log.debug(s"GET /network has been called.")
        complete{handler.handleGetNetwork().toJson}
      }
    }
  }

  /**
    * Called to add a generic label to the instance with the specified id. The Id and label are passed as query arguments
    * named 'Id' and 'Label', resp. (so the resulting call is /addLabel?Id=42&Label=private)
    * @return Server route that either maps to 200 OK or the respective error codes.
    */
  def addLabel() : server.Route = parameters('Id.as[Long], 'Label.as[String]){ (id, label) =>
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Admin)){ token =>
      post {
        log.debug(s"POST /addLabel?Id=$id&Label=$label has been called.")
        handler.handleAddLabel(id, label) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot add label $label to $id, id not found.")
            complete{HttpResponse(StatusCodes.NotFound, entity = s"Cannot add label, id $id not found.")}
          case handler.OperationResult.InternalError =>
            log.warning(s"Error while adding label $label to $id: Label exceeds character limit.")
            complete{HttpResponse(StatusCodes.BadRequest,
              entity = s"Cannot add label to $id, label exceeds character limit of ${Registry.configuration.maxLabelLength}")}
          case handler.OperationResult.Ok =>
            log.info(s"Successfully added label $label to instance with id $id.")
            complete("Successfully added label")
        }
      }
    }
  }

  /**
    * Called to run a command in a  docker container. The Id an Command is the required parameter there are other optional parameter can be passed
    * a query with required parameter Command and Id (so the resulting call is /command?Id=42&Command=ls).
    * @return Server route that either maps to 200 Ok or the respective error codes.
    */
  def runCommandInContainer() : server.Route = parameters('Id.as[Long], 'Command.as[String],
    'AttachStdin.as[Boolean].?, 'AttachStdout.as[Boolean].?,
    'AttachStderr.as[Boolean].?,'DetachKeys.as[String].?, 'Privileged.as[Boolean].?,'Tty.as[Boolean].?, 'User.as[String].?
    ) { (id, command, attachStdin, attachStdout, attachStderr, detachKeys, privileged, tty, user) =>
    authenticateOAuth2[AccessToken]("Secure Site", AuthProvider.authenticateOAuthRequire(_, userType = UserType.Admin)){ token =>
      post {
        log.debug(s"POST /command has been called")
        handler.handleCommand(id, command, attachStdin, attachStdout, attachStderr, detachKeys, privileged, tty, user) match {
          case handler.OperationResult.IdUnknown =>
            log.warning(s"Cannot run command $command to $id, id not found.")
            complete{HttpResponse(StatusCodes.NotFound, entity = s"Cannot run command, id $id not found.")}
          case handler.OperationResult.NoDockerContainer =>
            log.warning(s"Cannot run command $command to $id, $id is no docker container.")
            complete{HttpResponse(StatusCodes.BadRequest,entity = s"Cannot run command, $id is no docker container.")}
          case handler.OperationResult.Ok =>
            complete{HttpResponse(StatusCodes.OK)}
          case r =>
            complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")}
        }
      }
    }
  }

  /**
    * Creates a WebSocketConnection that streams events that are issued by the registry to all connected clients.
    * @return Server route that maps to the WebSocketConnection
    */
  def streamEvents() : server.Route = {
    handleWebSocketMessages{
      //Flush pending messages from publisher
      Source.fromPublisher(handler.eventPublisher).to(Sink.ignore).run()
      //Create flow from publisher
      Flow[Message]
        .map{
          case TextMessage.Strict(msg: String) => msg
          case _ => println("Ignored non-text message.")
        }
        .via(
          Flow.fromSinkAndSource(Sink.foreach(println), Source.fromPublisher(handler.eventPublisher)
        .map(event => event.toJson(eventFormat).toString))
        )
        .map{msg: String => TextMessage.Strict(msg + "\n")}
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


}

