package de.upb.cs.swt.delphi.instanceregistry.Docker

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.unmarshalling.Unmarshal
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Registry}
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success


class ContainerCommands(connection: DockerConnection) extends JsonSupport with Commands with AppLogging{

  import connection._

  implicit val system: ActorSystem = Registry.system
  protected val containersPath = Path / "containers"

  def list(
            all: Boolean
          )(implicit ec: ExecutionContext) = {
    val request = Get(buildUri(containersPath / "json", Query("all" -> all.toString)))
    connection.sendRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.OK =>

          Unmarshal(response).to[Seq[ContainerStatus]]
        case _ =>
          unknownResponseFuture(response)
      }
    }
  }

  def create(
              containerConfig: ContainerConfig,
              containerName: Option[ContainerName]
            )(implicit ec: ExecutionContext): Future[CreateContainerResponse] = {
    val configJson = containerConfig
    val query = containerName.map(name => Query("name" -> name.value)).getOrElse(Query())
    val request = Post(buildUri(containersPath / "create", query), configJson)
    connection.sendRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.Created =>
          Unmarshal(response).to[CreateContainerResponse]
        case _ =>
          unknownResponseFuture(response)
      }
    }
  }

  def start(
             containerId: String,
           )(implicit ec: ExecutionContext): Future[String] = {
    val request = Post(buildUri(containersPath / containerId / "start"))
    connection.sendRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.NoContent =>
          Future.successful(containerId)
        case StatusCodes.NotFound =>
          throw ContainerNotFoundException(containerId)
        case _ =>
          unknownResponseFuture(response)
      }
    }
  }


  def pause(
             containerId: String,
           )(implicit ec: ExecutionContext): Future[String] = {
    val request = Post(buildUri(containersPath / containerId / "pause"))
    connection.sendRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.NoContent =>
          Future.successful(containerId)
        case StatusCodes.NotModified =>
          throw ContainerAlreadyStoppedException(containerId)
        case StatusCodes.NotFound =>
          throw ContainerNotFoundException(containerId)
        case _ =>
          unknownResponseFuture(response)
      }
    }
  }

  def unpause(
             containerId: String,
           )(implicit ec: ExecutionContext): Future[String] = {
    val request = Post(buildUri(containersPath / containerId / "unpause"))
    connection.sendRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.NoContent =>
          Future.successful(containerId)
        case StatusCodes.NotModified =>
          throw ContainerAlreadyStoppedException(containerId)
        case StatusCodes.NotFound =>
          throw ContainerNotFoundException(containerId)
        case _ =>
          unknownResponseFuture(response)
      }
    }
  }

  def restart(
               containerId: String,
             )(implicit ec: ExecutionContext): Future[String] = {
    val request = Post(buildUri(containersPath / containerId / "stop"))
    connection.sendRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.NoContent =>
          Future.successful(containerId)
        case StatusCodes.NotFound =>
          throw new ContainerNotFoundException(containerId)
        case _ =>
          unknownResponseFuture(response)
      }
    }
  }

  def stop(
            containerId: String,
          )(implicit ec: ExecutionContext): Future[String] = {
    val request = Post(buildUri(containersPath / containerId / "stop"))
    connection.sendRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.NoContent =>
          Future.successful(containerId)
        case StatusCodes.NotModified =>
          throw new ContainerAlreadyStoppedException(containerId)
        case StatusCodes.NotFound =>
          throw new ContainerNotFoundException(containerId)
        case _ =>
          unknownResponseFuture(response)
      }
    }
  }

  def remove(
              containerId: String,
              force: Boolean,
              removeVolumes: Boolean
            )(implicit ec: ExecutionContext): Future[String] = {
    val query = Query("force" -> force.toString, "v" -> removeVolumes.toString)
    val request = Delete(buildUri(containersPath / containerId, query))
    connection.sendRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.NoContent =>
          Future.successful(containerId)
        case StatusCodes.NotFound =>
          throw ContainerNotFoundException(containerId)
        case _ =>
          unknownResponseFuture(response)
      }
    }
  }

  def get(
           containerId: String
         )(implicit ec: ExecutionContext): Future[Networks] = {
    val request = Get(buildUri(containersPath / containerId / "json"))
    connection.sendRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.OK =>
          Unmarshal(response.entity).to[String].map { json =>
            val out = json.parseJson.asJsObject.getFields("NetworkSettings")
            out match {
              case Seq(network) => Networks(network.asJsObject.fields("IPAddress").toString())
              case _ => throw DeserializationException("Cannot find required field NetworkSettings/IPAddress")
            }

          }
        case StatusCodes.NotFound =>
          throw ContainerNotFoundException(containerId)
        case _ =>
          unknownResponseFuture(response)
      }
    }
  }

}