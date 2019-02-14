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
package de.upb.cs.swt.delphi.instanceregistry.daos
import java.sql.Timestamp

import akka.http.scaladsl.model.DateTime
import slick.jdbc.MySQLProfile
import slick.jdbc.MySQLProfile.api._
import slick.lifted.ProvenShape
import slick.sql.SqlProfile.ColumnOption.{NotNull, SqlType}

object StringLengthDefinitions {
  val EnumStringLength: Int = 50
  val HashStringLength: Int = 65
  val NameStringLength: Int = 100
  val UriStringLength: Int = 255
  val LongCompositeStringLenght: Int = 1000
}

class Instances(tag: Tag) extends Table[(Long, String, Long, String, String, Option[String], String, String, Option[String])](tag, "instances") {

  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
  def host: Rep[String] = column[String]("host", O.Length(StringLengthDefinitions.UriStringLength), NotNull)
  def portNumber: Rep[Long] = column[Long]("portNumber", NotNull)
  def name: Rep[String] = column[String]("name", O.Length(StringLengthDefinitions.NameStringLength), NotNull)
  def componentType: Rep[String] = column[String]("componentType", O.Length(StringLengthDefinitions.EnumStringLength), NotNull)
  def dockerId: Rep[Option[String]] = column[Option[String]]("dockerId", O.Length(StringLengthDefinitions.UriStringLength), O.Default(None))

  def instanceState: Rep[String] = column[String]("instanceState", O.Length(StringLengthDefinitions.EnumStringLength), NotNull)
  def labels: Rep[String] = column[String]("labels", O.Length(StringLengthDefinitions.LongCompositeStringLenght), NotNull)

  def traefikHostName : Rep[Option[String]] = column[Option[String]]("traefikHostName", O.Length(StringLengthDefinitions.UriStringLength), O.Default(None))

  // scalastyle:off method.name
  def * :ProvenShape[(Long, String, Long, String, String, Option[String], String, String, Option[String])] =
    (id, host, portNumber, name, componentType, dockerId, instanceState, labels, traefikHostName)
  // scalastyle:on method.name
}

class InstanceMatchingResults(tag: Tag) extends Table[(Long, Long, Boolean)](tag, "instance_matching_results") {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
  def instanceId: Rep[Long] = column[Long]("instanceId", NotNull)
  def matchingSuccessful: Rep[Boolean] = column[Boolean]("matchingSuccessful")

  // scalastyle:off method.name
  def * : ProvenShape[(Long, Long, Boolean)] = (id, instanceId, matchingSuccessful)
  // scalastyle:on method.name
}

class InstanceEvents(tag: Tag) extends Table[(Long, String, DateTime, String)](tag, "instance_events") {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
  def eventType: Rep[String] = column[String]("eventType", O.Length(StringLengthDefinitions.NameStringLength), NotNull)
  def timestamp: Rep[DateTime] = column[DateTime]("timestamp", SqlType("timestamp not null default CURRENT_TIMESTAMP"))
  def payload: Rep[String] = column[String]("payload", O.Length(StringLengthDefinitions.LongCompositeStringLenght), NotNull)

  // scalastyle:off method.name
  def * : ProvenShape[(Long, String, DateTime, String)] = (id, eventType, timestamp, payload)
  // scalastyle:on method.name

  implicit def dateTime : MySQLProfile.BaseColumnType[DateTime] =
    MappedColumnType.base[DateTime, Timestamp](
      dt => new Timestamp(dt.clicks),
      ts => DateTime(ts.getTime)
    )
}

class EventMaps(tag: Tag) extends Table[(Long, Long, Long)](tag, "event_maps") {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
  def instanceId: Rep[Long] = column[Long]("instanceId", NotNull)
  def eventId: Rep[Long] = column[Long]("eventId", NotNull)

  // scalastyle:off method.name
  def * : ProvenShape[(Long, Long, Long)] = (id, instanceId, eventId)
  // scalastyle:on method.name
}

class InstanceLinks(tag: Tag) extends Table[(Long, Long, Long, String)](tag, "instance_links") {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
  def idFrom: Rep[Long] = column[Long]("idFrom", NotNull)
  def idTo: Rep[Long] = column[Long]("idTo", NotNull)
  def linkState: Rep[String] = column[String]("linkState", O.Length(StringLengthDefinitions.NameStringLength), NotNull)

  // scalastyle:off method.name
  def * : ProvenShape[(Long, Long, Long, String)] = (id, idFrom, idTo, linkState)
  // scalastyle:on method.name
}

class Users(tag: Tag) extends Table[(Long, String, String, String)](tag, "users") {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
  def userName: Rep[String] = column[String]("userName", O.Length(StringLengthDefinitions.NameStringLength), NotNull)
  def secret: Rep[String] = column[String]("secret", O.Length(StringLengthDefinitions.HashStringLength), NotNull)
  def userType: Rep[String] = column[String]("userType", O.Length(StringLengthDefinitions.EnumStringLength), NotNull)

  // scalastyle:off method.name
  def * : ProvenShape[(Long, String, String, String)] = (id, userName, secret, userType)
  // scalastyle:on method.name
}