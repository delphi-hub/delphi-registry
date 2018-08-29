package de.upb.cs.swt.delphi.instanceregistry

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.server.HttpApp
import akka.util.Timeout
import model.{Instance, InstanceID}


/**
  * Web server configuration for Instance Resgistry API.
  */
object Server extends HttpApp with JsonSupport with AppLogging {

  private val configuration = new Configuration()
  private val system = ActorSystem("delphi-InstnaceRegistry-API")
  implicit val timeout = Timeout(5, TimeUnit.SECONDS)

  override def routes =
    path("register") { addInstance(Instance) } ~
      path("deregister") { deleteInstance(InstanceID) } ~
      path("instances" ) { fetchInstance("Crawler") } ~
      path("numberOfInstances" ) { numberOfInstances("Crawler") } ~
      path("matchingInstance" ) { getMatchingInstance(Instance)} ~
      path("matchingResult" ) {matchInstance}


   def addInstance(Instance: Object) = {
    post
    {
      complete {"Get Instance Implementation Post Request"}


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
    post {
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


