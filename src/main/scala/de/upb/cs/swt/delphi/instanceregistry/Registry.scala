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
package de.upb.cs.swt.delphi.instanceregistry

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.Docker._
import de.upb.cs.swt.delphi.instanceregistry.connection.Server
import de.upb.cs.swt.delphi.instanceregistry.daos._

import scala.concurrent.ExecutionContext
import scala.language.postfixOps

object Registry extends AppLogging {
  implicit val system: ActorSystem = ActorSystem("delphi-registry")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher


  val configuration = new Configuration()

  private val dao : InstanceDAO  = {
    if (configuration.useInMemoryInstanceDB) {
      new DynamicInstanceDAO(configuration)
    } else {
      new DatabaseInstanceDAO(configuration)
    }
  }

  private val authDao: AuthDAO = {
    if (configuration.useInMemoryAuthDB) {
      new DynamicAuthDAO(configuration)
    } else {
      new DatabaseAuthDAO(configuration)
    }
  }

  private val requestHandler = new RequestHandler(configuration, authDao, dao, DockerConnection.fromEnvironment(configuration))

  private val server: Server = new Server(requestHandler)


  def main(args: Array[String]): Unit = {
    requestHandler.initialize()
    server.startServer(configuration.bindHost, configuration.bindPort)
    requestHandler.shutdown()
    system.terminate()
  }
}
