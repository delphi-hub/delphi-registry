package de.upb.cs.swt.delphi.instanceregistry


import akka.actor.ActorSystem
import akka.http.scaladsl.server
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.HttpApp
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType
import io.swagger.client.model.{Instance, JsonSupport}

import scala.concurrent.ExecutionContext
import spray.json._

import scala.util.{Failure, Success}


/**
  * Web server configuration for Instance Registry API.
  */
object Server extends HttpApp with JsonSupport with AppLogging {

  implicit val system : ActorSystem = Registry.system
  implicit val materializer : ActorMaterializer = ActorMaterializer()
  implicit val ec : ExecutionContext = system.dispatcher

  private val handler : RequestHandler = Registry.requestHandler

  override def routes : server.Route =
      path("register") {entity(as[String]) { jsonString => addInstance(jsonString) }} ~
      path("deregister") { deleteInstance() } ~
      path("instances" ) { fetchInstancesOfType() } ~
      path("numberOfInstances" ) { numberOfInstances() } ~
      path("matchingInstance" ) { getMatchingInstance()} ~
      path("matchingResult" ) {matchInstance()}


   def addInstance(InstanceString: String) : server.Route = {
    post
    {
      log.debug(s"POST /register has been called, parameter is: $InstanceString")

      try {
        val paramInstance : Instance = InstanceString.parseJson.convertTo[Instance](instanceFormat)
        handler.registerNewInstance(paramInstance) match {
          case Success(id) => complete{id.toString}
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

   def deleteInstance() : server.Route = parameters('Id.as[Long]){ Id =>
    post {
      log.debug(s"POST /deregister?Id=$Id has been called")

      handler.removeInstance(Id) match {
        case Success(_) =>
          log.info(s"Successfully removed instance with id $Id")
          complete {s"Successfully removed instance with id $Id"}
        case Failure(x) =>
          log.error(x, s"Cannot remove instance with id $Id, that id is not known to the server.")
          complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $Id not known to the server")}
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

  def getMatchingInstance() : server.Route = parameters('ComponentType.as[String]){ compTypeString =>
    get{
      log.debug(s"GET /matchingInstance?ComponentType=$compTypeString has been called")

      val compType : ComponentType = ComponentType.values.find(v => v.toString == compTypeString).orNull
      log.info(s"Looking for instance of type $compType ...")

      if(compType != null){
        handler.getMatchingInstanceOfType(compType) match {
          case Success(matchedInstance) =>
            log.info(s"Matched to $matchedInstance.")
            complete(matchedInstance)
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

      handler.applyMatchingResult(id, matchingResult) match {
        case Success(_) => complete{s"Matching result $matchingResult processed."}
        case Failure(x) =>
          log.warning(s"Could not process matching result, exception was: ${x.getMessage}")
          complete(HttpResponse(StatusCodes.NotFound, entity = s"Could not process matching result, id $id was not found."))
      }
    }
  }

}


