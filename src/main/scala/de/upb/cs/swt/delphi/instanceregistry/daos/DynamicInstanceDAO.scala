package de.upb.cs.swt.delphi.instanceregistry.daos

import akka.actor.ActorSystem
import de.upb.cs.swt.delphi.instanceregistry.{AppLogging, Server}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.Instance
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/**
  * Implementation of the instance data access object that keeps its data in memory
  * instead of using a persistent storage.
  */
class DynamicInstanceDAO extends InstanceDAO with AppLogging {

  private val instances : mutable.Set[Instance] = new mutable.HashSet[Instance]()
  implicit val system : ActorSystem = Server.system

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

  override def getAllInstances(): List[Instance] = {
    List() ++ instances
  }

  override def clearAll() : Unit = {
    instances.clear()
  }

}
