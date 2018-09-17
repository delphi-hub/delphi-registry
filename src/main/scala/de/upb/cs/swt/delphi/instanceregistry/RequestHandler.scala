package de.upb.cs.swt.delphi.instanceregistry

import akka.actor.ActorSystem
import de.upb.cs.swt.delphi.instanceregistry.daos.{DynamicInstanceDAO, InstanceDAO}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.Instance
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType

import scala.util.{Failure, Success, Try}

class RequestHandler (configuration: Configuration) extends AppLogging {

  implicit val system : ActorSystem = Registry.system

  private val instanceDao : InstanceDAO = new DynamicInstanceDAO(configuration)

  def initialize() : Unit = {
    log.info("Initializing request handler...")
    instanceDao.initialize()
    if(!instanceDao.allInstances().exists(instance => instance.name.equals("Default ElasticSearch Instance"))){
      //Add default ES instance
      registerNewInstance(Instance(None, "elasticsearch://localhost", 9200, "Default ElasticSearch Instance", ComponentType.ElasticSearch))
    }
    log.info("Done initializing request handler.")
  }

  def shutdown() : Unit = {
    instanceDao.shutdown()
  }

  def registerNewInstance(instance : Instance) : Try[Long] = {
    val newID = if(instanceDao.allInstances().isEmpty){
      0L
    } else {
      (instanceDao.allInstances().map(i => i.id.getOrElse(0L)) max) + 1L
    }

    log.info(s"Assigned new id $newID to registering instance with name ${instance.name}.")

    val newInstance = Instance(id = Some(newID), name = instance.name, host = instance.host,
      portNumber = instance.portNumber, componentType = instance.componentType)

    instanceDao.addInstance(newInstance) match {
      case Success(_) => Success(newID)
      case Failure(x) => Failure(x)
    }
  }

  def removeInstance(instanceId : Long) : Try[Unit] = {
    if(!instanceDao.hasInstance(instanceId)){
      Failure(new RuntimeException(s"Cannot remove instance with id $instanceId, that id is not known to the server."))
    } else {
      instanceDao.removeInstance(instanceId)
    }
  }

  def getAllInstancesOfType(compType : ComponentType) : List[Instance] = {
    instanceDao.getInstancesOfType(compType)
  }

  def getNumberOfInstances(compType : ComponentType) : Int = {
    instanceDao.allInstances().count(i => i.componentType == compType)
  }

  def getMatchingInstanceOfType(compType : ComponentType ) : Try[Instance] = {
    log.info(s"Trying to match to instance of type $compType ...")
    getNumberOfInstances(compType) match {
      case 0 =>
        log.error(s"Cannot match to any instance of type $compType, no such instance present.")
        Failure(new RuntimeException(s"Cannot match to any instance of type $compType, no instance present."))
      case 1 =>
        val instance : Instance = instanceDao.getInstancesOfType(compType).head
        log.info(s"Only one instance of that type present, matching to instance with id ${instance.id.get}.")
        Success(instance)
      case x =>
        log.info(s"Found $x instances of type $compType.")

        //First try: Match to instance with most consecutive positive matching results
        var maxConsecutivePositiveResults = 0
        var instanceToMatch : Instance = null

        for(instance <- instanceDao.getInstancesOfType(compType)){
          if(countConsecutivePositiveMatchingResults(instance.id.get) > maxConsecutivePositiveResults){
            maxConsecutivePositiveResults = countConsecutivePositiveMatchingResults(instance.id.get)
            instanceToMatch = instance
          }
        }

        if(instanceToMatch != null){
          log.info(s"Matching to instance with id ${instanceToMatch.id}, as it has $maxConsecutivePositiveResults positive results in a row.")
          Success(instanceToMatch)
        } else {
          //Second try: Match to instance with most positive matching results
          var maxPositiveResults = 0

          for(instance <- instanceDao.getInstancesOfType(compType)){
            val noOfPositiveResults : Int = instanceDao.getMatchingResultsFor(instance.id.get).get.count(i => i)
            if( noOfPositiveResults > maxPositiveResults){
              maxPositiveResults = noOfPositiveResults
              instanceToMatch = instance
            }
          }

          if(instanceToMatch != null){
            log.info(s"Matching to instance with id ${instanceToMatch.id}, as it has $maxPositiveResults positive results.")
            Success(instanceToMatch)
          } else {
            //All instances are equally good (or bad), match to any of them
            instanceToMatch = instanceDao.getInstancesOfType(compType).head
            log.info(s"Matching to instance with id ${instanceToMatch.id}, no differences between instances have been found.")
            Success(instanceToMatch)
          }
        }
    }

  }

  def applyMatchingResult(id : Long, result : Boolean) : Try[Unit] = {
    if(!instanceDao.hasInstance(id)){
      Failure(new RuntimeException(s"Cannot apply matching result to instance with id $id, that id is not known to the server"))
    } else {
      instanceDao.addMatchingResult(id, result)
    }
  }

  private def countConsecutivePositiveMatchingResults(id : Long) : Int = {
    if(!instanceDao.hasInstance(id) || instanceDao.getMatchingResultsFor(id).get.isEmpty){
      0
    } else {
      val matchingResults = instanceDao.getMatchingResultsFor(id).get
      var count = 0

      for (index <- matchingResults.size to 1){
        if(matchingResults(index - 1)){
          count += 1
        } else {
          return count
        }
      }
      count
    }

  }
}
