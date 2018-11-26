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
    assert(dao.addInstance(buildInstance(4)).isSuccess)
    assert(dao.allInstances().size == 4)
    assert(dao.hasInstance(4))
    assert(dao.removeInstance(4).isSuccess)
  }

  it must "not allow the addition of any id twice" in {
    assert(dao.addInstance(buildInstance(3)).isFailure)
    assert(dao.allInstances().size == 3)
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
    dao.removeAll()
    assert(dao.allInstances().isEmpty)
  }

  it must "have an empty list of matching results for newly added instances" in {
    dao.addInstance(buildInstance(4))
    assert(dao.getMatchingResultsFor(4).isSuccess)
    assert(dao.getMatchingResultsFor(4).get.isEmpty)
  }

  it must "keep the correct order of matching results posted" in {
    assert(dao.addMatchingResult(3, matchingSuccessful = true).isSuccess)
    assert(dao.addMatchingResult(3, matchingSuccessful = true).isSuccess)
    assert(dao.addMatchingResult(3, matchingSuccessful = false).isSuccess)

    assert(dao.getMatchingResultsFor(3).isSuccess)
    assert(dao.getMatchingResultsFor(3).get.head)
    assert(dao.getMatchingResultsFor(3).get (1))
    assert(!dao.getMatchingResultsFor(3).get (2))

  }

  it must "remove the matching results when the instance is removed" in {
    assert(dao.removeInstance(3).isSuccess)
    assert(dao.getMatchingResultsFor(3).isFailure)
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
    assert(dao.addInstance
    (Instance(Some(42), "http://localhost", 33449, "AnyName",
      ComponentType.WebApi, Some("dockerId"), InstanceState.Running, List.empty[String], List.empty[InstanceLink], List.empty[InstanceLink] )).isSuccess)
    assert(dao.getDockerIdFor(42).isSuccess)
    assert(dao.getDockerIdFor(42).get.equals("dockerId"))
  }

  it must "add events only to instances that have been registered" in {
    assert(dao.getEventsFor(1).isSuccess)
    assert(dao.getEventsFor(1).get.isEmpty)

    val eventToAdd = RegistryEventFactory.createInstanceAddedEvent(dao.getInstance(1).get)
    assert(dao.addEventFor(-1, eventToAdd).isFailure)
    assert(dao.addEventFor(1, eventToAdd).isSuccess)
    assert(dao.getEventsFor(1).get.size == 1)
    assert(dao.getEventsFor(1).get.head == eventToAdd)
  }

  it must "verify the presence of instance ids when a link is added" in {
    assert(dao.addLink(InstanceLink(-1,2, LinkState.Assigned)).isFailure)
    assert(dao.addLink(InstanceLink(42, Integer.MAX_VALUE, LinkState.Assigned)).isFailure)
    assert(dao.addLink(InstanceLink(1,2, LinkState.Assigned)).isSuccess)
    assert(dao.getLinksFrom(1).size == 1)
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

  override protected def afterEach() : Unit = {
    dao.removeAll()
    dao.deleteRecoveryFile()
  }

}
