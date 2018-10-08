package de.upb.cs.swt.delphi.instanceregistry.daos

import java.io.{File, IOException, PrintWriter}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Configuration, Registry}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.{Instance, JsonSupport}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import spray.json._

import scala.io.Source

/**
  * Implementation of the instance data access object that keeps its data in memory
  * instead of using a persistent storage.
  */
class DynamicInstanceDAO (configuration : Configuration) extends InstanceDAO with AppLogging with JsonSupport {

  private val instances : mutable.Set[Instance] = new mutable.HashSet[Instance]()
  private val instanceMatchingResults : mutable.Map[Long, mutable.MutableList[Boolean]] = new mutable.HashMap[Long,mutable.MutableList[Boolean]]()

  implicit val system : ActorSystem = Registry.system
  implicit val materializer : ActorMaterializer = ActorMaterializer()
  implicit val ec : ExecutionContext = system.dispatcher


  override def addInstance(instance: Instance): Try[Unit] = {
    //Verify ID is present in instance
    if(instance.id.isEmpty){
      val msg = s"Cannot add instance ${instance.name}, id is empty!"
      log.warning(msg)
      Failure(new RuntimeException(msg))
    } else {
      //Verify id is not already present in instances!
      if(!hasInstance(instance.id.get)){
        instances.add(instance)
        instanceMatchingResults.put(instance.id.get, mutable.MutableList())
        dumpToRecoveryFile()
        Success(log.info(s"Added instance ${instance.name} with id ${instance.id} to database."))
      } else {
        val msg = s"Cannot add instance ${instance.name}, id ${instance.id} already present."
        log.warning(msg)
        Failure(new RuntimeException(msg))
      }
    }
  }

  override def hasInstance(id: Long): Boolean = {
    //addInstance verifies that id : Option[Long] is not empty, so can apply .get here!
    val query = instances filter {i => i.id.get == id}
    query.nonEmpty
  }

  override def removeInstance(id: Long): Try[Unit] = {
    if(hasInstance(id)){
      //AddInstance verifies that id is always present, hasInstance verifies that find will return an instance
      instances.remove(instances.find(i => i.id.get == id).get)
      instanceMatchingResults.remove(id)
      dumpToRecoveryFile()
      Success(log.info(s"Successfully removed instance with id $id."))
    } else {
      val msg = s"Cannot remove instance with id $id, that id is not present."
      log.warning(msg)
      Failure(new RuntimeException(msg))
    }
  }

  override def getInstance(id: Long): Option[Instance] = {
    if(hasInstance(id)) {
      val query = instances filter {i => i.id.get == id}
      val instance  = query.iterator.next()
      Some(instance)
    } else {
      None
    }
  }

  override def getInstancesOfType(componentType: ComponentType): List[Instance] = {
    List() ++ instances filter {i => i.componentType == componentType}
  }

  override def allInstances(): List[Instance] = {
    List() ++ instances
  }

  override def removeAll() : Unit = {
    instances.clear()
    instanceMatchingResults.clear()
    dumpToRecoveryFile()
  }

  override def addMatchingResult(id: Long, matchingSuccessful: Boolean): Try[Unit] = {
    if(hasInstance(id)){
      instanceMatchingResults.get(id) match {
        case Some(resultList) =>
          resultList += matchingSuccessful
          Success(log.info(s"Successfully added matching result $matchingSuccessful to instance with id $id."))
        case None =>
          log.warning(s"Could not add matching result, list for instance with id $id not present!")
          Failure(new RuntimeException("No matching result list present"))
      }
    } else {
      log.warning(s"Cannot add matching result, instance with id $id not present.")
      Failure(new RuntimeException(s"Cannot add matching result, instance with id $id not present."))
    }
  }

  override def getMatchingResultsFor(id: Long): Try[List[Boolean]] = {
    if(hasInstance(id) && instanceMatchingResults.contains(id)){
      Success(List() ++ instanceMatchingResults(id))
    } else {
      log.warning(s"Cannot get matching results, id $id not present!")
      Failure(new RuntimeException(s"Cannot get matching results, id $id not present!"))
    }
  }

  override def initialize(): Unit = {
    log.info("Initializing dynamic instance DAO...")
    clearData()
    tryInitFromRecoveryFile()
    log.info("Successfully initialized.")
  }

  override def shutdown() : Unit = {
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

  override def setStateFor(id: Long, state: InstanceState.Value): Try[Unit] ={
    if(hasInstance(id)){
      val instance = getInstance(id).get
      val newInstance = Instance(instance.id, instance.host, instance.portNumber, instance.name, instance.componentType, instance.dockerId, state)
      instances.remove(instance)
      instances.add(newInstance)
      Success()
    } else {
      Failure(new RuntimeException(s"Instance with id $id was not found."))
    }
  }

  private[daos] def clearData() : Unit = {
    instances.clear()
    instanceMatchingResults.clear()
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

}
