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
