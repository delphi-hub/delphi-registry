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
package de.upb.cs.swt.delphi.instanceregistry.requestLimiter

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, extractClientIP, onSuccess}
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.Timeout
import de.upb.cs.swt.delphi.instanceregistry.Registry
import de.upb.cs.swt.delphi.instanceregistry.Docker.JsonSupport
import de.upb.cs.swt.delphi.instanceregistry.requestLimiter.IpLogActor.{Accepted, Reset}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.pattern.ask
import spray.json._

class RequestLimitScheduler(ipLogActor: ActorRef) extends JsonSupport {

  implicit val system: ActorSystem = Registry.system
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher


  implicit val timeout: Timeout = Timeout(5, TimeUnit.SECONDS)

  Source.tick(0.second, Registry.configuration.ipLogRefreshRate, NotUsed)
    .runForeach(_ => {
      ipLogActor ! Reset
    })(materializer)

  def acceptOnValidLimit(apiRoutes: Route): Route = {
    extractClientIP { ip =>
      val promise = (ipLogActor ? Accepted(ip.toString())).mapTo[Boolean]
      onSuccess(promise) {
        success =>
          if (success) {
            apiRoutes
          } else {
            val res = Map("msg" -> "Request limit exceeded")
            complete(HttpResponse(StatusCodes.BadRequest, entity = res.toJson.toString()))
          }
      }
    }
  }
}
