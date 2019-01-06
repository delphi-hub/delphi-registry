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
