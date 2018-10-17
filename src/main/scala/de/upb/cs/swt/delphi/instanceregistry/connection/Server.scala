package de.upb.cs.swt.delphi.instanceregistry.connection

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
object Server extends HttpApp with InstanceJsonSupport with EventJsonSupport with AppLogging {

  implicit val system : ActorSystem = Registry.system
  implicit val materializer : ActorMaterializer = ActorMaterializer()
  implicit val ec : ExecutionContext = system.dispatcher

  private val handler : RequestHandler = Registry.requestHandler

  override def routes : server.Route =
      /****************BASIC OPERATIONS****************/
      path("register") {entity(as[String]) { jsonString => register(jsonString) }} ~
      path("deregister") { deregister() } ~
      path("instances") { fetchInstancesOfType() } ~
      path("numberOfInstances") { numberOfInstances() } ~
      path("matchingInstance") { matchingInstance()} ~
      path("matchingResult") { matchInstance()} ~
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
      /****************EVENT OPERATIONS****************/
      path("events") { streamEvents()}

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

  def matchingInstance() : server.Route = parameters('ComponentType.as[String]){ compTypeString =>
    get{
      log.debug(s"GET /matchingInstance?ComponentType=$compTypeString has been called")

      val compType : ComponentType = ComponentType.values.find(v => v.toString == compTypeString).orNull
      log.info(s"Looking for instance of type $compType ...")

      if(compType != null){
        handler.getMatchingInstanceOfType(compType) match {
          case Success(matchedInstance) =>
            log.info(s"Matched to $matchedInstance.")
            complete(matchedInstance.toJson(instanceFormat))
          case Failure(x) =>
            log.warning(s"Could not find matching instance for type $compType, message was ${x.getMessage}.")
            complete(HttpResponse(StatusCodes.NotFound, entity = s"Could not find matching instance for type $compType"))
        }
      } else {
        log.error(s"Failed to deserialize parameter string $compTypeString to ComponentType.")
        complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not deserialize parameter string $compTypeString to ComponentType"))
      }
    }
  }

  def matchInstance() : server.Route = parameters('Id.as[Long], 'MatchingSuccessful.as[Boolean]){ (id, matchingResult) =>
    post {
      log.debug(s"POST /matchingResult?Id=$id&MatchingSuccessful=$matchingResult has been called")

      handler.handleMatchingResult(id, matchingResult) match {
        case handler.OperationResult.IdUnknown =>
          log.warning(s"Cannot apply matching result for id $id, that id was not found.")
          complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")}
        case handler.OperationResult.Ok =>
          complete{s"Matching result $matchingResult processed."}
      }
    }
  }

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

  def stop() : server.Route = parameters('Id.as[Long]) { id =>
    post {
      log.debug(s"POST /stop?Id=$id has been called")
      handler.handleStop(id) match {
        case handler.OperationResult.IdUnknown =>
          log.warning(s"Cannot stop id $id, that id was not found.")
          complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $id not found.")}
        case handler.OperationResult.NoDockerContainer =>
          log.warning(s"Cannot stop id $id, that instance is not running in a docker container.")
          complete{HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not running in a docker container.")}
        case handler.OperationResult.Ok =>
          complete{HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")}
        case r =>
          complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error, unknown operation result $r")}
      }
    }
  }

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
          log.warning(s"Cannot delete id $id, that instance is not stopped.")
          complete {HttpResponse(StatusCodes.BadRequest, entity = s"Id $id is not stopped.")}
        case handler.OperationResult.Ok =>
          complete{HttpResponse(StatusCodes.Accepted, entity = "Operation accepted.")}
        case handler.OperationResult.InternalError =>
          complete{HttpResponse(StatusCodes.InternalServerError, entity = s"Internal server error")}
      }
    }
  }

  def streamEvents() : server.Route = {
    handleWebSocketMessages{
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


