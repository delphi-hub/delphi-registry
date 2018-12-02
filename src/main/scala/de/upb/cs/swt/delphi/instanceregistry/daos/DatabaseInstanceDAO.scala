package de.upb.cs.swt.delphi.instanceregistry.daos

import java.io.{File, IOException, PrintWriter}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.EventEnums.EventType
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Configuration, Registry}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.LinkEnums.LinkState
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.{InstanceJsonSupport, _}
import slick.lifted.TableQuery

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import slick.jdbc.H2Profile.api._
import slick.jdbc.meta.MTable
import spray.json._

import scala.concurrent.duration.Duration
import scala.io.Source


class DatabaseInstanceDAO (configuration : Configuration) extends InstanceDAO with AppLogging with InstanceJsonSupport with EventJsonSupport{

  private val instances : TableQuery[Instances] = TableQuery[Instances]
  private val instanceMatchingResults : TableQuery[InstanceMatchingResults] = TableQuery[InstanceMatchingResults]
  private val instanceEvents : TableQuery[InstanceEvents] = TableQuery[InstanceEvents]
  private val instanceLinks : TableQuery[InstanceLinks] = TableQuery[InstanceLinks]

  implicit val system : ActorSystem = Registry.system
  implicit val materializer : ActorMaterializer = ActorMaterializer()
  implicit val ec : ExecutionContext = system.dispatcher

  val db = Database.forURL(configuration.databaseHost + configuration.databaseName, driver = configuration.databaseDriver, user = configuration.databaseUsername, password = configuration.databasePassword)

  override def addInstance(instance : Instance) : Try[Unit] = {
    //Verify ID is present in instance
    if(instance.id.isEmpty){
      val msg = s"Cannot add instance ${instance.name}, id is empty!"
      log.warning(msg)
      Failure(new RuntimeException(msg))
    } else {
      if(!hasInstance(instance.id.get)){
        val id = instance.id.get
        val host = instance.host
        val port = instance.portNumber
        val name = instance.name
        val componentType = instance.componentType.toString
        val dockerId = instance.dockerId.getOrElse("")
        val instanceState = instance.instanceState.toString
        val labels = getListAsString(instance.labels)

        val addFuture: Future[Long] = db.run((instances returning instances.map(_.id)) += (id, host, port, name, componentType, dockerId, instanceState, labels))
        val instanceId = Await.result(addFuture, Duration.Inf)

        dumpToRecoveryFile()
        Success(log.info(s"Added instance ${instance.name} with id ${instanceId} to database."))
      } else {
        val msg = s"Cannot add instance ${instance.name}, id ${instance.id} already present."
        log.warning(msg)
        Failure(new RuntimeException(msg))
      }
    }
  }

  override def hasInstance(id: Long) : Boolean = {
      Await.result(db.run(instances.filter(_.id === id).exists.result), Duration.Inf)
  }

  override def removeInstance(id: Long) : Try[Unit] = {
    if(hasInstance(id)) {
      removeInstancesWithId(id)
      removeInstanceMatchingResultsWithId(id)
      removeinstanceEventsWithId(id)
      removeInstanceLinksWithId(id)
      dumpToRecoveryFile()
      Success(log.info(s"Successfully removed instance with id $id."))
    }else{
      val msg = s"Cannot remove instance with id $id, that id is not present."
      log.warning(msg)
      Failure(new RuntimeException(msg))
    }
  }

  override def getInstance(id: Long) : Option[Instance] = {
    if(hasInstance(id)) {
      var result = Await.result(db.run(instances.filter(_.id === id).result.headOption), Duration.Inf)
      Some(dataToObjectInstance(result))
    } else {
      None
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
    dumpToRecoveryFile()
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
        val tables = List(instances, instanceEvents, instanceLinks, instanceMatchingResults)
        val existing = db.run(MTable.getTables)
        val createAction = existing.flatMap( v => {
          val names = v.map(mt => mt.name.name)
          val createIfNotExist = tables.filter( table =>
            (!names.contains(table.baseTableRow.tableName))).map(_.schema.create)
          db.run(DBIO.sequence(createIfNotExist))
        })
        Await.result(createAction, Duration.Inf)


      log.info("Initializing dynamic instance DAO...")
      clearData()
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
    clearData()
    deleteRecoveryFile()
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
      val instance = getInstance(id).get
      val newInstance = Instance(instance.id,
        instance.host,
        instance.portNumber,
        instance.name,
        instance.componentType,
        instance.dockerId,
        state,
        instance.labels,
        instance.linksTo,
        instance.linksFrom)
      removeInstancesWithId(instance.id.get)
      addInstance(newInstance)
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

          println(Await.result(db.run(updateAction), Duration.Inf).toString)
          Success()
        }
      }
    } else {
      Failure(new RuntimeException(s"Instance with id $id was not found."))
    }
  }

  override def addEventFor(id: Long, event: RegistryEvent) : Try[Unit] = {

    val payload = event.payload.toJson(registryEventPayloadFormat).toString

    if(hasInstance(id)){
      val addAction: DBIO[Unit] = DBIO.seq(
        instanceEvents.map(c => (c.instanceId, c.eventType, c.payload))
          += (id, event.eventType.toString, payload)
      )
      db.run(addAction)
      Success()
    } else {
      Failure(new RuntimeException(s"Instance with id $id was not found."))
    }
  }


  override def getEventsFor(id: Long) : Try[List[RegistryEvent]] = {
    if(hasInstance(id) && hasInstanceEvents(id)){
      val resultAll = Await.result(db.run(instanceEvents.filter(_.instanceId === id).result), Duration.Inf)
      val listAll = List() ++ resultAll.map(_.value)
      val listInstance = listAll.map(c => dataToObjectRegistryEvent(c._3, c._4))
      Success(listInstance)
    } else {
      log.warning(s"Cannot get events, id $id not present!")
      Failure(new RuntimeException(s"Cannot get events, id $id not present!"))
    }
  }

  override def addLink(link: InstanceLink) : Try[Unit] = {
    if(hasInstance(link.idFrom) && hasInstance(link.idTo)){

      if(getLinksFrom(link.idFrom).exists(l => l.idTo == link.idTo)){
        //There already is a link between the two instances. Update it instead of adding a new one
        updateLink(link)
      } else {
        //If new link is in state 'Assigned': Set any link that previously was assigned to 'outdated'
        //IMPORTANT: Only works bc every component has exactly one dependency!
        if(link.linkState == LinkState.Assigned){
          for (prevLink <- getLinksFrom(link.idFrom, Some(LinkState.Assigned))){
            updateLink(InstanceLink(prevLink.idFrom, prevLink.idTo, LinkState.Outdated))
          }
        }
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
    val linksMatching = Await.result(db.run(instanceLinks.filter( x => x.idFrom === link.idFrom && x.idTo === link.idTo).result), Duration.Inf)

    if(linksMatching.nonEmpty){
      for(l <- linksMatching){
        removeInstanceLinksWithId(l._1)
        addLinkFromInstanceLink(dataToObjectInstanceLinks(l._2, l._3, l._4))
      }
      Success()
    } else {
      Failure(new RuntimeException(s"Cannot update link $link, this link is not present in the dao."))
    }
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

  private def addLinksToInstance(instance: Instance): Instance = {
    val linksTo = getLinksTo(instance.id.getOrElse(-1))
    val linksFrom = getLinksFrom(instance.id.getOrElse(-1))

    Instance(
      instance.id,
      instance.host,
      instance.portNumber,
      instance.name,
      instance.componentType,
      instance.dockerId,
      instance.instanceState,
      instance.labels,
      linksTo,
      linksFrom
    )
  }

  private[daos] def clearData() : Unit = {
    removeAllInstances()
    removeAllInstanceMatchingResults()
    removeAllInstanceEvents()
    removeAllInstanceLinks()
  }

  private[daos] def dumpToRecoveryFile() : Unit = {
    log.debug(s"Dumping data to recovery file ${configuration.recoveryFileName} ...")
    val writer = new PrintWriter(new File(configuration.recoveryFileName))
    writer.write(allInstances().toJson(listFormat(instanceFormat)).toString())
    writer.flush()
    writer.close()
    log.debug(s"Successfully wrote to recovery file.")
  }

  private[daos] def deleteRecoveryFile() : Unit = {
    log.info("Deleting data recovery file...")
    if(new File(configuration.recoveryFileName).delete()){
      log.info(s"Successfully deleted data recovery file ${configuration.recoveryFileName}.")
    } else {
      log.warning(s"Failed to delete data recovery file ${configuration.recoveryFileName}.")
    }
  }

  private[daos] def tryInitFromRecoveryFile() : Unit = {
    try {
      log.info(s"Attempting to load data from recovery file ${configuration.recoveryFileName} ...")
      val recoveryFileContent = Source.fromFile(configuration.recoveryFileName).getLines()

      if(!recoveryFileContent.hasNext){
        log.warning(s"Recovery file invalid, more than one line found.")
        throw new IOException("Recovery file invalid.")
      }

      val jsonString : String = recoveryFileContent.next()

      val instanceList = jsonString.parseJson.convertTo[List[Instance]](listFormat(instanceFormat))

      log.info(s"Successfully loaded ${instanceList.size} instance from recovery file. Initializing...")

      clearData()
      for(instance <- instanceList){
        addInstance(instance)
      }

      log.info(s"Successfully initialized from recovery file.")

    } catch  {
      case _ : IOException =>
        log.info(s"Recovery file ${configuration.recoveryFileName} not found, so no data will be loaded.")
      case dx : DeserializationException =>
        log.error(dx, "An error occurred while deserializing the contents of the recovery file.")
    }

  }

  private def getComponetTypeFromString(componentType: String): ComponentType ={
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

  private def dataToObjectInstance(options : Option[(Long, String, Long, String, String, String, String, String)]): Instance = {
    val optionValue = options.get
    val componentTypeObj = getComponetTypeFromString(optionValue._5)
    val instanceStateObj = getInstanceStateFromString(optionValue._7)
    val labelsList = getListFromString(optionValue._8)
    val linksTo = getLinksTo(optionValue._1)
    val LinksFrom = getLinksFrom(optionValue._1)
    Instance.apply(Option(optionValue._1), optionValue._2, optionValue._3, optionValue._4, componentTypeObj, Option(optionValue._6), instanceStateObj, labelsList, linksTo, LinksFrom)
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

  private def removeinstanceEventsWithId(id: Long): Unit ={
    val q = instanceEvents.filter(_.instanceId === id)
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
    val action = instanceEvents.delete
    db.run(action)
  }

  private def removeAllInstanceLinks() : Unit ={
    val action = instanceLinks.delete
    db.run(action)
  }

  private def hasMatchingResultForInstance(id: Long): Boolean = {
    Await.result(db.run(instanceMatchingResults.filter(_.id === id).exists.result), Duration.Inf)
  }

  private def hasInstanceEvents(id: Long) : Boolean = {
    Await.result(db.run(instanceEvents.filter(_.instanceId === id).exists.result), Duration.Inf)
  }

  private def dataToObjectRegistryEvent(eventType: String, payload: String): RegistryEvent = {
    RegistryEvent.apply(getEventTypeFromString(eventType), registryEventPayloadFormat.read(payload.parseJson))
  }


  def dbTest(): Boolean = {
    try {
      db.createSession.conn.isValid(5)
    } catch {
      case e: Throwable => false
    }
  }

}
