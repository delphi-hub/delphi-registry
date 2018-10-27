package de.upb.cs.swt.delphi.instanceregistry.connection

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import de.upb.cs.swt.delphi.instanceregistry.AppLogging
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.Instance

import scala.concurrent.{ExecutionContext, Future}

object RestClient extends AppLogging{

  def executePost(requestUri: String)
                 (implicit system: ActorSystem, ec: ExecutionContext): Future[HttpResponse] =
    Http().singleRequest(HttpRequest(HttpMethods.POST, uri = requestUri))


  def getUri(instance: Instance) : String = {
    "http://" + instance.host + ":" + instance.portNumber.toString
  }
}
