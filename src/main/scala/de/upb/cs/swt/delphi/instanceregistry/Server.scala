package de.upb.cs.swt.delphi.instanceregistry

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.server
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.HttpApp
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.util.Timeout
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType
import io.swagger.client.model.{Instance, JsonSupport}

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration


/**
  * Web server configuration for Instance Registry API.
  */
object Server extends HttpApp with JsonSupport with AppLogging {

  //Default ES instance for testing
  private val instances = mutable.HashSet (Instance(Some(0), Some("elasticsearch://localhost"), Some(9200), Some("Default ElasticSearch Instance"), Some(ComponentType.ElasticSearch)))

  implicit val system : ActorSystem = ActorSystem("delphi-registry")
  implicit val materializer : ActorMaterializer = ActorMaterializer()
  implicit val ec : ExecutionContext = system.dispatcher
  implicit val timeout : Timeout = Timeout(5, TimeUnit.SECONDS)

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
      Await.result(Unmarshal(InstanceString).to[Instance] map {paramInstance =>
        val name = paramInstance.name.getOrElse("None")
        val newID : Long = {
          if(instances.isEmpty){
              0L
          }
          else{
            (instances map( instance => instance.iD.get) max) + 1L
          }
        }

        val instanceToRegister = Instance(iD = Some(newID), iP = paramInstance.iP, portnumber = paramInstance.portnumber, name = paramInstance.name, componentType = paramInstance.componentType)

        instances += instanceToRegister
        log.info(s"Instance with name $name registered, ID $newID assigned.")

        complete {newID.toString()}
      } recover {case ex =>
        log.warning(s"Failed to read registering instance, exception: $ex")
        complete(HttpResponse(StatusCodes.InternalServerError, entity = "Failed to unmarshal parameter."))
      }, Duration.Inf)
    }
  }

   def deleteInstance() : server.Route = parameters('Id.as[Long]){ Id =>
    post {
      log.debug(s"POST /deregister?Id=$Id has been called")

      val instanceToRemove = instances find(instance => instance.iD.get == Id)

      if(instanceToRemove.isEmpty){
        log.warning(s"Cannot remove instance with id $Id, that id is not present on the server")
        complete{HttpResponse(StatusCodes.NotFound, entity = s"Id $Id not present on the server")}
      }
      else{
        instances remove instanceToRemove.get
        log.info(s"Successfully removed instance with id $Id")
        complete {s"Successfully removed instance with id $Id"}
      }
    }
  }
  def fetchInstancesOfType () : server.Route = parameters('ComponentType.as[String]) { compTypeString =>
    get {
      log.debug(s"GET /instances?ComponentType=$compTypeString has been called")
      val compType : Option[ComponentType] = ComponentType.values.find(v => v.toString == compTypeString).map(v => Some(v)).getOrElse(None)
      val matchingInstancesList = List() ++ instances filter {instance => instance.componentType == compType}

      complete {matchingInstancesList}
    }
  }

  def numberOfInstances() : server.Route = parameters('ComponentType.as[String]) { compTypeString =>
    get {
      log.debug(s"GET /numberOfInstances?ComponentType=$compTypeString has been called")
      val compType : Option[ComponentType] = ComponentType.values.find(v => v.toString == compTypeString).map(v => Some(v)).getOrElse(None)
      val count : Int = instances count {instance => instance.componentType == compType}
      complete{count.toString()}
    }
  }

  def getMatchingInstance() : server.Route = parameters('ComponentType.as[String]){ compTypeString =>
    get{
      log.debug(s"GET /matchingInstance?ComponentType=$compTypeString has been called")
      val compType : Option[ComponentType] = ComponentType.values.find(v => v.toString == compTypeString).map(v => Some(v)).getOrElse(None)
      log.info(s"Looking for instance of type ${compType.getOrElse("None")} ...")
      val matchingInstances = instances filter {instance => instance.componentType == compType}
      if(matchingInstances.isEmpty){
        log.warning(s"Could not find matching instance for type $compType .")
        complete(HttpResponse(StatusCodes.BadRequest, entity = s"Could not find matching instance for type $compType"))
      }
      else {
        val matchedInstance = matchingInstances.iterator.next()
        log.info(s"Matched to $matchedInstance.")
        complete(matchedInstance)
      }

    }
  }

  def matchInstance() : server.Route = parameters('Id.as[Long], 'MatchingSuccessful.as[Boolean]){ (Id, MatchingResult) =>
    post {
      //TODO: Need to keep track of matching, maybe remove instances if not reachable!
      log.debug(s"POST /matchingResult?Id=$Id&MatchingSuccessful=$MatchingResult has been called")
      if(MatchingResult){
        log.info(s"Instance with Id $Id was successfully matched.")
      }
      else{
        log.warning(s"A client was not able to reach matched instance with Id $Id !")
      }
      complete {s"Matching result $MatchingResult processed."}
    }
  }

  def main(args: Array[String]): Unit = {
    val configuration = new Configuration()
    Server.startServer(configuration.bindHost, configuration.bindPort)
    system.terminate()
  }


}


