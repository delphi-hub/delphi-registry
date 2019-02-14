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

import akka.http.scaladsl.model.StatusCode

abstract class DockerApiException(message: String) extends RuntimeException(message)

case class ContainerNotFoundException(id: String) extends DockerApiException(s"Container $id was not found")

case class ContainerAlreadyStartedException(id: ContainerId) extends DockerApiException(s"Container $id has already started")

case class ContainerAlreadyStoppedException(id: String) extends DockerApiException(s"Container $id has already stopped")

case class ImageNotFoundException(imageName: String) extends DockerApiException(s"Image $imageName was not found")

case class UnknownResponseException(statusCode: StatusCode, entity: String) extends DockerApiException(s"Got unknown status: $statusCode, with entity: $entity")

case class ServerErrorException(statusCode: StatusCode, detailMessage: String) extends DockerApiException(s"Server error ($statusCode): $detailMessage")

case class BadRequestException(detailMessage: String) extends DockerApiException(detailMessage)


abstract class DockerException(message: String) extends RuntimeException(message)

