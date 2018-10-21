package de.upb.cs.swt.delphi.instanceregistry

import java.io.File

import de.upb.cs.swt.delphi.instanceregistry.Docker.DockerConnection
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.Instance
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

class RequestHandlerTest extends FlatSpec with Matchers with BeforeAndAfterEach{

  val handler : RequestHandler = new RequestHandler(new Configuration(), DockerConnection.fromEnvironment())

  private def buildInstance(id : Long, dockerId: Option[String] = None, state: InstanceState.Value = InstanceState.Stopped) : Instance = {
    Instance(Some(id), "https://localhost", 12345, "TestInstance", ComponentType.ElasticSearch, dockerId, state)
  }

  override protected def beforeEach(): Unit = {
    new File(Registry.configuration.recoveryFileName).delete()
    handler.initialize()
    handler.instanceDao.removeAll()
    handler.initialize()
  }

  "The RequestHandler" must "assign new IDs to instances regardless of their actual id" in {
    val registerNewInstance = handler.handleRegister(buildInstance(Long.MaxValue))
    assert(registerNewInstance.isSuccess)
    assert(registerNewInstance.get != Long.MaxValue)

    val registerNewInstance2 = handler.handleRegister(buildInstance(42L))
    assert(registerNewInstance2.isSuccess)
    assert(registerNewInstance2.get != 42L)
  }

  it must "ignore the dockerId and instanceState on registration" in {
    val registerInstance = handler.handleRegister(buildInstance(1, Some("RandomDockerId"), InstanceState.Failed))
    assert(registerInstance.isSuccess)
    val instance = handler.getInstance(registerInstance.get)
    assert(instance.isDefined)
    assert(instance.get.dockerId.isEmpty)
    assert(instance.get.instanceState == InstanceState.Running)
  }

  it must "store name, host, port and type of the registering instance" in {
    val registerInstance = handler.handleRegister(buildInstance(1))
    assert(registerInstance.isSuccess)
    val instance = handler.getInstance(registerInstance.get).get
    assert(instance.host.equals("https://localhost"))
    assert(instance.portNumber == 12345)
    assert(instance.name.equals("TestInstance"))
    assert(instance.componentType == ComponentType.ElasticSearch)
  }

  it must "validate preconditions on deregister" in {
    //Bypass register as it would ignore dockerId!
    val registerDockerInstance = handler.instanceDao.addInstance(buildInstance(42, Some("RandomDockerId")))
    assert(registerDockerInstance.isSuccess)
    val dockerInstance = handler.getInstance(42).get

    //Check wrong id
    assert(handler.handleDeregister(Int.MaxValue) == handler.OperationResult.IdUnknown)
    //Check is docker container
    assert(handler.handleDeregister(dockerInstance.id.get) == handler.OperationResult.IsDockerContainer)
  }

  it must "successfully deregister an instance that meets the required preconditions" in {
    val registerInstance = handler.handleRegister(buildInstance(1, None))

    assert(registerInstance.isSuccess)
    assert(handler.handleDeregister(registerInstance.get) == handler.OperationResult.Ok)
    assert(handler.getInstance(registerInstance.get).isEmpty)
  }

  it must "validate the id before applying a matching result" in {
    assert(handler.handleMatchingResult(42, result = false) == handler.OperationResult.IdUnknown)
  }

  it must "change the instance state when matching results are applied" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(42, Some("RandomDockerId"), InstanceState.NotReachable))
    val register2 = handler.instanceDao.addInstance(buildInstance(43, Some("AnotherRandomDockerID"), InstanceState.Running))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handleMatchingResult(42, result = true) == handler.OperationResult.Ok)
    assert(handler.getInstance(42).get.instanceState == InstanceState.Running)
    assert(handler.handleMatchingResult(43, result = false) == handler.OperationResult.Ok)
    assert(handler.getInstance(43).get.instanceState == InstanceState.NotReachable)
  }

  it must "not change the instance state on invalid state transitions" in {
    val register = handler.instanceDao.addInstance(buildInstance(42, Some("RandomDockerId"), InstanceState.Failed))
    assert(register.isSuccess)

    assert(handler.handleMatchingResult(42, result = true) == handler.OperationResult.Ok)
    assert(handler.getInstance(42).get.instanceState == InstanceState.Failed)
  }

  it must "validate preconditions on report operations" in {
    val register = handler.instanceDao.addInstance(buildInstance(42, None))

    assert(register.isSuccess)
    assert(handler.handleReportStart(-1) == handler.OperationResult.IdUnknown)
    assert(handler.handleReportStart(42) == handler.OperationResult.NoDockerContainer)

    assert(handler.handleReportFailure(-1L, None) == handler.OperationResult.IdUnknown)
    assert(handler.handleReportFailure(42, None) == handler.OperationResult.NoDockerContainer)

    assert(handler.handleReportStop(-1) == handler.OperationResult.IdUnknown)
    assert(handler.handleReportStop(42) == handler.OperationResult.NoDockerContainer)
  }

  it must "change the state on reportStart" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(42, Some("RandomDockerId"), InstanceState.Stopped))
    val register2 = handler.instanceDao.addInstance(buildInstance(43, Some("RandomDockerId2"), InstanceState.Failed))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handleReportStart(42) == handler.OperationResult.Ok)
    assert(handler.getInstance(42).get.instanceState == InstanceState.Running)
    assert(handler.handleReportStart(43) == handler.OperationResult.Ok)
    assert(handler.getInstance(43).get.instanceState == InstanceState.Running)
  }

  it must "change states only for valid state transitions on reportStop" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(42, Some("RandomDockerId"), InstanceState.Running))
    val register2 = handler.instanceDao.addInstance(buildInstance(43, Some("RandomDockerId2"), InstanceState.Failed))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handleReportStop(42) == handler.OperationResult.Ok)
    assert(handler.getInstance(42).get.instanceState == InstanceState.NotReachable)
    assert(handler.handleReportStop(43) == handler.OperationResult.Ok)
    assert(handler.getInstance(43).get.instanceState == InstanceState.Failed)
  }

  it must "change the state on reportFailure" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(42, Some("RandomDockerId"), InstanceState.Stopped))
    val register2 = handler.instanceDao.addInstance(buildInstance(43, Some("RandomDockerId2"), InstanceState.Running))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handleReportFailure(42, None) == handler.OperationResult.Ok)
    assert(handler.getInstance(42).get.instanceState == InstanceState.Failed)
    assert(handler.handleReportFailure(43, None) == handler.OperationResult.Ok)
    assert(handler.getInstance(43).get.instanceState == InstanceState.Failed)
  }

  it must "validate preconditions on handlePause" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(1, None))
    val register2 = handler.instanceDao.addInstance(buildInstance(2, Some("RandomDockerId"), InstanceState.Failed))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handlePause(Int.MaxValue) == handler.OperationResult.IdUnknown)
    assert(handler.handlePause(1) == handler.OperationResult.NoDockerContainer)
    assert(handler.handlePause(2) == handler.OperationResult.InvalidStateForOperation)
  }

  it must "change the state on handlePause" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(1, Some("RandomDockerId"), InstanceState.Running))
    assert(register1.isSuccess)

    assert(handler.handlePause(1) == handler.OperationResult.Ok)
    assert(handler.getInstance(1).get.instanceState == InstanceState.Paused)
  }

  it must "validate preconditions on handleResume" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(1, None))
    val register2 = handler.instanceDao.addInstance(buildInstance(2, Some("RandomDockerId"), InstanceState.Failed))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handleResume(Int.MaxValue) == handler.OperationResult.IdUnknown)
    assert(handler.handleResume(1) == handler.OperationResult.NoDockerContainer)
    assert(handler.handleResume(2) == handler.OperationResult.InvalidStateForOperation)
  }

  it must "change the state on handleResume" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(1, Some("RandomDockerId"), InstanceState.Paused))
    assert(register1.isSuccess)

    assert(handler.handleResume(1) == handler.OperationResult.Ok)
    assert(handler.getInstance(1).get.instanceState == InstanceState.Running)
  }

  it must "validate preconditions on handleStop" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(1, None))
    assert(register1.isSuccess)

    assert(handler.handleStop(Int.MaxValue) == handler.OperationResult.IdUnknown)
    assert(handler.handleStop(1) == handler.OperationResult.NoDockerContainer)
  }

  it must "change the state of the instance on handleStop" in {
    val register1 = handler.instanceDao.addInstance(Instance(Some(1), "http://localhost", 8083, "MyCrawler", ComponentType.Crawler, Some("RandomDockerId"), InstanceState.Running))
    assert(register1.isSuccess)

    assert(handler.handleStop(1) == handler.OperationResult.Ok)
    assert(handler.getInstance(1).get.instanceState == InstanceState.Stopped)
  }

  it must "validate preconditions on handleStart" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(1, None))
    val register2 = handler.instanceDao.addInstance(buildInstance(2, Some("RandomDockerId"), InstanceState.Paused))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handleStart(Int.MaxValue) == handler.OperationResult.IdUnknown)
    assert(handler.handleStart(1) == handler.OperationResult.NoDockerContainer)
    assert(handler.handleStart(2) == handler.OperationResult.InvalidStateForOperation)
  }

  it must "not change the state of the instance on handleStart" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(1, Some("RandomDockerId"), InstanceState.Stopped))
    assert(register1.isSuccess)

    assert(handler.handleStop(1) == handler.OperationResult.Ok)
    assert(handler.getInstance(1).get.instanceState == InstanceState.Stopped)
  }

  it must "validate preconditions on handleDeleteContainer" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(1, None))
    val register2 = handler.instanceDao.addInstance(buildInstance(2, Some("RandomDockerId"), InstanceState.Paused))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handleDeleteContainer(Int.MaxValue) == handler.OperationResult.IdUnknown)
    assert(handler.handleDeleteContainer(1) == handler.OperationResult.NoDockerContainer)
    assert(handler.handleDeleteContainer(2) == handler.OperationResult.InvalidStateForOperation)
  }

  it must "remove instances on handleDeleteContainer" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(1, Some("RandomDockerId"), InstanceState.Stopped))
    assert(register1.isSuccess)

    assert(handler.handleDeleteContainer(1) == handler.OperationResult.Ok)
    assert(handler.getInstance(1).isEmpty)
  }

  it must "not add two default ES instances on initializing" in {
    assert(handler.getNumberOfInstances(ComponentType.ElasticSearch) == 1)
    handler.initialize()
    assert(handler.getNumberOfInstances(ComponentType.ElasticSearch) == 1)
  }

  it must "match to instance with most consecutive positive matching results" in {
    val esInstance = handler.handleRegister(buildInstance(2))
    assert(esInstance.isSuccess)
    assert(esInstance.get == 1)

    assert(handler.handleMatchingResult(0, result = false) == handler.OperationResult.Ok)
    assert(handler.handleMatchingResult(0, result = true) == handler.OperationResult.Ok)
    assert(handler.handleMatchingResult(0,result = true) == handler.OperationResult.Ok)

    assert(handler.handleMatchingResult(1,result = true) == handler.OperationResult.Ok)
    assert(handler.handleMatchingResult(1,result = false) == handler.OperationResult.Ok)

    val matchingInstance = handler.getMatchingInstanceOfType(ComponentType.ElasticSearch)
    assert(matchingInstance.isSuccess)
    assert(matchingInstance.get.id.get == 0)

    assert(handler.handleDeregister(1L) == handler.OperationResult.Ok)
  }

  it must "match to instance with most positive matching results" in {
    val esInstance = handler.handleRegister(buildInstance(2))
    assert(esInstance.isSuccess)
    assert(esInstance.get == 1)

    assert(handler.handleMatchingResult(0, result = true) == handler.OperationResult.Ok)
    assert(handler.handleMatchingResult(0,result = true) == handler.OperationResult.Ok)
    assert(handler.handleMatchingResult(0, result = false) == handler.OperationResult.Ok)

    assert(handler.handleMatchingResult(1,result = false) == handler.OperationResult.Ok)
    assert(handler.handleMatchingResult(1,result = false) == handler.OperationResult.Ok)

    val matchingInstance = handler.getMatchingInstanceOfType(ComponentType.ElasticSearch)
    assert(matchingInstance.isSuccess)
    assert(matchingInstance.get.id.get == 0)

    assert(handler.handleDeregister(1L) == handler.OperationResult.Ok)
  }

  it must "fail to match if no instance of type is present" in {
    assert(handler.getMatchingInstanceOfType(ComponentType.Crawler).isFailure)
  }

  override protected def afterEach(): Unit = {
    handler.shutdown()
  }

}
