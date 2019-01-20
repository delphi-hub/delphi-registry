package de.upb.cs.swt.delphi.instanceregistry.Docker

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.stream.Materializer

import scala.concurrent.Future

object DockerConnection {


  def fromEnvironment()(implicit system: ActorSystem, materializer: Materializer): DockerConnection = {
    def env(key: String): Option[String] = sys.env.get(key).filter(_.nonEmpty)

    val host = env("DELPHI_DOCKER_HOST").getOrElse {
      "http://localhost:9095"
    }
    DockerHttpConnection(host)
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

case class DockerHttpConnection(
                                 baseUri: Uri,
                               )(implicit val system: ActorSystem, val materializer: Materializer)
  extends DockerConnection {
  override def sendRequest(request: HttpRequest): Future[HttpResponse] = {
    Http(system).singleRequest(request)
  }
}

