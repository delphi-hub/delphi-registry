package de.upb.cs.swt.delphi.instanceregistry

import java.io.File

import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.Instance
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

class RequestHandlerTest extends FlatSpec with Matchers with BeforeAndAfterEach{

  val handler : RequestHandler = new RequestHandler(new Configuration())

  private def buildInstance(id : Long) : Instance = {
    Instance(Some(id), "https://localhost", 12345, "TestInstance", ComponentType.ElasticSearch)
  }

  override protected def beforeEach(): Unit = {
    new File(Registry.configuration.recoveryFileName).delete()
    handler.initialize()
  }

  "The RequestHandler" must "assign new IDs to instances regardless of their actual id" in {
    val registerNewInstance = handler.registerNewInstance(buildInstance(Long.MaxValue))
    assert(registerNewInstance.isSuccess)
    assert(registerNewInstance.get != Long.MaxValue)

    val registerNewInstance2 = handler.registerNewInstance(buildInstance(1L))
    assert(registerNewInstance2.isSuccess)
    assert(registerNewInstance2.get != 1L)
  }

  it must "be able to remove instances that have been added before" in {
    val registerNewInstance = handler.registerNewInstance(buildInstance(-1L))
    assert(registerNewInstance.isSuccess)
    assert(registerNewInstance.get == 1)
    assert(handler.removeInstance(1).isSuccess)
  }

  it must "not add two default ES instances on initializing" in {
    assert(handler.getNumberOfInstances(ComponentType.ElasticSearch) == 1)
    handler.initialize()
    assert(handler.getNumberOfInstances(ComponentType.ElasticSearch) == 1)
  }

  it must "match to instance with most consecutive positive matching results" in {
    val esInstance = handler.registerNewInstance(buildInstance(2))
    assert(esInstance.isSuccess)
    assert(esInstance.get == 1)

    assert(handler.applyMatchingResult(0, result = false).isSuccess)
    assert(handler.applyMatchingResult(0, result = true).isSuccess)
    assert(handler.applyMatchingResult(0,result = true).isSuccess)

    assert(handler.applyMatchingResult(1,result = true).isSuccess)
    assert(handler.applyMatchingResult(1,result = false).isSuccess)

    val matchingInstance = handler.getMatchingInstanceOfType(ComponentType.ElasticSearch)
    assert(matchingInstance.isSuccess)
    assert(matchingInstance.get.id.get == 0)

    assert(handler.removeInstance(1L).isSuccess)
  }

  it must "match to instance with most positive matching results" in {
    val esInstance = handler.registerNewInstance(buildInstance(2))
    assert(esInstance.isSuccess)
    assert(esInstance.get == 1)

    assert(handler.applyMatchingResult(0, result = true).isSuccess)
    assert(handler.applyMatchingResult(0,result = true).isSuccess)
    assert(handler.applyMatchingResult(0, result = false).isSuccess)

    assert(handler.applyMatchingResult(1,result = false).isSuccess)
    assert(handler.applyMatchingResult(1,result = false).isSuccess)

    val matchingInstance = handler.getMatchingInstanceOfType(ComponentType.ElasticSearch)
    assert(matchingInstance.isSuccess)
    assert(matchingInstance.get.id.get == 0)

    assert(handler.removeInstance(1L).isSuccess)
  }

  it must "fail to match if no instance of type is present" in {
    assert(handler.getMatchingInstanceOfType(ComponentType.Crawler).isFailure)
  }

  override protected def afterEach(): Unit = {
    handler.shutdown()
  }

}
