package de.upb.cs.swt.delphi.instanceregistry.requestLimiter

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.{HttpResponse, StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, extractClientIP, onSuccess}
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.Timeout
import de.upb.cs.swt.delphi.instanceregistry.Configuration
import de.upb.cs.swt.delphi.instanceregistry.Docker.JsonSupport
import de.upb.cs.swt.delphi.instanceregistry.requestLimiter.IpLogActor.{Accepted, Reset}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.pattern.ask
import spray.json._

class RequestLimitScheduler(ipLogActor: ActorRef) extends JsonSupport {
  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val configuration: Configuration = new Configuration()
  implicit val timeout: Timeout = Timeout(configuration.defaultTimeout, TimeUnit.SECONDS)

  Source.tick(0.second, configuration.ipLogRefreshRate, NotUsed)
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
