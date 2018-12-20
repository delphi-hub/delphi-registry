package de.upb.cs.swt.delphi.instanceregistry.requestLimiter

import akka.actor.{Actor, Props}
import de.upb.cs.swt.delphi.instanceregistry.requestLimiter.IpLogActor._
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Configuration}

import scala.collection.mutable.ListBuffer

class IpLogActor extends Actor with AppLogging {
  private var ipStore = ListBuffer[String]()
  private val configuration = new Configuration

  override def receive: Receive = {
    case Add(ip) => {
      ipStore += ip
    }
    case Reset => {
      ipStore = ListBuffer[String]()
    }
    case Accepted(ip) => {
      val hostname = ip.split(":")(0)
      self ! Add(hostname)
      val noOfReqFromIp = ipStore.count(_.equals(hostname))
      val validTotalReq = ipStore.size < configuration.maxTotalNoRequest
      val validIndividualReq = noOfReqFromIp < configuration.maxIndividualIpReq
      val validReq = validTotalReq && validIndividualReq
      val accept = if (validReq) true else false
      sender() ! accept
    }
  }
}


object IpLogActor {

  final case class Add(ip: String)

  case object Reset

  final case class Accepted(ip: String)

  def props: Props = Props(new IpLogActor)
}