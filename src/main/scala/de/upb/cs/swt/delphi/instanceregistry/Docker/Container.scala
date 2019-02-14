// Copyright (C) 2018 The Delphi Team.
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package de.upb.cs.swt.delphi.instanceregistry.Docker

sealed trait ContainerId {
  def value: String
}

case class ContainerName(name: String) extends ContainerId {
  override def value: String = name

  override def toString: String = name
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
                            Env: Seq[String] = Seq.empty,
                            Labels: Map[String, String] = Map.empty[String,String],
                            ExposedPorts: Map[String, EmptyExposedPortConfig] = Map.empty,
                            NetworkingConfig: NetworkConfig = NetworkConfig(Map.empty))

case class NetworkConfig(EndpointsConfig: Map[String, EmptyEndpointConfig])
case class EmptyEndpointConfig()
case class EmptyExposedPortConfig()

case class Networks(
                     IPAddress: String
                   )

object PortBinding {
  def apply(HostPort: Int): PortBinding = new PortBinding(HostPort = HostPort)
}

case class PortBinding(HostIp: String = "0.0.0.0",
                       HostPort: Int)
