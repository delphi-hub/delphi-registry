package de.upb.cs.swt.delphi.instanceregistry

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.HttpApp
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.util.Timeout
import io.swagger.client.model.{Instance, JsonSupport}

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}


/**
  * Web server configuration for Instance Resgistry API.
  */
object Server extends HttpApp with JsonSupport with AppLogging {

  private val configuration = new Configuration()
  implicit val system = ActorSystem("delphi-registry")
  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(5, TimeUnit.SECONDS)

  override def routes =
    path("register") {entity(as[String]) { jsonString => addInstance(jsonString) }} ~
      path("deregister") { deleteInstance(Long) } ~
      path("instances" ) { fetchInstance("Crawler") } ~
      path("numberOfInstances" ) { numberOfInstances("Crawler") } ~
      path("matchingInstance" ) { getMatchingInstance(Instance)} ~
      path("matchingResult" ) {matchInstance}


   def addInstance(Instance: String) = {
    post
    {
      Await.result(Unmarshal(Instance).to[Instance] map {instance =>
        val instancename = instance.name
        log.info(s"Instance with name $instancename registered.")
        complete {"Get Instance Implementation Post Request"}
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

  def getMatchingInstance(Instance: Object) = {
    get {
        complete{"Search for a Specific Instance and Return that Instance"}

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


