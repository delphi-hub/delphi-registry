package de.upb.cs.swt.delphi.instanceregistry.Docker

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.stream.Materializer
import de.upb.cs.swt.delphi.instanceregistry.{Configuration, Registry}

import scala.concurrent.Future

object DockerConnection {


  def fromEnvironment(configuration: Configuration): DockerConnection = {
    DockerHttpConnection(configuration.dockerUri)
  }
}

trait DockerConnection {
  def baseUri: Uri

  def system: ActorSystem

  implicit def materializer: Materializer

  def sendRequest(request: HttpRequest): Future[HttpResponse]

  def buildUri(path: Path, query: Query = Query.Empty): Uri = {
    baseUri.copy(path = baseUri.path ++ path, rawQueryString = Some(query.toString()))

  }

}

case class DockerHttpConnection(baseUri: Uri)
  extends DockerConnection {
  override def system: ActorSystem = Registry.system
  override implicit def materializer: Materializer = Registry.materializer

  override def sendRequest(request: HttpRequest): Future[HttpResponse] = {
    Http(system).singleRequest(request)
  }
}

