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

import java.io.{File, IOException, PrintWriter}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Configuration, Registry}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model._
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.LinkEnums.LinkState

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import spray.json._

import scala.io.Source

/**
  * Implementation of the instance data access object that keeps its data in memory
  * instead of using a persistent storage.
  */
class DynamicInstanceDAO (configuration : Configuration) extends InstanceDAO with AppLogging with InstanceJsonSupport {

  private val instances : mutable.Set[Instance] = new mutable.HashSet[Instance]()
  private val instanceMatchingResults : mutable.Map[Long, mutable.MutableList[Boolean]] = new mutable.HashMap[Long,mutable.MutableList[Boolean]]()
  private val instanceEvents : mutable.Map[Long, mutable.MutableList[RegistryEvent]] = new mutable.HashMap[Long, mutable.MutableList[RegistryEvent]]()
  private val instanceLinks: mutable.Set[InstanceLink] = new mutable.HashSet[InstanceLink]()

  implicit val system : ActorSystem = Registry.system
  implicit val materializer : ActorMaterializer = Registry.materializer
  implicit val ec : ExecutionContext = system.dispatcher


  override def addInstance(instance: Instance): Try[Long] = {
    val id = nextId()

    val newInstance = Instance(Some(id), instance.host, instance.portNumber, instance.name, instance.componentType,
      instance.dockerId, instance.instanceState,instance.labels, instance.linksTo, instance.linksFrom, instance.traefikConfiguration)
    instances.add(newInstance)
    instanceMatchingResults.put(newInstance.id.get, mutable.MutableList())
    instanceEvents.put(newInstance.id.get, mutable.MutableList())
    dumpToRecoveryFile()
    log.info(s"Added instance ${newInstance.name} with id ${newInstance.id.get} to database.")
    Success(id)
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
      instanceEvents.remove(id)
      instanceLinks.retain(link => link.idFrom != id && link.idTo != id)
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
      Some(addLinksToInstance(instance))
    } else {
      None
    }
  }

  override def updateInstance(instance: Instance):Try[Unit] = {
    if(hasInstance(instance.id.get)){
      instances.remove(getInstance(instance.id.get).get)
      instances.add(instance)
      Success()
    } else {
      Failure(new RuntimeException(s"Cannot update instance, id ${instance.id.get} not found."))
    }
  }

  override def getInstancesOfType(componentType: ComponentType): List[Instance] = {
    List() ++ instances filter {i => i.componentType == componentType} map addLinksToInstance
  }

  override def allInstances(): List[Instance] = {
    List() ++ instances map addLinksToInstance
  }

  override def removeAll() : Unit = {
    instances.clear()
    instanceMatchingResults.clear()
    instanceEvents.clear()
    instanceLinks.clear()
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
    //tryInitFromRecoveryFile()
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

  override def setStateFor(id: Long, state: InstanceState.Value): Try[Unit] = {
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
        instance.linksFrom,
        instance.traefikConfiguration)
      instances filter {i => i.id == instance.id} map instances.remove
      instances.add(newInstance)
      Success()
    } else {
      Failure(new RuntimeException(s"Instance with id $id was not found."))
    }
  }

  override def addLabelFor(id: Long, label: String): Try[Unit] = {
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
          val newInstance = Instance(instance.id,
            instance.host,
            instance.portNumber,
            instance.name,
            instance.componentType,
            instance.dockerId,
            instance.instanceState,
            instance.labels ++ List[String](label),
            instance.linksTo,
            instance.linksFrom,
            instance.traefikConfiguration)
          instances filter {i => i.id == instance.id} map instances.remove
          instances.add(newInstance)
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
        val newInstance = Instance(instance.id,
          instance.host,
          instance.portNumber,
          instance.name,
          instance.componentType,
          instance.dockerId,
          instance.instanceState,
          labelList,
          instance.linksTo,
          instance.linksFrom,
          instance.traefikConfiguration)
        instances filter {i => i.id == instance.id} map instances.remove
        instances.add(newInstance)
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
    if(hasInstance(id)){
      instanceEvents(id) += event
      Success()
    } else {
      Failure(new RuntimeException(s"Instance with id $id was not found."))
    }
  }

  override def getEventsFor(id: Long) : Try[List[RegistryEvent]] = {
    if(hasInstance(id) && instanceEvents.contains(id)){
      Success(List() ++ instanceEvents(id))
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
        instanceLinks.add(link)
      }
      Success()
    } else {
      Failure(new RuntimeException("Cannot add link, ids not known."))
    }
  }

  override def updateLink(link: InstanceLink) : Try[Unit] = {
    val linksMatching = instanceLinks.filter(l => l.idFrom == link.idFrom && l.idTo == link.idTo)

    if(linksMatching.nonEmpty){
      for(l <- linksMatching){
        instanceLinks.remove(l)
      }
      instanceLinks.add(link)
      Success()
    } else {
      Failure(new RuntimeException(s"Cannot update link $link, this link is not present in the dao."))
    }
  }

  override def getLinksFrom(id: Long, state: Option[LinkState] = None) : List[InstanceLink] = {
    val links = instanceLinks.filter(link => link.idFrom == id)

    if(state.isDefined){
      List() ++ links.filter(link => link.linkState == state.get)
    } else {
      List() ++ links
    }
  }

  override def getLinksTo(id:Long, state: Option[LinkState] = None) : List[InstanceLink] = {
    val links = instanceLinks.filter(link => link.idTo == id)

    if(state.isDefined){
      List() ++ links.filter(link => link.linkState == state.get)
    } else {
      List() ++ links
    }
  }

  private def addLinksToInstance(instance: Instance): Instance = {
    val linksTo = List[InstanceLink]() ++ instanceLinks.filter(link => link.idTo == instance.id.getOrElse(-1))
    val linksFrom = List[InstanceLink]() ++ instanceLinks.filter(link => link.idFrom == instance.id.getOrElse(-1))

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
      linksFrom,
      instance.traefikConfiguration
    )
  }

  private[daos] def clearData() : Unit = {
    instances.clear()
    instanceMatchingResults.clear()
    instanceEvents.clear()
    instanceLinks.clear()
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

  private def nextId(): Long = {
    if(instances.isEmpty){
      0L
    } else {
      (instances.map(i => i.id.getOrElse(0L)) max) + 1L
    }
  }
}
