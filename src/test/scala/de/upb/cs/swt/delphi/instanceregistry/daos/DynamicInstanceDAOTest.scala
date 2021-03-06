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

import de.upb.cs.swt.delphi.instanceregistry.Configuration
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.{Instance, InstanceLink, RegistryEventFactory}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.LinkEnums.LinkState
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

class DynamicInstanceDAOTest extends FlatSpec with Matchers with BeforeAndAfterEach{

  val dao : DynamicInstanceDAO = new DynamicInstanceDAO(new Configuration())

  private def buildInstance(id : Int) : Instance = {
    Instance(Some(id), "https://localhost", 12345, "TestInstance", ComponentType.Crawler, None, InstanceState.Stopped, List.empty[String],
      List.empty[InstanceLink], List.empty[InstanceLink])
  }

  override protected def beforeEach() : Unit = {
    dao.deleteRecoveryFile()
    for(i <- 1 to 3){
      dao.addInstance(buildInstance(i))
    }
  }

  "The instance DAO" must "be able to add an get an instance with a new id" in {
    val idOption = dao.addInstance(buildInstance(id = 42))
    assert(idOption.isSuccess)
    assert(dao.allInstances().size == 4)
    assert(dao.hasInstance(idOption.get))
    assert(dao.removeInstance(idOption.get).isSuccess)
  }

  it must "not assign unique ids ignoring the ones provided by the parameter" in {
    val idOption = dao.addInstance(buildInstance(3))
    assert(idOption.isSuccess)
    assert(dao.allInstances().size == 4)
    assert(dao.hasInstance(idOption.get))
    assert(dao.removeInstance(idOption.get).isSuccess)
  }

  it must "return true on hasInstance for any present id" in {
    for(i <- 0 to 2){
      assert(dao.hasInstance(i))
    }
  }

  it must "return false on hasInstance for any id not present" in {
    assert(!dao.hasInstance(-1))
    assert(!dao.hasInstance(Long.MaxValue))
    assert(!dao.hasInstance(4))
  }

  it must "return instances with the correct id on getInstance" in {
    for(i <- 0 to 2){
      val instance = dao.getInstance(i)
      assert(instance.isDefined)
      assert(instance.get.id.isDefined)
      assert(instance.get.id.get == i)
    }
  }

  it must "return instance with the correct type on getInstanceOfType" in {
    val compTypeInstances = dao.getInstancesOfType(ComponentType.Crawler)
    assert(compTypeInstances.size == 3)

    for(instance <- compTypeInstances){
      assert(instance.componentType == ComponentType.Crawler)
    }
  }

  it must "remove instances that are present in the DAO" in {
    for(i <- 0 to 2){
      assert(dao.removeInstance(i).isSuccess)
      assert(!dao.hasInstance(i))
    }
    assert(dao.allInstances().isEmpty)
  }

  it must "not change the data on removing invalid IDs" in {
    assert(dao.removeInstance(-1).isFailure)
    assert(dao.removeInstance(Long.MaxValue).isFailure)
    assert(dao.removeInstance(4).isFailure)
  }

  it must "remove all instance on removeAll" in {
    dao.removeAll()
    assert(dao.allInstances().isEmpty)
  }

  it must "have an empty list of matching results for newly added instances" in {
    val idOption = dao.addInstance(buildInstance(4))
    assert(dao.getMatchingResultsFor(idOption.get).isSuccess)
    assert(dao.getMatchingResultsFor(idOption.get).get.isEmpty)
  }

  it must "keep the correct order of matching results posted" in {
    assert(dao.addMatchingResult(2, matchingSuccessful = true).isSuccess)
    assert(dao.addMatchingResult(2, matchingSuccessful = true).isSuccess)
    assert(dao.addMatchingResult(2, matchingSuccessful = false).isSuccess)

    assert(dao.getMatchingResultsFor(2).isSuccess)
    assert(dao.getMatchingResultsFor(2).get.head)
    assert(dao.getMatchingResultsFor(2).get (1))
    assert(!dao.getMatchingResultsFor(2).get (2))

  }

  it must "remove the matching results when the instance is removed" in {
    assert(dao.removeInstance(2).isSuccess)
    assert(dao.getMatchingResultsFor(2).isFailure)
  }

  it must "be able to change the state for arbitrary state transitions" in {
    assert(dao.getInstance(1).get.instanceState == InstanceState.Stopped)
    assert(dao.setStateFor(1, InstanceState.Failed).isSuccess)
    assert(dao.getInstance(1).get.instanceState == InstanceState.Failed)
    assert(dao.setStateFor(1, InstanceState.Running).isSuccess)
    assert(dao.getInstance(1).get.instanceState == InstanceState.Running)
  }

  it must "fail when setting state for invalid ids" in {
    assert(dao.setStateFor(42, InstanceState.Failed).isFailure)
    assert(dao.setStateFor(Int.MaxValue, InstanceState.Running).isFailure)
  }

  it must "fail to get docker ids from instances without any docker id" in {
    assert(dao.getDockerIdFor(1).isFailure)
    assert(dao.getDockerIdFor(2).isFailure)
  }

  it must "return the correct docker ids for instances with a docker id" in {
    val idOption = dao.addInstance(Instance(Some(42), "http://localhost", 33449, "AnyName",
      ComponentType.WebApi, Some("dockerId"), InstanceState.Running, List.empty[String], List.empty[InstanceLink], List.empty[InstanceLink] ))
    assert(idOption.isSuccess)
    assert(dao.getDockerIdFor(idOption.get).isSuccess)
    assert(dao.getDockerIdFor(idOption.get).get.equals("dockerId"))
  }

  it must "add events only to instances that have been registered" in {
    assert(dao.getEventsFor(1,0, 0, 0).isSuccess)
    assert(dao.getEventsFor(1,0, 0, 0).get.isEmpty)

    val eventToAdd = RegistryEventFactory.createInstanceAddedEvent(dao.getInstance(1).get)
    assert(dao.addEventFor(-1, eventToAdd).isFailure)
    assert(dao.addEventFor(1, eventToAdd).isSuccess)
    assert(dao.getEventsFor(1,0, 0, 0).get.size == 1)
    assert(dao.getEventsFor(1, 1, 1, 0).get.isEmpty)
    assert(dao.getEventsFor(1, 0, 0, 0).get.head == eventToAdd)
  }

  it must "verify the presence of instance ids when a link is added" in {
    assert(dao.addLink(InstanceLink(-1,2, LinkState.Assigned)).isFailure)
    assert(dao.addLink(InstanceLink(42, Integer.MAX_VALUE, LinkState.Assigned)).isFailure)
    assert(dao.addLink(InstanceLink(1,2, LinkState.Assigned)).isSuccess)
    assert(dao.getLinksFrom(1).size == 1)
  }

  it must "update old links in state 'Assigned' on adding a new assigned link." in {
    assert(dao.addLink(InstanceLink(0,1, LinkState.Assigned)).isSuccess)
    assert(dao.getLinksFrom(0, Some(LinkState.Assigned)).size == 1)
    assert(dao.addLink(InstanceLink(0,2, LinkState.Assigned)).isSuccess)

    assert(dao.getLinksFrom(0, Some(LinkState.Outdated)).size == 1)
    assert(dao.getLinksFrom(0, Some(LinkState.Outdated)).head.idTo == 1)

    assert(dao.getLinksFrom(0, Some(LinkState.Assigned)).size == 1)
    assert(dao.getLinksFrom(0, Some(LinkState.Assigned)).head.idTo == 2)
  }

  it must "be able to add label to instance" in {
    val addLabel = dao.addLabelFor(2, "test3")
    assert(addLabel.isSuccess)
    val instance = dao.getInstance(2)
    assert(instance.get.labels.size == 1)
  }

  it must "be able to remove label to instance if it exist" in {
    dao.addLabelFor(2, "test3")
    val removeLabel = dao.removeLabelFor(2, "test3")
    assert(removeLabel.isSuccess)
    val instance = dao.getInstance(2)
    assert(instance.get.labels.isEmpty)
  }

  it must "fail to remove label to instance if label not exist" in {
    val removeLabel = dao.removeLabelFor(2, "test1")
    assert(removeLabel.isFailure)
  }

  override protected def afterEach() : Unit = {
    dao.removeAll()
    dao.deleteRecoveryFile()
  }

}
