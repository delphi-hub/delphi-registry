// Copyright (C) 2018 The Delphi Team.
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

// http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package de.upb.cs.swt.delphi.instanceregistry.Docker


import java.nio.ByteOrder

import akka.NotUsed
import akka.actor.{ActorSystem, PoisonPill}
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.model.{HttpEntity, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Flow, Framing, Keep, Sink, Source}
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Registry}
import spray.json._
import PostDataFormatting.commandJsonRequest
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.OverflowStrategy
import akka.util.ByteString
import org.reactivestreams.Publisher
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}


class ContainerCommands(connection: DockerConnection) extends JsonSupport with Commands with AppLogging {

  import connection._

  implicit val system: ActorSystem = Registry.system
  protected val containersPath: Path = Path / "containers"

  def list(all: Boolean)(implicit ec: ExecutionContext): Future[Seq[ContainerStatus]] = {
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
    val request = Post(buildUri(containersPath / containerId / "restart"))
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

  def stop(
            containerId: String,
          )(implicit ec: ExecutionContext): Future[String] = {
    val request = Post(buildUri(containersPath / containerId / "stop"))
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

            Try[Networks]{
              val ip = json.parseJson.asJsObject.fields("NetworkSettings")
                .asJsObject.fields("Networks")
                .asJsObject.fields(Registry.configuration.traefikDockerNetwork)
                .asJsObject.getFields("IPAddress").head.toString.replace("\"", "")
              Networks(ip)
            } match {
              case Success(network) => network
              case Failure(ex) =>
                throw DeserializationException(s"Failed to extract IPAddress from docker with message ${ex.getMessage}")
            }

          }
        case StatusCodes.NotFound =>
          throw ContainerNotFoundException(containerId)
        case _ =>
          unknownResponseFuture(response)
      }
    }
  }

  def retrieveLogs(
            containerId: String,
            stdErrSelected: Boolean
          )(implicit ec: ExecutionContext): Future[String] = {

    val query = Query("stdout" -> (!stdErrSelected).toString, "stderr" -> stdErrSelected.toString, "follow" -> "false", "tail" -> "all", "timestamps" -> "true")
    val request = Get(buildUri(containersPath / containerId.substring(0,11) / "logs", query))

    connection.sendRequest(request).flatMap {response =>
      response.status match {
        case StatusCodes.OK =>
          Unmarshal(response.entity).to[String]
        case StatusCodes.UpgradeRequired =>
          log.warning(s"Unexpected upgrade response while reading logs for container $containerId")
          log.warning(s"Got $response")
          unknownResponseFuture(response)
        case StatusCodes.NotFound =>
          throw ContainerNotFoundException(containerId)
        case _ =>
          unknownResponseFuture(response)
      }
    }
  }

  def streamLogs(containerId: String, stdErrSelected: Boolean) (implicit ec: ExecutionContext) : Try[Publisher[Message]] = {

    // Select stdout / stderr in query params
    val queryParams = Query("stdout" -> (!stdErrSelected).toString, "stderr" -> stdErrSelected.toString, "follow" -> "true", "tail" -> "all", "timestamps" -> "false")

    // Create actor-publisher pair, publisher will be returned
    val (streamActor, streamPublisher) = Source.actorRef[Message](bufferSize = 10, OverflowStrategy.dropNew)
      .toMat(Sink.asPublisher(fanout = true))(Keep.both)
      .run()

    // Delimiter flow splits incoming traffic into lines based on dockers multiplex-protocol
    // Docker prepends an 8-byte header, where the last 4 byte encode line length in big endian
    // See https://docs.docker.com/engine/api/v1.30/#operation/ContainerAttach
    val delimiter: Flow[ByteString, ByteString, NotUsed] = Framing.lengthField(4, 4, 100000, ByteOrder.BIG_ENDIAN)

    // Flow that removes header bytes from payload
    val removeHeaderFlow: Flow[ByteString, ByteString, NotUsed] = Flow.fromFunction(in => in.slice(8, in.size))

    // Build request
    val request = Get(buildUri(containersPath / containerId.substring(0,11) / "logs", queryParams))

    // Execute request
    val res = connection.sendRequest(request).flatMap { res =>
      // Extract payload ByteString from data stream using above flows. Map to string.
      val logLines = res.entity.dataBytes.via(delimiter).via(removeHeaderFlow).map(_.utf8String)
      logLines.runForeach { line =>
        // Send each log line to the stream actor, which will publish them
        log.debug(s"Streaming log message $line")
        streamActor ! TextMessage(line)
      }
    }

    // Kill actor on completion
    res.onComplete{ _ =>
      log.info("Log stream finished successfully.")
      streamActor ! PoisonPill
    }

    // Return publish so server can subscribe to it
    Success(streamPublisher)
  }

  def commandCreate(containerId: String, cmd: String, privileged: Option[Boolean], user: Option[String])
                   (implicit ec: ExecutionContext): Future[CreateContainerResponse] = {
    val content = commandJsonRequest(cmd, privileged, user)

    val request = Post(buildUri(containersPath / containerId / "exec"), HttpEntity(`application/json`, content))


    connection.sendRequest(request).flatMap { response =>

      response.status match {
        case StatusCodes.Created =>

          Unmarshal(response).to[CreateContainerResponse]
        case StatusCodes.NotFound =>
          throw ContainerNotFoundException(containerId)
        case _ =>
          unknownResponseFuture(response)
      }
    }
  }

  def commandRun(containerId: String, commandId: String)(implicit ec: ExecutionContext): Future[String]  =  {
    val request = Post(buildUri(containersPath / "exec" / commandId / "start"))
    connection.sendRequest(request).flatMap { response =>
      response.status match {
        case StatusCodes.OK =>
          Future.successful(commandId)
        case StatusCodes.NotFound =>
          throw ContainerNotFoundException(containerId)
        case _ =>
          unknownResponseFuture(response)
      }
    }
  }

}