// Copyright (C) 2018 The Delphi Team.
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

// http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package de.upb.cs.swt.delphi.instanceregistry.daos

import de.upb.cs.swt.delphi.instanceregistry.Configuration
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.LinkEnums.LinkState
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.{Instance, InstanceLink, RegistryEventFactory}
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

class DatabaseInstanceDAOTest extends FlatSpec with Matchers with BeforeAndAfterEach{

  val config = new Configuration()
  val dao : DatabaseInstanceDAO = new DatabaseInstanceDAO(config)
  dao.setDatabaseConfiguration("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MYSQL","", "org.h2.Driver")
  before()

  private def buildInstance(id : Int) : Instance = {
    Instance(Some(id), "https://localhost", 12345, "TestInstance", ComponentType.Crawler, None, InstanceState.Stopped, List.empty[String],
      List.empty[InstanceLink], List.empty[InstanceLink])
  }

  def before() : Unit = {
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
    val idOption = dao.addInstance(buildInstance(4))
    assert(idOption.isSuccess)
    assert(dao.allInstances().size == 4)
    assert(dao.hasInstance(idOption.get))
    assert(dao.removeInstance(idOption.get).isSuccess)
  }

  it must "return true on hasInstance for any present id" in {
    for(i <- 1 to 3){
      assert(dao.hasInstance(i))
    }
  }

  it must "return false on hasInstance for any id not present" in {
    assert(!dao.hasInstance(-1))
    assert(!dao.hasInstance(Long.MaxValue))
    assert(!dao.hasInstance(4))
  }

  it must "return instances with the correct id on getInstance" in {
    for(i <- 1 to 3){
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

  it must "Successfully added the Instance matching result" in {
    val idOption = dao.addInstance(buildInstance(6))
    dao.addMatchingResult(idOption.get, matchingSuccessful = true)
    assert(dao.getMatchingResultsFor(idOption.get).isSuccess)
  }

  it must "have an empty list of matching results for newly added instances" in {
    dao.removeInstance(6)
    assert(dao.getMatchingResultsFor(6).isFailure)
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
    val idOption = dao.addInstance(buildInstance(7))
    assert(dao.removeInstance(idOption.get).isSuccess)
    assert(dao.getMatchingResultsFor(idOption.get).isFailure)
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
    dao.removeInstance(idOption.get)
  }

  it must "add events only to instances that have been registered" in {
    assert(dao.getEventsFor(1).isFailure)
    //assert(dao.getEventsFor(1).get.isEmpty)

    val eventToAdd = RegistryEventFactory.createInstanceAddedEvent(dao.getInstance(1).get)
    assert(dao.addEventFor(-1, eventToAdd).isFailure)
    assert(dao.addEventFor(1, eventToAdd).isSuccess)
    assert(dao.getEventsFor(1).get.size == 1)
    assert(dao.getEventsFor(1).get.head == eventToAdd)
  }

  it must "verify the presence of instance ids when a link is added" in {
    assert(dao.addLink(InstanceLink(-1,2, LinkState.Assigned)).isFailure)
    assert(dao.addLink(InstanceLink(42, Integer.MAX_VALUE, LinkState.Assigned)).isFailure)
    assert(dao.addLink(InstanceLink(2,3, LinkState.Assigned)).isSuccess)
    assert(dao.getLinksFrom(2).size == 1)
  }

  it must "update old links in state 'Assigned' on adding a new assigned link." in {
    assert(dao.addLink(InstanceLink(1,2, LinkState.Assigned)).isSuccess)
    assert(dao.getLinksFrom(1, Some(LinkState.Assigned)).size == 1)
    assert(dao.addLink(InstanceLink(1,3, LinkState.Assigned)).isSuccess)

    assert(dao.getLinksFrom(1, Some(LinkState.Outdated)).size == 1)
    assert(dao.getLinksFrom(1, Some(LinkState.Outdated)).head.idTo == 2)

    assert(dao.getLinksFrom(1, Some(LinkState.Assigned)).size == 1)
    assert(dao.getLinksFrom(1, Some(LinkState.Assigned)).head.idTo == 3)
  }

  it must "remove instances that are present in the DAO" in {
    for(i <- 1 to 3){
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
    before()
    dao.removeAll()
    assert(dao.allInstances().isEmpty)
  }

}
