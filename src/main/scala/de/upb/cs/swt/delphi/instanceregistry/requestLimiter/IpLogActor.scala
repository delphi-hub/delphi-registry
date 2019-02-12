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

import akka.actor.{Actor, Props}
import de.upb.cs.swt.delphi.instanceregistry.requestLimiter.IpLogActor._
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Registry}

import scala.collection.mutable.ListBuffer

class IpLogActor extends Actor with AppLogging {
  private var ipStore = ListBuffer[String]()

  override def receive: Receive = {
    case Add(ip) =>
      ipStore += ip

    case Reset =>
      ipStore = ListBuffer[String]()

    case Accepted(ip) =>
      val hostname = ip.split(":")(0)
      self ! Add(hostname)
      val noOfReqFromIp = ipStore.count(_.equals(hostname))
      val validTotalReq = ipStore.size < Registry.configuration.maxTotalNoRequest
      val validIndividualReq = noOfReqFromIp < Registry.configuration.maxIndividualIpReq
      val validReq = validTotalReq && validIndividualReq
      sender() ! validReq
  }
}


object IpLogActor {

  final case class Add(ip: String)

  case object Reset

  final case class Accepted(ip: String)

  def props: Props = Props(new IpLogActor)
}
