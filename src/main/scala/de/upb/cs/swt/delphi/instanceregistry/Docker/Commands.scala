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

import akka.NotUsed
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import de.upb.cs.swt.delphi.instanceregistry.AppLogging

import scala.concurrent.{ExecutionContext, Future}

trait Commands extends AppLogging{
  def unknownResponse(response: HttpResponse)(implicit ec: ExecutionContext, mat: Materializer): Source[Nothing, NotUsed] = {
    import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers._

    Source.fromFuture(Unmarshal(response.entity).to[String](stringUnmarshaller, ec, mat).map { entity =>
      if (response.status == StatusCodes.InternalServerError) {

        throw ServerErrorException(response.status, entity)
      } else {
        throw UnknownResponseException(response.status, entity)
      }
    })
  }

  def unknownResponseFuture(response: HttpResponse)(implicit ec: ExecutionContext, mat: Materializer): Future[Nothing] = {
    unknownResponse(response).runWith(Sink.head)
  }
}