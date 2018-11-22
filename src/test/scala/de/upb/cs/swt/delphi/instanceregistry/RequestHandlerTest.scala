package de.upb.cs.swt.delphi.instanceregistry

import java.io.File

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import de.upb.cs.swt.delphi.instanceregistry.Docker._
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.{Instance, InstanceLink}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.LinkEnums.LinkState
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.concurrent.ExecutionContext

class RequestHandlerTest extends FlatSpec with Matchers with BeforeAndAfterEach {
  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher
  val handler: RequestHandler = new RequestHandler(new Configuration(), DockerConnection.fromEnvironment())

  private def buildInstance(id: Long, componentType: ComponentType = ComponentType.ElasticSearch, dockerId: Option[String] = None, state: InstanceState.Value = InstanceState.Stopped, labels: List[String] = List.empty[String]): Instance = {
    Instance(Some(id), "https://localhost", 12345, "TestInstance", componentType, dockerId, state, labels, List.empty[InstanceLink], List.empty[InstanceLink])
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
    val registerInstance = handler.handleRegister(buildInstance(id = 1, dockerId = Some("RandomDockerId"), state = InstanceState.Failed))
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
    val registerDockerInstance = handler.instanceDao.addInstance(buildInstance(id = 42, dockerId = Some("RandomDockerId")))
    assert(registerDockerInstance.isSuccess)
    val dockerInstance = handler.getInstance(42).get

    //Check wrong id
    assert(handler.handleDeregister(Int.MaxValue) == handler.OperationResult.IdUnknown)
    //Check is docker container
    assert(handler.handleDeregister(dockerInstance.id.get) == handler.OperationResult.IsDockerContainer)
  }

  it must "successfully deregister an instance that meets the required preconditions" in {
    val registerInstance = handler.handleRegister(buildInstance(1))

    assert(registerInstance.isSuccess)
    assert(handler.handleDeregister(registerInstance.get) == handler.OperationResult.Ok)
    assert(handler.getInstance(registerInstance.get).isEmpty)
  }

  it must "validate the id before applying a matching result" in {
    assert(handler.handleMatchingResult(callerId = 41, matchedInstanceId = 42, matchingSuccess = false) == handler.OperationResult.IdUnknown)
  }

  it must "change the instance state when matching results are applied" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(id = 42, dockerId = Some("RandomDockerId"), state = InstanceState.NotReachable))
    val register2 = handler.instanceDao.addInstance(buildInstance(id = 43, dockerId = Some("AnotherRandomDockerID"), state = InstanceState.Running))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    //Add Link to prevent internal error later
    assert(handler.instanceDao.addLink(InstanceLink(42,43, LinkState.Assigned)).isSuccess)

    assert(handler.handleMatchingResult(callerId = 43, matchedInstanceId = 42, matchingSuccess = true) == handler.OperationResult.Ok)
    assert(handler.getInstance(42).get.instanceState == InstanceState.Running)
    assert(handler.handleMatchingResult(callerId = 42, matchedInstanceId = 43, matchingSuccess = false) == handler.OperationResult.Ok)
    assert(handler.getInstance(43).get.instanceState == InstanceState.NotReachable)
  }

  it must "not change the instance state on invalid state transitions" in {
    val register = handler.instanceDao.addInstance(buildInstance(id = 42, dockerId = Some("RandomDockerId"), state = InstanceState.Failed))
    val register2 = handler.instanceDao.addInstance(buildInstance(id = 43, dockerId = Some("RandomDockerId2"), state = InstanceState.Running))

    assert(register.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handleMatchingResult(callerId = 43, matchedInstanceId = 42, matchingSuccess = true) == handler.OperationResult.Ok)
    assert(handler.getInstance(42).get.instanceState == InstanceState.Failed)
  }

  it must "validate preconditions on report operations" in {
    val register = handler.instanceDao.addInstance(buildInstance(42))

    assert(register.isSuccess)
    assert(handler.handleReportStart(-1) == handler.OperationResult.IdUnknown)
    assert(handler.handleReportStart(42) == handler.OperationResult.NoDockerContainer)

    assert(handler.handleReportFailure(-1L, None) == handler.OperationResult.IdUnknown)
    assert(handler.handleReportFailure(42, None) == handler.OperationResult.NoDockerContainer)

    assert(handler.handleReportStop(-1) == handler.OperationResult.IdUnknown)
    assert(handler.handleReportStop(42) == handler.OperationResult.NoDockerContainer)
  }

  it must "change the state on reportStart" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(id = 42, dockerId = Some("RandomDockerId"), state = InstanceState.Stopped))
    val register2 = handler.instanceDao.addInstance(buildInstance(id = 43, dockerId = Some("RandomDockerId2"), state = InstanceState.Failed))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handleReportStart(42) == handler.OperationResult.Ok)
    assert(handler.getInstance(42).get.instanceState == InstanceState.Running)
    assert(handler.handleReportStart(43) == handler.OperationResult.Ok)
    assert(handler.getInstance(43).get.instanceState == InstanceState.Running)
  }

  it must "change states only for valid state transitions on reportStop" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(id = 42, dockerId = Some("RandomDockerId"), state = InstanceState.Running))
    val register2 = handler.instanceDao.addInstance(buildInstance(id = 43, dockerId = Some("RandomDockerId2"), state = InstanceState.Failed))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handleReportStop(42) == handler.OperationResult.Ok)
    assert(handler.getInstance(42).get.instanceState == InstanceState.Stopped)
    assert(handler.handleReportStop(43) == handler.OperationResult.Ok)
    assert(handler.getInstance(43).get.instanceState == InstanceState.Failed)
  }

  it must "change the state on reportFailure" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(id = 42, dockerId  = Some("RandomDockerId"), state = InstanceState.Stopped))
    val register2 = handler.instanceDao.addInstance(buildInstance(id = 43, dockerId = Some("RandomDockerId2"), state = InstanceState.Running))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handleReportFailure(42, None) == handler.OperationResult.Ok)
    assert(handler.getInstance(42).get.instanceState == InstanceState.Failed)
    assert(handler.handleReportFailure(43, None) == handler.OperationResult.Ok)
    assert(handler.getInstance(43).get.instanceState == InstanceState.Failed)
  }

  it must "validate preconditions on handlePause" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(1))
    val register2 = handler.instanceDao.addInstance(buildInstance(id = 2, dockerId = Some("RandomDockerId"), state = InstanceState.Failed))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handlePause(Int.MaxValue) == handler.OperationResult.IdUnknown)
    assert(handler.handlePause(1) == handler.OperationResult.NoDockerContainer)
    assert(handler.handlePause(2) == handler.OperationResult.InvalidStateForOperation)
  }

  //Below test is not applicable anymore, state change is managed in futures!
  /*it must "change the state on handlePause" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(1, Some("RandomDockerId"), InstanceState.Running))
    assert(register1.isSuccess)

    assert(handler.handlePause(1) == handler.OperationResult.Ok)
    assert(handler.getInstance(1).get.instanceState == InstanceState.Paused)
  }*/

  it must "validate preconditions on handleResume" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(1))
    val register2 = handler.instanceDao.addInstance(buildInstance(id = 2, dockerId = Some("RandomDockerId"), state = InstanceState.Failed))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handleResume(Int.MaxValue) == handler.OperationResult.IdUnknown)
    assert(handler.handleResume(1) == handler.OperationResult.NoDockerContainer)
    assert(handler.handleResume(2) == handler.OperationResult.InvalidStateForOperation)
  }

  //Below test is not applicable anymore, state change is managed in futures!
  /*
  it must "change the state on handleResume" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(1, Some("RandomDockerId"), InstanceState.Paused))
    assert(register1.isSuccess)

    assert(handler.handleResume(1) == handler.OperationResult.Ok)
    assert(handler.getInstance(1).get.instanceState == InstanceState.Running)
  }*/

  it must "validate preconditions on handleStop" in {
    assert(handler.handleStop(Int.MaxValue) == handler.OperationResult.IdUnknown)
  }

  //Below test is not applicable anymore, state change is managed in futures!
  /*it must "change the state of the instance on handleStop" in {
    val register1 = handler.instanceDao.addInstance(Instance(Some(1), "http://localhost", 8083, "MyCrawler", ComponentType.Crawler, Some("RandomDockerId"), InstanceState.Running))
    assert(register1.isSuccess)

    assert(handler.handleStop(1) == handler.OperationResult.Ok)
    assert(handler.getInstance(1).get.instanceState == InstanceState.Stopped)
  }*/

  it must "validate preconditions on handleStart" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(1))
    val register2 = handler.instanceDao.addInstance(buildInstance(id = 2, dockerId = Some("RandomDockerId"), state = InstanceState.Paused))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handleStart(Int.MaxValue) == handler.OperationResult.IdUnknown)
    assert(handler.handleStart(1) == handler.OperationResult.NoDockerContainer)
    assert(handler.handleStart(2) == handler.OperationResult.InvalidStateForOperation)
  }

  it must "not change the state of the instance on handleStart" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(id = 1, dockerId = Some("RandomDockerId"), state = InstanceState.Stopped))
    assert(register1.isSuccess)

    assert(handler.handleStop(1) == handler.OperationResult.Ok)
    assert(handler.getInstance(1).get.instanceState == InstanceState.Stopped)
  }

  it must "validate preconditions on handleDeleteContainer" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(1))
    val register2 = handler.instanceDao.addInstance(buildInstance(id = 2, dockerId = Some("RandomDockerId"), state = InstanceState.Running))
    assert(register1.isSuccess)
    assert(register2.isSuccess)

    assert(handler.handleDeleteContainer(Int.MaxValue) == handler.OperationResult.IdUnknown)
    assert(handler.handleDeleteContainer(1) == handler.OperationResult.NoDockerContainer)
    assert(handler.handleDeleteContainer(2) == handler.OperationResult.InvalidStateForOperation)
  }

  it must "remove instances on handleDeleteContainer" in {
    val register1 = handler.instanceDao.addInstance(buildInstance(id = 1, dockerId = Some("RandomDockerId"), state = InstanceState.Stopped))
    assert(register1.isSuccess)

    assert(handler.handleDeleteContainer(1) == handler.OperationResult.Ok)
    assert(handler.getInstance(1).isEmpty)
  }

  it must "not add two default ES instances on initializing" in {
    assert(handler.getNumberOfInstances(ComponentType.ElasticSearch) == 1)
    handler.initialize()
    assert(handler.getNumberOfInstances(ComponentType.ElasticSearch) == 1)
  }

  it must "validate preconditions before adding a label" in {
    assert(handler.instanceDao.addInstance(buildInstance(id = 1, labels = List("private"))).isSuccess)

    assert(handler.handleAddLabel(42, "private") == handler.OperationResult.IdUnknown)
    assert(handler.handleAddLabel(1, "PrivATe") == handler.OperationResult.Ok)
    assert(handler.instanceDao.getInstance(1).get.labels.size == 1) //Do not add same value twice (ignore case)

    val sb: StringBuilder = new StringBuilder("foo")
    while(sb.length <= Registry.configuration.maxLabelLength){
      sb.append("x")
    }
    assert(handler.handleAddLabel(1, sb.toString()) == handler.OperationResult.InternalError)

    assert(handler.handleAddLabel(1, "public") == handler.OperationResult.Ok)
    assert(handler.instanceDao.getInstance(1).get.labels.size == 2)
  }

  it must "validate preconditions before creating a link" in {
    assert(handler.instanceDao.addInstance(buildInstance(id = 1, componentType = ComponentType.WebApi)).isSuccess)
    assert(handler.instanceDao.addInstance(buildInstance(id = 2, componentType = ComponentType.WebApp)).isSuccess)

    assert(handler.handleInstanceLinkCreated(-1, Int.MaxValue) == handler.OperationResult.IdUnknown)
    assert(handler.handleInstanceLinkCreated(Int.MaxValue, 0) == handler.OperationResult.IdUnknown)
    assert(handler.handleInstanceLinkCreated(0, 1) == handler.OperationResult.InvalidTypeForOperation)
    assert(handler.handleInstanceLinkCreated(2,0) == handler.OperationResult.InvalidTypeForOperation)

    assert(handler.handleInstanceLinkCreated(2,1) == handler.OperationResult.Ok)
    assert(handler.instanceDao.getLinksFrom(2).size == 1)
  }

  it must "validate preconditions before assigning new dependencies" in {
    assert(handler.instanceDao.addInstance(buildInstance(id = 1, componentType = ComponentType.WebApi)).isSuccess)
    assert(handler.instanceDao.addInstance(buildInstance(id = 2, componentType = ComponentType.WebApi)).isSuccess)
    assert(handler.instanceDao.addInstance(buildInstance(id = 3, dockerId = Some("random"), componentType = ComponentType.WebApp)).isSuccess)
    assert(handler.instanceDao.addInstance(buildInstance(id = 4, dockerId = None, componentType = ComponentType.WebApp)).isSuccess)

    assert(handler.instanceDao.addLink(InstanceLink(3,1, linkState = LinkState.Assigned)).isSuccess)

    assert(handler.handleInstanceAssignment(3, Integer.MAX_VALUE) == handler.OperationResult.IdUnknown)
    assert(handler.handleInstanceAssignment(4, 3) == handler.OperationResult.NoDockerContainer)
    assert(handler.handleInstanceAssignment(3,2) == handler.OperationResult.Ok)

    assert(handler.instanceDao.getLinksFrom(3).filter(i => i.linkState == LinkState.Assigned).head.idTo == 2)
  }

  /**
    * MATCHING TESTS
    */

  it must "not match to any instance if no instance of requested type is present" in {
    assert(handler.isInstanceIdPresent(0) && handler.instanceDao.getInstance(0).get.componentType == ComponentType.ElasticSearch)
    assert(handler.instanceDao.addInstance(buildInstance(id = 1, componentType = ComponentType.WebApp, labels = List("private"))).isSuccess)

    //No WebApi present, must fail
    assert(handler.getMatchingInstanceOfType(callerId = 1, compType = ComponentType.WebApi).isFailure)

    //Shared label with elastic search instance, still no WebApi present, must fail
    assert(handler.handleAddLabel(id = 0, label = "private") == handler.OperationResult.Ok)
    assert(handler.getMatchingInstanceOfType(callerId = 1, compType = ComponentType.WebApi).isFailure)

    //Try component type crawler: Must also fail
    assert(handler.getMatchingInstanceOfType(callerId = 1, compType = ComponentType.Crawler).isFailure)

    //Assign a link to an invalid type in the db. Must also fail
    assert(handler.instanceDao.addLink(InstanceLink(idFrom = 1, idTo = 0, linkState = LinkState.Assigned)).isSuccess)
    assert(handler.getMatchingInstanceOfType(callerId = 1, compType = ComponentType.WebApi).isFailure)
  }

  it must "rank assigned links higher than shared labels in matching" in {
    assert(handler.instanceDao.addInstance(buildInstance(id = 1, componentType = ComponentType.WebApp, labels = List("private", "new"))).isSuccess)
    assert(handler.instanceDao.addInstance(buildInstance(id = 2, componentType = ComponentType.WebApi, labels = List("public", "new"))).isSuccess)
    assert(handler.instanceDao.addInstance(buildInstance(id = 3, componentType = ComponentType.WebApi, labels = List("private", "new"))).isSuccess)

    assert(handler.instanceDao.addLink(InstanceLink(idFrom = 1, idTo = 2, linkState = LinkState.Assigned)).isSuccess)

    //Matching must yield the instance that was assigned!
    val matching = handler.getMatchingInstanceOfType(callerId = 1, ComponentType.WebApi)
    assert(matching.isSuccess)
    assert(matching.get.id.get == 2)

    //Now that link is outdated, shared labels "private" & "new" must be deciding factor!
    assert(handler.instanceDao.updateLink(InstanceLink(idFrom = 1, idTo = 2, linkState = LinkState.Outdated)).isSuccess)
    val matching2 = handler.getMatchingInstanceOfType(callerId = 1, ComponentType.WebApi)
    assert(matching2.isSuccess)
    assert(matching2.get.id.get == 3)
  }

  it must "match to instance with most consecutive positive matching results in fallback matching" in {
    val esInstance = handler.handleRegister(buildInstance(2))
    val crawlerId = handler.handleRegister(Instance(Some(2), "foo", 42, "bar", ComponentType.Crawler, None, InstanceState.Running, List.empty[String], List.empty[InstanceLink], List.empty[InstanceLink]))

    assert(esInstance.isSuccess)
    assert(esInstance.get == 1)
    assert(crawlerId.isSuccess)
    assert(crawlerId.get == 2)

    //Add Links to prevent errors later
    assert(handler.instanceDao.addLink(InstanceLink(1,0,LinkState.Assigned)).isSuccess)
    assert(handler.instanceDao.addLink(InstanceLink(0,1,LinkState.Assigned)).isSuccess)

    assert(handler.handleMatchingResult(callerId = 1, matchedInstanceId = 0, matchingSuccess = false) == handler.OperationResult.Ok)
    assert(handler.handleMatchingResult(callerId = 1, matchedInstanceId = 0, matchingSuccess = true) == handler.OperationResult.Ok)
    assert(handler.handleMatchingResult(callerId = 1, matchedInstanceId = 0, matchingSuccess = true) == handler.OperationResult.Ok)

    assert(handler.handleMatchingResult(callerId = 0, matchedInstanceId = 1, matchingSuccess = true) == handler.OperationResult.Ok)
    assert(handler.handleMatchingResult(callerId = 0, matchedInstanceId = 1, matchingSuccess = false) == handler.OperationResult.Ok)

    val matchingInstance = handler.getMatchingInstanceOfType(callerId = 2, ComponentType.ElasticSearch)
    assert(matchingInstance.isSuccess)
    assert(matchingInstance.get.id.get == 0)

    assert(handler.handleDeregister(1L) == handler.OperationResult.Ok)
    assert(handler.handleDeregister(2L) == handler.OperationResult.Ok)
  }

  override protected def afterEach(): Unit = {
    handler.shutdown()
  }

}
