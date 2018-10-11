package de.upb.cs.swt.delphi.instanceregistry.Docker

import scala.concurrent.{ExecutionContext, Future}

case class DockerClient(connection: DockerConnection) {




  val container = new ContainerCommands(connection)

  def ps(all: Boolean = false)(implicit ec: ExecutionContext): Future[Seq[ContainerStatus]] = listContainers(all)

  def listContainers(all: Boolean = false)(implicit ec: ExecutionContext) = container.list(all)

  def start(
             containerId: String,
            // hostConfig: Option[HostConfig] = None
           )(implicit ec: ExecutionContext): Future[String] = {
    container.start(containerId)
  }

  def stop(
            containerId: String,
          //  maximumWait: Seconds = Seconds.seconds(10)
          )(implicit ec: ExecutionContext): Future[String] = {
    container.stop(containerId)
  }

  def create(
              containerConfig: ContainerConfig,
              hostConfig: HostConfig = HostConfig(),
              containerName: Option[ContainerName] = None
            )(implicit ec: ExecutionContext): Future[CreateContainerResponse] = {
    container.create(containerConfig, hostConfig, containerName)
  }

  def runLocal(
                containerConfig: ContainerConfig,
                hostConfig: HostConfig = HostConfig(),
                name: Option[ContainerName] = None
              )(implicit ec: ExecutionContext): Future[String] = {
    create(containerConfig, hostConfig, name).flatMap { response =>
      container.start(response.Id)
    }
  }

  def removeContainer(
                       containerId: String,
                       force: Boolean = false,
                       volumes: Boolean = false
                     )(implicit ec: ExecutionContext): Future[String] = {
    container.remove(containerId, force, volumes)
  }
}
