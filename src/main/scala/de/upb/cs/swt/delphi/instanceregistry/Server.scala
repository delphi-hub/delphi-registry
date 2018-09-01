package de.upb.cs.swt.delphi.instanceregistry

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.{model, server}
import akka.http.scaladsl.model.{HttpEntity, HttpResponse, ResponseEntity, StatusCodes}
import akka.http.scaladsl.server.HttpApp
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.util.Timeout
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType
import io.swagger.client.model.{Instance, JsonSupport}

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration


/**
  * Web server configuration for Instance Resgistry API.
  */
object Server extends HttpApp with JsonSupport with AppLogging {

  private val configuration = new Configuration()
  //Default ES instance for testing
  private val instances = mutable.HashSet (Instance(Some(0), Some("elasticsearch://localhost"), Some(9200), Some("Default ElasticSearch Instance"), Some(ComponentType.ElasticSearch)))

  implicit val system = ActorSystem("delphi-registry")
  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(5, TimeUnit.SECONDS)

  override def routes =
    path("register") {entity(as[String]) { jsonString => addInstance(jsonString) }} ~
      path("deregister") { deleteInstance(Long) } ~
      path("instances" ) { fetchInstance("Crawler") } ~
      path("numberOfInstances" ) { numberOfInstances("Crawler") } ~
      path("matchingInstance" ) { getMatchingInstance()} ~
      path("matchingResult" ) {matchInstance}


   def addInstance(Instance: String) = {
    post
    {
      Await.result(Unmarshal(Instance).to[Instance] map {paramInstance =>
        val name = paramInstance.name.getOrElse("None")
        val newID : Long = {
          if(instances.isEmpty){
              0L
          }
          else{
            (instances map( instance => instance.iD.get) max) + 1L
          }
        }

        val instanceToRegister = new Instance(iD = Some(newID), iP = paramInstance.iP, portnumber = paramInstance.portnumber, name = paramInstance.name, componentType = paramInstance.componentType)

        instances += instanceToRegister
        log.info(s"Instance with name $name registered, ID $newID assigned.")

        complete {HttpResponse(StatusCodes.OK, entity = newID.toString()) }
      } recover {case ex =>
        log.warning(s"Failed to read registering instance, exception: $ex")
        complete(HttpResponse(StatusCodes.InternalServerError, entity = "Failed to unmarshal parameter."))
      }, Duration.Inf)
    }
  }

   def deleteInstance(InstanceID: Object) = {
    post {
      complete {"Delete Instance"}
    }
  }
  def fetchInstance (componentType: String)=
    get { complete("Fetch Specific Instance")
    }

  def numberOfInstances(componentType: String) = {
    get {
        complete{ "Number of Instances"}
    }
  }

  def getMatchingInstance() : server.Route = parameters('ComponentType.as[String]){ compTypeString =>
    get{
      val compType : Option[ComponentType] = ComponentType.values.find(v => v.toString() == compTypeString).map(v => Some(v)).getOrElse(None)
      log.info(s"Looking for instance of type ${compType.getOrElse("None")} ...")
      val matchingInstances = instances filter {instance => instance.componentType == compType}
      if(matchingInstances.isEmpty){
        log.warning(s"Could not find matching instance for type $compType .")
        complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not find matching instance for type $compType"))
      }
      else {
        val matchedInstance = matchingInstances.iterator.next()
        Await.result(Marshal(matchedInstance).to[ResponseEntity] map {entity =>
          log.info(s"Matched to $matchedInstance.")
          complete(HttpResponse(StatusCodes.OK, entity = entity))
        } recover {case ex =>
          log.warning(s"Failed to serialize matched instance, exception: $ex")
          complete(HttpResponse(StatusCodes.InternalServerError, entity = "Failed to serialize return value"))
        }, Duration.Inf)

      }

    }
  }

  def matchInstance = {
    get {
      complete {"Match Instance and Return Boolean if matched"
      }
    }
  }

  def main(args: Array[String]): Unit = {
    val configuration = new Configuration()
    Server.startServer(configuration.bindHost, configuration.bindPort)
    system.terminate()
  }


}


