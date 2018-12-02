package de.upb.cs.swt.delphi.instanceregistry.daos
import slick.jdbc.H2Profile.api._
import slick.sql.SqlProfile.ColumnOption.NotNull

class Instances(tag: Tag) extends Table[(Long, String, Long, String, String, String, String, String)](tag, "instances") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
  def host = column[String]("host", O.Length(50), NotNull)
  def portNumber = column[Long]("portNumber", NotNull)
  def name = column[String]("name", O.Length(50), NotNull)
  def componentType = column[String]("componentType", O.Length(50), NotNull)
  def dockerId = column[String]("dockerId", O.Length(50), NotNull)

  def instanceState = column[String]("instanceState", O.Length(50), NotNull)
  def labels = column[String]("labels", O.Length(100), NotNull)

  def * = (id, host, portNumber, name, componentType, dockerId, instanceState, labels)
}

class InstanceMatchingResults(tag: Tag) extends Table[(Long, Long, Boolean)](tag, "instance_matching_results") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
  def instanceId = column[Long]("instanceId", NotNull)
  def matchingSuccessful = column[Boolean]("matchingSuccessful")

  def * = (id, instanceId, matchingSuccessful)
}

class InstanceEvents(tag: Tag) extends Table[(Long, Long, String, String)](tag, "instance_events") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
  def instanceId = column[Long]("instanceId", NotNull)
  def eventType = column[String]("eventType", O.Length(100), NotNull)
  def payload = column[String]("payload", O.Length(255), NotNull)

  def * = (id, instanceId, eventType, payload)
}

class InstanceLinks(tag: Tag) extends Table[(Long, Long, Long, String)](tag, "instance_links") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
  def idFrom = column[Long]("idFrom", NotNull)
  def idTo = column[Long]("idTo", NotNull)
  def linkState=column[String]("linkState", O.Length(100), NotNull)

  def * = (id, idFrom, idTo, linkState)
}