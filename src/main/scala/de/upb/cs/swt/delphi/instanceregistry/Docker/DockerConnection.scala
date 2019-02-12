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

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.stream.Materializer
import de.upb.cs.swt.delphi.instanceregistry.Configuration

import scala.concurrent.Future

object DockerConnection {


  def fromEnvironment(configuration: Configuration) (implicit system: ActorSystem, mat: Materializer): DockerConnection = {
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

case class DockerHttpConnection(
                                 baseUri: Uri,
                               )(implicit val system: ActorSystem, val materializer: Materializer)
  extends DockerConnection {
  override def sendRequest(request: HttpRequest): Future[HttpResponse] = {
    Http(system).singleRequest(request)
  }
}

