package de.upb.cs.swt.delphi.instanceregistry.Docker

import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.unmarshalling.Unmarshal

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}


class ContainerCommands(connection: DockerConnection) extends JsonSupport with Commands {

  import connection._

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
    } //, Duration.Inf)
  }

  def create(
              containerConfig: ContainerConfig,
              //  hostConfig: HostConfig,
              containerName: Option[ContainerName]
            )(implicit ec: ExecutionContext): Future[CreateContainerResponse] = {
    val configJson = containerConfig //+ hostConfig.toString
    println("configjson is" + configJson)
    val query = containerName.map(name => Query("name" -> name.value)).getOrElse(Query())
    val request = Post(buildUri(containersPath / "create", query), configJson)
    //connection.sendRequest(request).flatMap { response =>
    Await.result(Http(system).singleRequest(request) map { response =>
      response.status match {
        case StatusCodes.Created =>
          println("response entity is" + Unmarshal(response.entity).to[String])
          Unmarshal(response).to[CreateContainerResponse]
        case _ =>
          unknownResponseFuture(response)
      }
    }, Duration.Inf)
  }


  def start(
             containerId: String,
             hostConfig: Option[HostConfig] = None
           )(implicit ec: ExecutionContext) = {
    val request = Post(buildUri(containersPath / containerId / "start"))
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
            //  maximumWait: Seconds
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
          throw new ContainerNotFoundException(containerId)
        case _ =>
          unknownResponseFuture(response)
      }
    }
  }


}