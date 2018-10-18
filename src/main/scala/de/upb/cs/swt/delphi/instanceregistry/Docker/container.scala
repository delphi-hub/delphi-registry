package de.upb.cs.swt.delphi.instanceregistry.Docker

import akka.http.scaladsl.model.DateTime


sealed trait ContainerId {
  def value: String
}

case class ContainerHashId(hash: String) extends ContainerId {
  override def value = hash

  def shortHash = hash.take(12)

  override def toString = hash
}

case class ContainerName(name: String) extends ContainerId {
  override def value = name

  override def toString = name
}

sealed trait Port {
  def port: Int

  def protocol: String

  override def toString = s"$port/$protocol"
}

object Port {

  case class Tcp(port: Int) extends Port {
    def protocol = "tcp"
  }

  case class Udp(port: Int) extends Port {
    def protocol = "udp"
  }

  def apply(port: Int, protocol: String): Option[Port] = {
    protocol match {
      case "tcp" => Some(Tcp(port))
      case "udp" => Some(Udp(port))
      case _ => None
    }
  }

  private val PortFormat = "(\\d+)/(\\w+)".r

  def unapply(raw: String): Option[Port] = {
    raw match {
      case PortFormat(port, "tcp") => Some(Tcp(port.toInt))
      case PortFormat(port, "udp") => Some(Udp(port.toInt))
      case _ => None
    }
  }
}


/**
  * Configuration options for standard streams.
  *
  * @param attachStdIn  Attach to standard input.
  * @param attachStdOut Attach to standard output.
  * @param attachStdErr Attach to standard error.
  * @param tty          Attach standard streams to a tty.
  * @param openStdin    Keep stdin open even if not attached.
  * @param stdinOnce    Close stdin when one attached client disconnects.
  */
case class StandardStreamsConfig(
                                  attachStdIn: Boolean = false,
                                  attachStdOut: Boolean = false,
                                  attachStdErr: Boolean = false,
                                  tty: Boolean = false,
                                  openStdin: Boolean = false,
                                  stdinOnce: Boolean = false
                                )

/**
  * Resource limitations on a container.
  *
  * @param memory     Memory limit, in bytes.
  * @param memorySwap Total memory limit (memory + swap), in bytes. Set `-1` to disable swap.
  * @param cpuShares  CPU shares (relative weight vs. other containers)
  * @param cpuset     CPUs in which to allow execution, examples: `"0-2"`, `"0,1"`.
  */
case class ContainerResourceLimits(
                                    memory: Long = 0,
                                    memorySwap: Long = 0,
                                    memoryReservation: Long = 0,
                                    cpuShares: Long = 0,
                                    cpuset: Option[String] = None
                                  )

object ContainerLink {
  def unapply(link: String) = {
    link.split(':') match {
      case Array(container) => Some(ContainerLink(container))
      case Array(container, alias) => Some(ContainerLink(container, Some(alias)))
      case _ => None
    }
  }
}

case class ContainerLink(containerName: String, aliasName: Option[String] = None) {
  def mkString = containerName + aliasName.fold("")(":" + _)
}

/**
  * Host configuration for a container.
  *
  * @param portBindings           A map of exposed container ports to bindings on the host.
  * @param publishAllPorts        Allocate a random port for each exposed container port.
  * @param links                  Container links.
  * @param volumeBindings         Volume bindings.
  * @param volumesFrom            Volumes to inherit from other containers.
  * @param devices                Devices to add to the container.
  * @param readOnlyRootFilesystem Mount the container's root filesystem as read only.
  * @param dnsServers             DNS servers for the container to use.
  * @param dnsSearchDomains       DNS search domains.
  * @param networkMode            Networking mode for the container
  * @param privileged             Gives the container full access to the host.
  * @param capabilities           Change Linux kernel capabilities for the container.
  * @param restartPolicy          Behavior to apply when the container exits.
  */
case class HostConfig(
                       portBindings: Map[Port, Seq[PortBinding]] = Map.empty,
                       publishAllPorts: Boolean = false,
                       links: Seq[ContainerLink] = Seq.empty,
                       volumeBindings: Seq[VolumeBinding] = Seq.empty,
                       volumesFrom: Seq[String] = Seq.empty,
                       devices: Seq[DeviceMapping] = Seq.empty,
                       readOnlyRootFilesystem: Boolean = false,
                       dnsServers: Seq[String] = Seq.empty,
                       dnsSearchDomains: Seq[String] = Seq.empty,
                       networkMode: Option[String] = None,
                       privileged: Boolean = false,
                       capabilities: LinuxCapabilities = LinuxCapabilities(),
                       resourceLimits: ContainerResourceLimits = ContainerResourceLimits(),
                       restartPolicy: RestartPolicy = NeverRestart
                     ) {
  def withPortBindings(ports: (Port, Seq[PortBinding])*) = {
    copy(portBindings = Map(ports: _*))
  }

  def withPublishAllPorts(publishAll: Boolean) = {
    copy(publishAllPorts = publishAll)
  }

  def withLinks(links: ContainerLink*) = {
    copy(links = links)
  }

  def withVolumeBindings(volumeBindings: VolumeBinding*) = {
    copy(volumeBindings = volumeBindings)
  }

  def withVolumesFrom(containers: String*) = {
    copy(volumesFrom = containers)
  }

  def withDevices(devices: DeviceMapping*) = {
    copy(devices = devices)
  }

  def withReadOnlyRootFilesystem(readOnlyRootFilesystem: Boolean) = {
    copy(readOnlyRootFilesystem = readOnlyRootFilesystem)
  }

  def withDnsServers(servers: String*) = {
    copy(dnsServers = servers)
  }

  def withDnsSearchDomains(searchDomains: String*) = {
    copy(dnsSearchDomains = searchDomains)
  }

  def withNetworkMode(mode: String) = {
    copy(networkMode = Option(mode))
  }

  def withPrivileged(privileged: Boolean) = {
    copy(privileged = privileged)
  }

  def withCapabilities(capabilities: LinuxCapabilities) = {
    copy(capabilities = capabilities)
  }

  def withRestartPolicy(restartPolicy: RestartPolicy) = {
    copy(restartPolicy = restartPolicy)
  }

  def withResourceLimits(resourceLimits: ContainerResourceLimits) = {
    copy(resourceLimits = resourceLimits)
  }

}

/**
  * @param add  Kernel capabilities to add to the container
  * @param drop Kernel capabilities to drop from the container.
  */
case class LinuxCapabilities(
                              add: Seq[String] = Seq.empty,
                              drop: Seq[String] = Seq.empty
                            )

trait RestartPolicy {
  def name: String
}

case object NeverRestart extends RestartPolicy {
  val name = ""
}

case object AlwaysRestart extends RestartPolicy {
  val name = "always"
}

object RestartOnFailure {
  val name = "on-failure"
}

case class RestartOnFailure(maximumRetryCount: Int = 0) extends RestartPolicy {
  val name = RestartOnFailure.name
}

case class DeviceMapping(pathOnHost: String, pathInContainer: String, cgroupPermissions: String)

case class CreateContainerResponse(Id: String, Warnings: Option[String])

//, Warnings: Seq[String])

case class ContainerState(
                           running: Boolean,
                           paused: Boolean,
                           restarting: Boolean,
                           pid: Int,
                           exitCode: Int,
                           startedAt: Option[DateTime] = None,
                           finishedAt: Option[DateTime] = None
                         )

case class NetworkSettings(
                            IPAddress: String
                            // ipPrefixLength: Int,
                            //gateway: String,
                            //bridge: String,
                            //ports: Map[Port, Seq[PortBinding]]
                          )

case class ContainerInfo(
                          id: ContainerHashId,
                          created: Option[DateTime],
                          path: String,
                          args: Seq[String],
                          config: ContainerConfig,
                          state: ContainerState,
                          image: String,
                          networkSettings: NetworkSettings,
                          resolvConfPath: String,
                          hostnamePath: String,
                          hostsPath: String,
                          name: String,
                          mountLabel: Option[String] = None,
                          processLabel: Option[String] = None,
                          volumes: Seq[VolumeBinding] = Seq.empty,
                          hostConfig: HostConfig,
                          node: Option[Node]
                        )

object VolumeBinding {
  def unapply(binding: String): Option[VolumeBinding] = {
    binding.split(":") match {
      case Array(host, container) =>
        Some(VolumeBinding(host, container, rw = true))
      case Array(host, container, "ro") =>
        Some(VolumeBinding(host, container, rw = false))
      case _ =>
        None
    }
  }
}

case class VolumeBinding(
                          hostPath: String,
                          containerPath: String,
                          rw: Boolean = true
                        )

case class Node(
                 labels: Map[String, String],
                 memory: Long,
                 cpus: Long,
                 name: String,
                 address: String,
                 ip: String,
                 id: String
               )

case class ContainerStatus(
                            Command: String,
                            // Created: String,
                            Id: String,
                            Image: String,
                            Names: Seq[String],
                            //Ports: Map[Port, Seq[PortBinding]], // = Map.empty,
                            //Labels: Map[String, String] = Map.empty,
                            // Ports: Map[Int, Seq[String]],
                            Labels: Map[String, String],
                            Status: String
                          )

case class ContainerConfig(
                            Image: String,
                            Entrypoint: Option[Seq[String]] = None,
                            Cmd: Seq[String] = Seq.empty,
                            Env: Seq[String] = Seq.empty)

//Id: Seq[String] = Seq.empty,
//IPAddress: Seq[String] = Seq.empty)

// ExposedPorts: Seq[String] = Seq.empty)

object PortBinding {
  def apply(HostPort: Int): PortBinding = new PortBinding(HostPort = HostPort)
}

case class PortBinding(HostIp: String = "0.0.0.0",
                       HostPort: Int)
