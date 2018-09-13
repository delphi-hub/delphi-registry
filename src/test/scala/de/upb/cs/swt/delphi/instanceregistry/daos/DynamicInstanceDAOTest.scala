package de.upb.cs.swt.delphi.instanceregistry.daos

import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.Instance
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

class DynamicInstanceDAOTest extends FlatSpec with Matchers with BeforeAndAfterAll{

  val dao : InstanceDAO = new DynamicInstanceDAO()

  private def buildInstance(id : Int) : Instance = {
    Instance(Some(id), "https://localhost", 12345, "TestInstance", ComponentType.Crawler)
  }

  override protected def beforeAll() : Unit = {
    dao.clearAll()
    for(i <- 1 to 3){
      dao.addInstance(buildInstance(i))
    }
  }

  "The instance DAO" must "be able to add an get an instance with a new id" in {
    assert(dao.addInstance(buildInstance(4)).isSuccess)
    assert(dao.getAllInstances().size == 4)
    assert(dao.hasInstance(4))
    assert(dao.removeInstance(4).isSuccess)
  }

}
