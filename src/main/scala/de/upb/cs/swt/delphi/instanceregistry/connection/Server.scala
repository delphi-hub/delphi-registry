package de.upb.cs.swt.delphi.instanceregistry.connection

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.HttpApp
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model._
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Registry, RequestHandler}
import spray.json._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


/**
  * Web server configuration for Instance Registry API.
  */
object Server extends HttpApp
  with InstanceJsonSupport
  with EventJsonSupport
  with InstanceLinkJsonSupport
  with InstanceNetworkJsonSupport
  with AppLogging {

  implicit val system : ActorSystem = Registry.system
  implicit val materializer : ActorMaterializer = ActorMaterializer()
  implicit val ec : ExecutionContext = system.dispatcher

  private val handler : RequestHandler = Registry.requestHandler

  //Routes that map http endpoints to methods in this object
  override def routes : server.Route =
      /****************BASIC OPERATIONS****************/
      path("register") {entity(as[String]) { jsonString => register(jsonString) }} ~
      path("deregister") { deregister() } ~
      path("instances") { fetchInstancesOfType() } ~
      path("instance") { retrieveInstance() } ~
      path("numberOfInstances") { numberOfInstances() } ~
      path("matchingInstance") { matchingInstance()} ~
      path("matchingResult") { matchInstance()} ~
      path("eventList") { eventList()} ~
      path("linksFrom") { linksFrom()} ~
      path("linksTo") { linksTo()} ~
      path("network") { network()} ~
      path("addLabel") { addLabel()} ~
      /****************DOCKER OPERATIONS****************/
      path("deploy") { deployContainer()} ~
      path("reportStart") { reportStart()} ~
      path("reportStop") { reportStop()} ~
      path("reportFailure") { reportFailure()} ~
      path("pause") { pause()} ~
      path("resume") { resume()} ~
      path("stop") { stop()} ~
      path("start") { start()} ~
      path("delete") { deleteContainer()} ~
      path("command") { runCommandInContainer()} ~
      /****************EVENT OPERATIONS****************/
      path("events") { streamEvents()}


  /**
    * Registers a new instance at the registry. This endpoint is intended for instances that are not running inside
    * a docker container, as the Id, DockerId and InstanceState are being ignored.
    * @param InstanceString String containing the serialized instance that is registering
    * @return Server route that either maps to a 200 OK response if successful, or to the respective error codes
    */
  def register(InstanceString: String) : server.Route = {
    post
    {
      log.debug(s"POST /register has been called, parameter is: $InstanceString")

      try {
        val paramInstance : Instance = InstanceString.parseJson.convertTo[Instance](instanceFormat)
        handler.handleRegister(paramInstance) match {
          case Success(id) =>
            complete{id.toString}
          case Failure(_) =>  complete(HttpResponse(StatusCodes.InternalServerError, entity = "An internal server error occurred."))
        }
      } catch {
        case dx : DeserializationException =>
          log.error(dx, "Deserialization exception")
          complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not deserialize parameter instance with message ${dx.getMessage}."))
        case _ : Exception =>  complete(HttpResponse(StatusCodes.InternalServerError, entity = "An internal server error occurred."))
      }
    }
  }

  /**
    * Removes an instance. The id of the instance that is calling deregister must be passed as an query argument named
    * 'Id' (so the call is /deregister?Id=42). This endpoint is intended for instances that are not running inside
    * a docker container, as the respective instance will be permanently deleted from the registry.
    * @return Server route that either maps to a 200 OK response if successful, or to the respective error codes.
    */
  def deregister() : server.Route = parameters('Id.as[Long]){ Id =>
    post {
      log.debug(s"POST /deregister?Id=$Id has been called")

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

  /**
    * Returns a list of instances with the specified ComponentType. The ComponentType must be passed as an query argument
    * named 'ComponentType' (so the call is /instances?ComponentType=Crawler).
    * @return Server route that either maps to a 200 OK response containing the list of instances, or the resp. error codes.
    */
  def fetchInstancesOfType () : server.Route = parameters('ComponentType.as[String]) { compTypeString =>
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

  /**
    * Returns the number of instances for the specified ComponentType. The ComponentType must be passed as an query
    * argument named 'ComponentType' (so the call is /numberOfInstances?ComponentType=Crawler).
    * @return Server route that either maps to a 200 OK response containing the number of instance, or the resp. error codes.
    */
  def numberOfInstances() : server.Route = parameters('ComponentType.as[String]) { compTypeString =>
    get {
      log.debug(s"GET /numberOfInstances?ComponentType=$compTypeString has been called")

      val compType : ComponentType = ComponentType.values.find(v => v.toString == compTypeString).orNull

      if(compType != null) {
        complete{handler.getNumberOfInstances(compType).toString()}
      } else {
        log.error(s"Failed to deserialize parameter string $compTypeString to ComponentType.")
        complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not deserialize parameter string $compTypeString to ComponentType"))
      }
    }
  }

  /**
    * Returns an instance with the specified id. Id is passed as query argument named 'Id' (so the resulting call is
    * /instance?Id=42)
    * @return Server route that either maps to 200 OK and the respective instance as entity, or 404.
    */
  def retrieveInstance() : server.Route = parameters('Id.as[Long]) { id =>
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

  /**
    * Returns an instance of the specified ComponentType that can be used to resolve dependencies. The ComponentType must
    * be passed as an query argument named 'ComponentType' (so the call is /matchingInstance?ComponentType=Crawler).
    * @return Server route that either maps to 200 OK response containing the instance, or the resp. error codes.
    */
  def matchingInstance() : server.Route = parameters('Id.as[Long], 'ComponentType.as[String]){ (id, compTypeString) =>
    get{
      log.debug(s"GET /matchingInstance?Id=$id&ComponentType=$compTypeString has been called")

      val compType : ComponentType = ComponentType.values.find(v => v.toString == compTypeString).orNull
      log.info(s"Looking for instance of type $compType ...")

      if(compType != null){
        handler.getMatchingInstanceOfType(id, compType) match {
          case Success(matchedInstance) =>
            log.info(s"Matched request from $id to $matchedInstance.")
            handler.handleInstanceLinkCreated(id, matchedInstance.id.get) match {
              case handler.OperationResult.IdUnknown =>
                log.warning(s"Could not handle the creation of instance link, the id $id seems to be invalid.")
                complete(HttpResponse(StatusCodes.NotFound, entity = s"Could not find instance with id $id."))
              case handler.OperationResult.InvalidTypeForOperation =>
                log.warning(s"Could not handle the creation of instance link, incompatible types found.")
                complete{HttpResponse(StatusCodes.BadRequest, entity = s"Invalid dependency type $compType")}
              case handler.OperationResult.Ok =>
                complete(matchedInstance.toJson(instanceFormat))
              case handler.OperationResult.InternalError =>
                complete{HttpResponse(StatusCodes.InternalServerError, entity = s"An internal error occurred")}
            }
          case Failure(x) =>
            log.warning(s"Could not find matching instance for type $compType, message was ${x.getMessage}.")
            complete(HttpResponse(StatusCodes.NotFound, entity = s"Could not find matching instance of type $compType for instance with id $id."))
        }
      } else {
        log.error(s"Failed to deserialize parameter string $compTypeString to ComponentType.")
        complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not deserialize parameter string $compTypeString to ComponentType"))
      }
    }
  }

  /**
    * Applies a matching result to the instance with the specified id. The matching result and id are passed as query
    * parameters named 'Id' and 'MatchingSuccessful' (so the call is /matchingResult?Id=42&MatchingSuccessful=True).
    * @return Server route that either maps to 200 OK or to the respective error codes
    */
  def matchInstance() : server.Route = parameters('CallerId.as[Long], 'MatchedInstanceId.as[Long], 'MatchingSuccessful.as[Boolean]){ (callerId, matchedInstanceId, matchingResult) =>
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

  /**
    * Returns a list of registry events that are associated to the instance with the specified id. The id is passed as
    * query argument named 'Id' (so the resulting call is /eventList?Id=42).
    * @return Server route mapping to either 200 OK and the list of event, or the resp. error codes.
    */
  def eventList() : server.Route = parameters('Id.as[Long]){id =>
      get {
        log.debug(s"GET /eventList?Id=$id has been called")

        handler.getEventList(id) match {
          case Success(list) => complete{list}
          case Failure(_) => complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")}
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

  /**
    * Called to report that the instance with the specified id was started successfully. The Id is passed as query
    * parameter named 'Id' (so the resulting call is /reportStart?Id=42)
    * @return Server route that either maps to 200 OK or the respective error codes
    */
  def reportStart() : server.Route = parameters('Id.as[Long]) {id =>
    post{
      handler.handleReportStart(id) match {
        case handler.OperationResult.IdUnknown =>
          log.warning(s"Cannot report start for id $id, that id was not found.")
          complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")}
        case handler.OperationResult.NoDockerContainer =>
          log.warning(s"Cannot report start for id $id, that instance is not running in a docker container.")
          complete{HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running in a docker container.")}
        case handler.OperationResult.Ok =>
          complete{"Report successfully processed."}
        case r =>
          complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")}
      }

    }
  }

  /**
    * Called to report that the instance with the specified id was stopped successfully. The Id is passed as query
    * parameter named 'Id' (so the resulting call is /reportStop?Id=42)
    * @return Server route that either maps to 200 OK or the respective error codes
    */
  def reportStop() : server.Route = parameters('Id.as[Long]) {id =>
    post{
      handler.handleReportStop(id) match {
        case handler.OperationResult.IdUnknown =>
          log.warning(s"Cannot report start for id $id, that id was not found.")
          complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")}
        case handler.OperationResult.NoDockerContainer =>
          log.warning(s"Cannot report start for id $id, that instance is not running in a docker container.")
          complete{HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running in a docker container.")}
        case handler.OperationResult.Ok =>
          complete{"Report successfully processed."}
        case r =>
          complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")}
      }
    }
  }

  /**
    * Called to report that the instance with the specified id encountered a failure. The Id is passed as query
    * parameter named 'Id' (so the resulting call is /reportFailure?Id=42)
    * @return Server route that either maps to 200 OK or the respective error codes
    */
  def reportFailure() : server.Route = parameters('Id.as[Long], 'ErrorLog.as[String].?) {(id, errorLog) =>
    post{
      if(errorLog.isEmpty){
        log.debug(s"POST /reportFailure?Id=$id has been called")
      } else {
        log.debug(s"POST /reportFailure?Id=$id&ErrorLog=${errorLog.get} has been called")
      }

      handler.handleReportFailure(id, errorLog) match {
        case handler.OperationResult.IdUnknown =>
          log.warning(s"Cannot report failure for id $id, that id was not found.")
          complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")}
        case handler.OperationResult.NoDockerContainer =>
          log.warning(s"Cannot report failure for id $id, that instance is not running in a docker container.")
          complete{HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running in a docker container.")}
        case handler.OperationResult.Ok =>
          complete{"Report successfully processed."}
        case r =>
          complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")}
      }
    }
  }

  /**
    * Called to pause the instance with the specified id. The associated docker container is paused. The Id is passed
    * as a query argument named 'Id' (so the resulting call is /pause?Id=42).
    * @return Server route that either maps to 202 ACCEPTED or the expected error codes.
    */
  def pause() : server.Route = parameters('Id.as[Long]) { id =>
    post{
      log.debug(s"POST /pause?Id=$id has been called")
      handler.handlePause(id) match {
        case handler.OperationResult.IdUnknown =>
          log.warning(s"Cannot pause id $id, that id was not found.")
          complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")}
        case handler.OperationResult.NoDockerContainer =>
          log.warning(s"Cannot pause id $id, that instance is not running in a docker container.")
          complete{HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running in a docker container.")}
        case handler.OperationResult.InvalidStateForOperation =>
          log.warning(s"Cannot pause id $id, that instance is not running.")
          complete{HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running .")}
        case handler.OperationResult.Ok =>
          complete{HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")}
        case r =>
          complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")}
      }
    }
  }

  /**
    * Called to resume the instance with the specified id. The associated docker container is resumed. The Id is passed
    * as a query argument named 'Id' (so the resulting call is /resume?Id=42).
    * @return Server route that either maps to 202 ACCEPTED or the expected error codes.
    */
  def resume() : server.Route = parameters('Id.as[Long]) { id =>
    post {
      log.debug(s"POST /resume?Id=$id has been called")
      handler.handleResume(id) match {
        case handler.OperationResult.IdUnknown =>
          log.warning(s"Cannot resume id $id, that id was not found.")
          complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")}
        case handler.OperationResult.NoDockerContainer =>
          log.warning(s"Cannot resume id $id, that instance is not running in a docker container.")
          complete{HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running in a docker container.")}
        case handler.OperationResult.InvalidStateForOperation =>
          log.warning(s"Cannot resume id $id, that instance is not paused.")
          complete {HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not paused.")}
        case handler.OperationResult.Ok =>
          complete{HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")}
        case r =>
          complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")}
      }
    }
  }

  /**
    * Called to stop the instance with the specified id. The associated docker container is stopped. The Id is passed
    * as a query argument named 'Id' (so the resulting call is /stop?Id=42).
    * @return Server route that either maps to 202 ACCEPTED or the expected error codes.
    */
  def stop() : server.Route = parameters('Id.as[Long]) { id =>
    post {
      log.debug(s"POST /stop?Id=$id has been called")
      handler.handleStop(id) match {
        case handler.OperationResult.IdUnknown =>
          log.warning(s"Cannot stop id $id, that id was not found.")
          complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")}
        case handler.OperationResult.InvalidTypeForOperation =>
          log.warning(s"Cannot stop id $id, this component type cannot be stopped.")
          complete{HttpResponse(StatusCodes.BadRequest, entity = s"Cannot stop instance of this type.")}
        case handler.OperationResult.Ok =>
          complete{HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")}
        case r =>
          complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")}
      }
    }
  }

  /**
    * Called to start the instance with the specified id. The associated docker container is started. The Id is passed
    * as a query argument named 'Id' (so the resulting call is /start?Id=42).
    * @return Server route that either maps to 202 ACCEPTED or the expected error codes.
    */
  def start() : server.Route = parameters('Id.as[Long]) { id =>
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

  /**
    * Called to delete the instance with the specified id as well as the associated docker container. The Id is passed
    * as a query argument named 'Id' (so the resulting call is /delete?Id=42).
    * @return Server route that either maps to 202 ACCEPTED or the respective error codes.
    */
  def deleteContainer() : server.Route = parameters('Id.as[Long]) { id =>
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
      }
    }
  }

  /**
    * Called to assign a new instance dependency to the instance with the specified id. Both the ids of the instance and
    * the specified dependency are passed as query arguments named 'Id' and 'assignedInstanceId' resp. (so the resulting
    * call is /assignInstance?Id=42&assignedInstanceId=43). Will update the dependency in DB and than restart the container.
    * @return Server route that either maps to 202 ACCEPTED or the respective error codes
    */
  def assignInstance() : server.Route = parameters('Id.as[Long], 'assignedInstanceId.as[Long]) { (id, assignedInstanceId) =>
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

  /**
    * Called to get a list of links from the instance with the specified id. The id is passed as query argument named
    * 'Id' (so the resulting call is /linksFrom?Id=42).
    * @return Server route that either maps to 200 OK (and the list of links as content), or the respective error code.
    */
  def linksFrom() : server.Route = parameters('Id.as[Long]) { id =>
    get {
      log.debug(s"GET /linksFrom?Id=$id has been called.")

      handler.handleGetLinksFrom(id) match {
        case Success(linkList) =>
          complete{linkList}
        case Failure(ex) =>
          log.warning(s"Failed to get links from $id with message: ${ex.getMessage}")
          complete{HttpResponse(StatusCodes.NotFound, entity = s"Failed to get links from $id, that id is not known.")}
      }
    }
  }

  /**
    * Called to get a list of links to the instance with the specified id. The id is passed as query argument named
    * 'Id' (so the resulting call is /linksTo?Id=42).
    * @return Server route that either maps to 200 OK (and the list of links as content), or the respective error code.
    */
  def linksTo() : server.Route = parameters('Id.as[Long]) {id =>
    get {
      log.debug(s"GET /linksTo?Id=$id has been called.")

      handler.handleGetLinksTo(id) match {
        case Success(linkList) =>
          complete{linkList}
        case Failure(ex) =>
          log.warning(s"Failed to get links to $id with message: ${ex.getMessage}")
          complete{HttpResponse(StatusCodes.NotFound, entity = s"Failed to get links to $id, that id is not known.")}
      }
    }
  }

  /**
    * Called to get the whole network graph of the current registry. Contains a list of all instances and all links
    * currently registered.
    * @return Server route that maps to 200 OK and the current InstanceNetwork as content.
    */
  def network() : server.Route = {
    get {
      log.debug(s"GET /network has been called.")
      complete{handler.handleGetNetwork().toJson(InstanceNetworkFormat)}
    }
  }

  /**
    * Called to add a generic label to the instance with the specified id. The Id and label are passed as query arguments
    * named 'Id' and 'Label', resp. (so the resulting call is /addLabel?Id=42&Label=private)
    * @return Server route that either maps to 200 OK or the respective error codes.
    */
  def addLabel() : server.Route = parameters('Id.as[Long], 'Label.as[String]){ (id, label) =>
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

  /**
    * Called to run a command in a  docker container. The Id an Command is the required parameter there are other optional parameter can be passed
    * a query with required parameter Command and Id (so the resulting call is /delete?Id=42&Command=ls).
    * @return Server route that either maps to 200 Ok or the respective error codes.
    */
  def runCommandInContainer() : server.Route = parameters('Id.as[Long], 'Command.as[String],
    'AttachStdin.as[Boolean].?, 'AttachStdout.as[Boolean].?,
    'AttachStderr.as[Boolean].?,'DetachKeys.as[String].?, 'Privileged.as[Boolean].?,'Tty.as[Boolean].?, 'User.as[String].?
    ) { (id, command, attachStdin, attachStdout, attachStderr, detachKeys, privileged, tty, user) =>
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


