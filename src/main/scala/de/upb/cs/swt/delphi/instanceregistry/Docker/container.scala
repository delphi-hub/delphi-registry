package de.upb.cs.swt.delphi.instanceregistry.Docker

sealed trait ContainerId {
  def value: String
}

case class ContainerName(name: String) extends ContainerId {
  override def value = name

  override def toString = name
}

case class CreateContainerResponse(Id: String, Warnings: Option[String])

case class ContainerStatus(
                            Command: String,
                            Id: String,
                            Image: String,
                            Names: Seq[String],
                            Labels: Map[String, String],
                            Status: String
                          )

case class ContainerConfig(
                            Image: String,
                            Entrypoint: Option[Seq[String]] = None,
                            Cmd: Seq[String] = Seq.empty,
                            Env: Seq[String] = Seq.empty)

case class Networks(
                     IPAddress: String
                   )

object PortBinding {
  def apply(HostPort: Int): PortBinding = new PortBinding(HostPort = HostPort)
}

case class PortBinding(HostIp: String = "0.0.0.0",
                       HostPort: Int)
