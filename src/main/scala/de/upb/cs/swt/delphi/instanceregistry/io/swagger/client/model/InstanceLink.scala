package de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model

import LinkEnums.LinkState

final case class InstanceLink(idFrom: Long, idTo:Long, linkState: LinkState)

object LinkEnums {
  type LinkState = LinkState.Value

  object LinkState extends Enumeration {
    val Assigned: Value =  Value("Assigned")
    val Failed: Value = Value("Failed")
    val Outdated: Value = Value("Outdated")
  }
}

