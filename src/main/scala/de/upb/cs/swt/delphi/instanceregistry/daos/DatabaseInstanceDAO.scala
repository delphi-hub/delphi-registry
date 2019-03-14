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

import akka.actor.ActorSystem
import akka.http.scaladsl.model.DateTime
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.EventEnums.EventType
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Configuration, Registry}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.LinkEnums.LinkState
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.{InstanceJsonSupport, _}
import slick.lifted.TableQuery

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import slick.jdbc.MySQLProfile.api._
import slick.jdbc.meta.MTable
import spray.json._

import scala.concurrent.duration.Duration


class DatabaseInstanceDAO (configuration : Configuration) extends InstanceDAO with AppLogging with InstanceJsonSupport with EventJsonSupport{

  private val instances : TableQuery[Instances] = TableQuery[Instances]
  private val instanceMatchingResults : TableQuery[InstanceMatchingResults] = TableQuery[InstanceMatchingResults]
  private val instanceEvents : TableQuery[InstanceEvents] = TableQuery[InstanceEvents]
  private val instanceLinks : TableQuery[InstanceLinks] = TableQuery[InstanceLinks]
  private val eventMaps : TableQuery[EventMaps] = TableQuery[EventMaps]

  implicit val system : ActorSystem = Registry.system
  implicit val materializer : ActorMaterializer = ActorMaterializer()
  implicit val ec : ExecutionContext = system.dispatcher

  private var db = Database.forURL(configuration.instanceDatabaseHost + configuration.instanceDatabaseName,
    driver = configuration.instanceDatabaseDriver,
    user = configuration.instanceDatabaseUsername,
    password = configuration.instanceDatabasePassword)

  override def addInstance(instance : Instance) : Try[Long] = {

    val id = 0L //Will be set by DB
    val host = instance.host
    val port = instance.portNumber
    val name = instance.name
    val componentType = instance.componentType.toString
    val dockerId = instance.dockerId
    val instanceState = instance.instanceState.toString
    val labels = getListAsString(instance.labels)
    val traefikHost = instance.traefikConfiguration.map(conf => conf.hostName)

    val addFuture: Future[Long] =
      db.run((instances returning instances.map(_.id)) += (id, host, port, name, componentType, dockerId, instanceState, labels, traefikHost))
    val instanceId = Await.result(addFuture, Duration.Inf)

    log.info(s"Added instance ${instance.name} with id $instanceId to database.")
    Success(instanceId)
  }

  override def hasInstance(id: Long) : Boolean = {
      Await.result(db.run(instances.filter(_.id === id).exists.result), Duration.Inf)
  }

  override def removeInstance(id: Long) : Try[Unit] = {
    if(hasInstance(id)) {
      removeInstancesWithId(id)
      removeInstanceMatchingResultsWithId(id)
      removeInstanceEventsWithId(id)
      removeInstanceLinksWithId(id)
      Success(log.info(s"Successfully removed instance with id $id."))
    }else{
      val msg = s"Cannot remove instance with id $id, that id is not present."
      log.warning(msg)
      Failure(new RuntimeException(msg))
    }
  }

  override def getInstance(id: Long) : Option[Instance] = {
    if(hasInstance(id)) {
      val result = Await.result(db.run(instances.filter(_.id === id).result.headOption), Duration.Inf)
      Some(dataToObjectInstance(result))
    } else {
      None
    }
  }

  override def updateInstance(instance: Instance) : Try[Unit] = {
    if(hasInstance(instance.id.get)){
      val host = instance.host
      val port = instance.portNumber
      val dockerId = instance.dockerId
      val instanceState = instance.instanceState.toString
      val traefikHost = instance.traefikConfiguration.map(_.hostName)

      val q = for {i <- instances if i.id === instance.id.get} yield (i.host, i.portNumber, i.dockerId, i.instanceState, i.traefikHostName)
      Await.result(db.run(q.update(host, port, dockerId, instanceState, traefikHost)), Duration.Inf)
      Success()
    } else {
      Failure(new RuntimeException(s"Id ${instance.id.get} not found."))
    }
  }

  override def getInstancesOfType(componentType : ComponentType) : List[Instance] = {
    val cmpType = componentType.toString
    var listInstance = List[Instance]()
    val hasData = Await.result(db.run(instances.filter(_.componentType === cmpType).exists.result), Duration.Inf)

    if(hasData)
    {
      val resultAll = Await.result(db.run(instances.filter(_.componentType === cmpType).result), Duration.Inf)
      val listAll = List() ++ resultAll.map(_.value)
      listInstance = listAll.map(c => dataToObjectInstance(Option(c)))
    }
    listInstance
  }

  override def allInstances() : List[Instance] = {
    var listInstance = List[Instance]()
    val hasData = Await.result(db.run(instances.exists.result), Duration.Inf)
    if(hasData){
      val resultAll = Await.result(db.run(instances.result), Duration.Inf)
      val listAll = List() ++ resultAll.map(_.value)
      listInstance = listAll.map(c => dataToObjectInstance(Option(c)))
    }

    listInstance
  }

  override def removeAll() : Unit = {
    removeAllInstances()
    removeAllInstanceMatchingResults()
    removeAllInstanceEvents()
    removeAllInstanceLinks()
  }

  override def addMatchingResult(id: Long, matchingSuccessful : Boolean) : Try[Unit] = {
    if(hasInstance(id)){
      val addAction: DBIO[Unit] = DBIO.seq(
        instanceMatchingResults.map(c => (c.instanceId, c.matchingSuccessful))
                                += (id, matchingSuccessful)
      )
      db.run(addAction)
      Success(log.info(s"Successfully added matching result $matchingSuccessful to instance with id $id."))
    } else {
      log.warning(s"Cannot add matching result, instance with id $id not present.")
      Failure(new RuntimeException(s"Cannot add matching result, instance with id $id not present."))
    }
  }

  override def getMatchingResultsFor(id: Long) : Try[List[Boolean]] = {
    if(hasInstance(id) && hasMatchingResultForInstance(id)){
      val query = instanceMatchingResults.filter(_.instanceId === id).map(_.matchingSuccessful).result
      val result = Await.result(db.run(query), Duration.Inf).toList
      Success(result)
    } else {
      log.warning(s"Cannot get matching results, id $id not present!")
      Failure(new RuntimeException(s"Cannot get matching results, id $id not present!"))
    }
  }

  override def initialize() : Unit = {

    if(dbTest()){
        val tables = List(instances, instanceEvents, instanceLinks, instanceMatchingResults, eventMaps)
        val existing = db.run(MTable.getTables)
        val createAction = existing.flatMap( v => {
          val names = v.map(mt => mt.name.name)
          val createIfNotExist = tables.filter( table =>
            !names.contains(table.baseTableRow.tableName)).map(_.schema.create)
          db.run(DBIO.sequence(createIfNotExist))
        })
        Await.result(createAction, Duration.Inf)


      log.info("Initializing sql instance DAO...")
      removeAll()
      log.info("Successfully initialized.")
    } else {
      log.error("Not found any database with the provided settings.")

      val terminationFuture = system.terminate()

      terminationFuture.onComplete {
        sys.exit(0)
      }
    }

  }


  override def shutdown(): Unit = {
    log.info("Shutting down dynamic instance DAO...")
    removeAll()
    log.info("Shutdown complete.")
  }


  override def getDockerIdFor(id: Long) : Try[String] = {
    getInstance(id) match {
      case Some(instance) => instance.dockerId match {
        case Some(dockerId) => Success(dockerId)
        case None => Failure(new RuntimeException(s"Instance with id $id is not running inside a docker container."))
      }
      case None => Failure(new RuntimeException(s"An instance with id $id was not found."))
    }
  }

  override def setStateFor(id: Long, state: InstanceState.Value) : Try[Unit] = {
    if(hasInstance(id)){
      val query = for { i <- instances if i.id === id } yield i.instanceState
      Await.ready(db.run(query.update(state.toString)), Duration.Inf)
      Success()
    } else {
      Failure(new RuntimeException(s"Instance with id $id was not found."))
    }
  }

  override def addLabelFor(id: Long, label: String) : Try[Unit] = {
    if(hasInstance(id)){
      val instance = getInstance(id).get
      if(instance.labels.exists(l => l.equalsIgnoreCase(label))){
        Success() //Label already present, Success!
      } else {
        if(label.length > configuration.maxLabelLength){
          Failure(new RuntimeException(s"Label exceeds character limit of ${configuration.maxLabelLength}."))
        } else if (label.contains(',')){
          Failure(new RuntimeException(s"Label contains invalid character: comma"))
        } else {

          val labels = instance.labels ++ List[String](label)
          val query = for { single <- instances if single.id === instance.id } yield single.labels
          val updateAction = query.update(getListAsString(labels))

          Await.result(db.run(updateAction), Duration.Inf).toString
          Success()
        }
      }
    } else {
      Failure(new RuntimeException(s"Instance with id $id was not found."))
    }
  }

  override def removeLabelFor(id: Long, label: String): Try[Unit] = {
    if(hasInstance(id)){
      val instance = getInstance(id).get
      if(instance.labels.exists(l => l.equalsIgnoreCase(label))){
        val labelList = instance.labels.filter(_ != label)
        val query = for { single <- instances if single.id === instance.id } yield single.labels
        val updateAction = query.update(getListAsString(labelList))
        Await.result(db.run(updateAction), Duration.Inf).toString
        Success()

      } else {
        val msg = s"Label $label is not present for the instance."
        log.warning(msg)
        Failure(new RuntimeException(msg))
      }
    } else {
      val msg = s"Instance with id $id not present."
      log.warning(msg)
      Failure(new RuntimeException(msg))
    }
  }

  override def addEventFor(id: Long, event: RegistryEvent) : Try[Unit] = {

    val payload = event.payload.toJson(registryEventPayloadFormat).toString

    if(hasInstance(id)){
      val addEvent: Future[Long] =
        db.run(instanceEvents.map(c => (c.eventType, c.payload)) returning instanceEvents.map(_.id)  += (event.eventType.toString, payload))
      val eventId = Await.result(addEvent, Duration.Inf)
      db.run(eventMaps.map(c => (c.instanceId, c.eventId)) += (id, eventId))

      Success()
    } else {
      Failure(new RuntimeException(s"Instance with id $id was not found."))
    }
  }


  override def getEventsFor(id: Long, startPage: Long, pageItems: Long, limitItems: Long) : Try[List[RegistryEvent]] = {
    if(hasInstance(id) && hasInstanceEvents(id)){
      val skip = startPage * pageItems
      val take = if(limitItems == 0) configuration.pageLimit else limitItems
      val eventMapIds = eventMaps.filter(_.instanceId === id).map(_.eventId)
      val resultAll = if(limitItems != 0) {Await.result(db.run(instanceEvents.filter(_.id in eventMapIds).drop(skip).take(take).result), Duration.Inf)}
                      else {Await.result(db.run(instanceEvents.filter(_.id in eventMapIds).drop(skip).take(take).result), Duration.Inf)}
      val listAll = List() ++ resultAll.map(result => dataToObjectRegistryEvent(result._2, result._3, result._4))
      Success(listAll)

    } else {
      log.warning(s"Cannot get events, id $id not present!")
      Failure(new RuntimeException(s"Cannot get events, id $id not present!"))
    }
  }

  override def addLink(link: InstanceLink) : Try[Unit] = {
    if(hasInstance(link.idFrom) && hasInstance(link.idTo)){

      //If new link is in state 'Assigned': Set any link that previously was assigned to 'outdated'
      //IMPORTANT: Only works bc every component has exactly one dependency!
      if(link.linkState == LinkState.Assigned){
        for (prevLink <- getLinksFrom(link.idFrom, Some(LinkState.Assigned))){
          updateLink(InstanceLink(prevLink.idFrom, prevLink.idTo, LinkState.Outdated))
        }
      }

      if(getLinksFrom(link.idFrom).exists(l => l.idTo == link.idTo)){
        //There already is a link between the two instances. Update it instead of adding a new one
        updateLink(link)
      } else {
        addLinkFromInstanceLink(link)
      }
      Success()
    } else {
      Failure(new RuntimeException("Cannot add link, ids not known."))
    }
  }

  private def addLinkFromInstanceLink(link: InstanceLink){
    val addAction: DBIO[Unit] = DBIO.seq(
      instanceLinks.map(c => (c.idFrom, c.idTo, c.linkState))
        += (link.idFrom, link.idTo, link.linkState.toString)
    )
    db.run(addAction)
  }

  override def updateLink(link: InstanceLink) : Try[Unit] = {
    val query = for {l <- instanceLinks if l.idFrom === link.idFrom && l.idTo === link.idTo} yield l.linkState
    Await.result(db.run(query.update(link.linkState.toString)), Duration.Inf)
    Success()
  }


  override def getLinksFrom(id: Long, state: Option[LinkState] = None) : List[InstanceLink] = {
    var listLinks = List[InstanceLink]()
    if(state.isDefined){
      val resultAll = Await.result(db.run(instanceLinks.filter( x => x.idFrom === id && x.linkState === state.get.toString).result), Duration.Inf)
      val listAll = List() ++ resultAll.map(_.value)
      listLinks = listAll.map(single => dataToObjectInstanceLinks(single._2, single._3, single._4))
    } else {
      val resultAll = Await.result(db.run(instanceLinks.filter( x => x.idFrom === id).result), Duration.Inf)
      val listAll = List() ++ resultAll.map(_.value)
      listLinks = listAll.map(single => dataToObjectInstanceLinks(single._2, single._3, single._4))
    }
    listLinks
  }


  override def getLinksTo(id:Long, state: Option[LinkState] = None) : List[InstanceLink] = {
    var listLinks = List[InstanceLink]()
    if(state.isDefined){
      val resultAll = Await.result(db.run(instanceLinks.filter( x => x.idTo === id && x.linkState === state.get.toString).result), Duration.Inf)
      val listAll = List() ++ resultAll.map(_.value)
      listLinks = listAll.map(single => dataToObjectInstanceLinks(single._2, single._3, single._4))
    } else {
      val resultAll = Await.result(db.run(instanceLinks.filter( x => x.idTo === id).result), Duration.Inf)
      val listAll = List() ++ resultAll.map(_.value)
      listLinks = listAll.map(single => dataToObjectInstanceLinks(single._2, single._3, single._4))
    }
    listLinks
  }

  private def dataToObjectInstanceLinks(eventType: Long, payload: Long, state: String): InstanceLink = {
    InstanceLink.apply(eventType, payload, getLinkStateFromString(state))
  }

  private def getComponentTypeFromString(componentType: String): ComponentType ={
    val result = componentType match {
      case "Crawler" => ComponentType.Crawler
      case "WebApi" => ComponentType.WebApi
      case "WebApp" => ComponentType.WebApp
      case "DelphiManagement" => ComponentType.DelphiManagement
      case "ElasticSearch" => ComponentType.ElasticSearch
    }
    result
  }

  private def getInstanceStateFromString(instanceState: String): InstanceState ={
    val result = instanceState match {
      case "Deploying" => InstanceState.Deploying
      case "Running" => InstanceState.Running
      case "Stopped" => InstanceState.Stopped
      case "Failed" => InstanceState.Failed
      case "Paused" => InstanceState.Paused
      case "NotReachable" => InstanceState.NotReachable
    }
    result
  }

  private def getEventTypeFromString(eventType: String): EventType ={
    val result = eventType match {
      case "StateChangedEvent" => EventType.StateChangedEvent
      case "InstanceAddedEvent" => EventType.InstanceAddedEvent
      case "InstanceRemovedEvent" => EventType.InstanceRemovedEvent
      case "NumbersChangedEvent" => EventType.NumbersChangedEvent
      case "DockerOperationErrorEvent" => EventType.DockerOperationErrorEvent
      case "LinkAddedEvent" => EventType.LinkAddedEvent
      case "LinkStateChangedEvent" => EventType.LinkStateChangedEvent
    }
    result
  }

  private def getLinkStateFromString(linkState: String): LinkState ={
    val result = linkState match {
      case "Assigned" => LinkState.Assigned
      case "Failed" => LinkState.Failed
      case "Outdated" => LinkState.Outdated
    }
    result
  }

  private def getListAsString(listItems: List[String]): String ={
    listItems mkString ","
  }
  private def getListFromString(listItems: String): List[String] ={
    listItems.split(",").toList
  }

  private def dataToObjectInstance(options : Option[(Long, String, Long, String, String, Option[String], String, String, Option[String])]): Instance = {
    val optionValue = options.get
    val componentTypeObj = getComponentTypeFromString(optionValue._5)
    val instanceStateObj = getInstanceStateFromString(optionValue._7)
    val labelsList = getListFromString(optionValue._8)
    val linksTo = getLinksTo(optionValue._1)
    val LinksFrom = getLinksFrom(optionValue._1)

    val traefikConfig = optionValue._9.map(host => TraefikConfiguration(host, configuration.traefikUri))

    Instance(Option(optionValue._1),
      optionValue._2,
      optionValue._3,
      optionValue._4,
      componentTypeObj,
      optionValue._6,
      instanceStateObj,
      labelsList,
      linksTo,
      LinksFrom,
      traefikConfig)
  }

  private def removeInstancesWithId(id: Long): Unit ={
    val q = instances.filter(_.id === id)
    val action = q.delete
    db.run(action)
  }

  private def removeInstanceMatchingResultsWithId(id: Long): Unit ={
    val q = instanceMatchingResults.filter(_.instanceId === id)
    val action = q.delete
    db.run(action)
  }

  private def removeInstanceEventsWithId(id: Long): Unit ={
    val resultAll = Await.result(db.run(eventMaps.filter(_.instanceId === id).result), Duration.Inf)
    resultAll.foreach(c => removeEvents(c._3))

    val q = eventMaps.filter(_.instanceId === id)
    val action = q.delete
    db.run(action)
  }

  private def removeInstanceLinksWithId(id: Long) : Unit = {
    val q = instanceLinks.filter(links => links.idFrom === id || links.idTo === id)
    val action = q.delete
    db.run(action)
  }

  private def removeAllInstances() : Unit ={
    val action = instances.delete
    db.run(action)
  }

  private def removeAllInstanceMatchingResults() : Unit ={
    val action = instanceMatchingResults.delete
    db.run(action)
  }

  private def removeAllInstanceEvents() : Unit ={
    val deleteInstanceEvents = instanceEvents.delete
    db.run(deleteInstanceEvents)

    val deleteEventMaps = eventMaps.delete
    db.run(deleteEventMaps)
  }

  private def removeAllInstanceLinks() : Unit ={
    val action = instanceLinks.delete
    db.run(action)
  }

  private def hasMatchingResultForInstance(id: Long): Boolean = {
    Await.result(db.run(instanceMatchingResults.filter(_.instanceId === id).exists.result), Duration.Inf)
  }

  private def hasInstanceEvents(id: Long) : Boolean = {
    Await.result(db.run(eventMaps.filter(_.instanceId === id).exists.result), Duration.Inf)
  }

  private def dataToObjectRegistryEvent(eventType: String, timestamp: DateTime, payload: String): RegistryEvent = {
    RegistryEvent.apply(getEventTypeFromString(eventType), registryEventPayloadFormat.read(payload.parseJson), timestamp)
  }

  private def removeEvents(id: Long): Unit = {
    val q = instanceEvents.filter(_.id === id)
    val action = q.delete
    db.run(action)
  }

  def dbTest(): Boolean = {
    try {
      val timeoutDBSeconds = 5
      db.createSession.conn.isValid(timeoutDBSeconds)
    } catch {
      case e: Throwable => throw e
    }
  }

  def setDatabaseConfiguration(databaseHost: String = "",
                               databaseName: String = "",
                               databaseDriver: String = "",
                               databaseUsername: String = "",
                               databasePassword: String = ""): Unit = {
    db = Database.forURL(databaseHost + databaseName, driver = databaseDriver, user = databaseUsername, password = databasePassword)
    initialize()
  }

}
