package de.upb.cs.swt.delphi.instanceregistry.daos

import de.upb.cs.swt.delphi.instanceregistry.Configuration
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.Instance
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

class DynamicInstanceDAOTest extends FlatSpec with Matchers with BeforeAndAfterEach{

  val dao : DynamicInstanceDAO = new DynamicInstanceDAO(new Configuration())

  private def buildInstance(id : Int) : Instance = {
    Instance(Some(id), "https://localhost", 12345, "TestInstance", ComponentType.Crawler)
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

  "The DAO" must "be able to read multiple instances from the recovery file" in {
    dao.dumpToRecoveryFile()
    dao.clearData()
    assert(dao.allInstances().isEmpty)
    dao.tryInitFromRecoveryFile()
    assert(dao.allInstances().size == 3)
  }

  it must "fail to load from recovery file if it is not present" in {
    dao.dumpToRecoveryFile()
    assert(dao.allInstances().size == 3)
    dao.deleteRecoveryFile()
    dao.clearData()
    assert(dao.allInstances().isEmpty)
    dao.tryInitFromRecoveryFile()
    assert(dao.allInstances().isEmpty)
  }

  it must "contain the correct instance data after loading from recovery file" in {
    assert(dao.addInstance(buildInstance(4)).isSuccess)
    dao.dumpToRecoveryFile()
    assert(dao.allInstances().size == 4)
    dao.clearData()
    assert(dao.allInstances().isEmpty)
    dao.tryInitFromRecoveryFile()
    assert(dao.allInstances().size == 4)
    val instance = dao.getInstance(4)
    assert(instance.isDefined)
    assert(instance.get.id.get == 4)
  }




  override protected def afterEach() : Unit = {
    dao.removeAll()
    dao.deleteRecoveryFile()
  }

}
