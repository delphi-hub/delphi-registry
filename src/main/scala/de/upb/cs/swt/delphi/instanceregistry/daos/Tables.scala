package de.upb.cs.swt.delphi.instanceregistry.daos
import java.sql.Timestamp

import akka.http.scaladsl.model.DateTime
import slick.jdbc.MySQLProfile
import slick.jdbc.MySQLProfile.api._
import slick.sql.SqlProfile.ColumnOption.{NotNull, SqlType}

class Instances(tag: Tag) extends Table[(Long, String, Long, String, String, Option[String], String, String, Option[String])](tag, "instances") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
  def host = column[String]("host", O.Length(255), NotNull)
  def portNumber = column[Long]("portNumber", NotNull)
  def name = column[String]("name", O.Length(100), NotNull)
  def componentType = column[String]("componentType", O.Length(50), NotNull)
  def dockerId = column[Option[String]]("dockerId", O.Length(255), O.Default(None))

  def instanceState = column[String]("instanceState", O.Length(50), NotNull)
  def labels = column[String]("labels", O.Length(255), NotNull)

  def traefikHostName = column[Option[String]]("traefikHostName", O.Length(255), O.Default(None))

  def * = (id, host, portNumber, name, componentType, dockerId, instanceState, labels, traefikHostName)
}

class InstanceMatchingResults(tag: Tag) extends Table[(Long, Long, Boolean)](tag, "instance_matching_results") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
  def instanceId = column[Long]("instanceId", NotNull)
  def matchingSuccessful = column[Boolean]("matchingSuccessful")

  def * = (id, instanceId, matchingSuccessful)
}

class InstanceEvents(tag: Tag) extends Table[(Long, String, DateTime, String)](tag, "instance_events") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
  def eventType = column[String]("eventType", O.Length(100), NotNull)
  def timestamp = column[DateTime]("timestamp", SqlType("timestamp not null default CURRENT_TIMESTAMP"))
  def payload = column[String]("payload", O.Length(1000), NotNull)

  def * = (id, eventType, timestamp, payload)

  implicit def dateTime : MySQLProfile.BaseColumnType[DateTime] =
    MappedColumnType.base[DateTime, Timestamp](
      dt => new Timestamp(dt.clicks),
      ts => DateTime(ts.getTime)
    )
}

class EventMaps(tag: Tag) extends Table[(Long, Long, Long)](tag, "event_maps") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
  def instanceId = column[Long]("instanceId", NotNull)
  def eventId = column[Long]("eventId", NotNull)

  def * = (id, instanceId, eventId)
}

class InstanceLinks(tag: Tag) extends Table[(Long, Long, Long, String)](tag, "instance_links") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
  def idFrom = column[Long]("idFrom", NotNull)
  def idTo = column[Long]("idTo", NotNull)
  def linkState=column[String]("linkState", O.Length(100), NotNull)

  def * = (id, idFrom, idTo, linkState)
}