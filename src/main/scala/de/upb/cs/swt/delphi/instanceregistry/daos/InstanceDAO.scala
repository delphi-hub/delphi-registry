package de.upb.cs.swt.delphi.instanceregistry.daos

import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.{Instance, InstanceLink, InstanceNetwork, RegistryEvent}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.LinkEnums.LinkState

import scala.util.Try

/**
  * An Data Access Object to access the set of registered instances
  */
trait InstanceDAO {

  /***
    * Add a new instance to the DAO.
    * @param instance Instance to add (attribute 'id' must not be empty!)
    * @return Success if id was not already present, Failure otherwise
    */
  def addInstance(instance : Instance) : Try[Unit]

  /**
    * Checks whether the DAO holds an instance with the specified id.
    * @param id Id to look for
    * @return True if id is present, false otherwise
    */
  def hasInstance(id: Long) : Boolean

  /**
    * Removes the instance with the given id from the DAO.
    * @param id Id of the instance that will be removed
    * @return Success if id was present, Failure otherwise
    */
  def removeInstance(id: Long) : Try[Unit]

  /**
    * Gets the instance with the specified id from the DAO
    * @param id Id of the instance to retrieve
    * @return Some(instance) if present, else None
    */
  def getInstance(id: Long) : Option[Instance]

  /**
    * Retrieves all instances of the specified ComponentType from the DAO
    * @param componentType ComponentType to look for
    * @return A list of instances with the specified type
    */
  def getInstancesOfType(componentType : ComponentType) : List[Instance]

  /**
    * Retrieves all instances from the DAO
    * @return A list of all instances in the DAO
    */
  def allInstances() : List[Instance]

  /**
    * Removes all instances from the DAO
    */
  def removeAll() : Unit

  /**
    * Add a matching result for the specified instance
    * @param id Id of the instance to post a result for
    * @param matchingSuccessful Boolean indicating whether the matching was successful
    */
  def addMatchingResult(id: Long, matchingSuccessful : Boolean) : Try[Unit]

  /**
    * Gets the list of matching results for the instance with the specified id
    * @param id Id of the instance
    * @return List of boolean values
    */
  def getMatchingResultsFor(id: Long) : Try[List[Boolean]]

  /**
    * Initializes the DAO
    */
  def initialize() : Unit

  /**
    * Shuts the DAO down
    */
  def shutdown(): Unit

  /**
    * If successful, returns the docker handle of the instance with the specified id. If the specified instance is not
    * present or not running as a docker container, Failure will be returned.
    */
  def getDockerIdFor(id: Long) : Try[String]

  /**
    * If successful, sets the state for the instance with the given id to the given state.
    * @param id Id of the instance
    * @param state New state to set
    */
  def setStateFor(id: Long, state: InstanceState.Value) : Try[Unit]

  /**
    * Add an event to the specified instance
    * @param id Id of the instance that the event should be added to
    * @param event Event to add
    * @return Success if instance is present, Failure otherwise
    */
  def addEventFor(id: Long, event: RegistryEvent) : Try[Unit]

  /**
    * Gets the list of events for the instance with the specified id
    * @param id Id of the instance
    * @return List of events if instance is present, Failure otherwise
    */
  def getEventsFor(id: Long) : Try[List[RegistryEvent]]

  /**
    * Adds a new instance link to the dao. Will fail if the ids referenced in the link object are not present.
    * @param link Link to add
    * @return Success if both ids are present, Failure otherwise
    */
  def addLink(link: InstanceLink) : Try[Unit]

  /**
    * Update the link between the two instances specified by the parameter.
    * @param link Link to update
    * @return Success if link is present, Failure otherwise
    */
  def updateLink(link: InstanceLink) : Try[Unit]

  /**
    * Get all outgoing links from the specified instance. Optionally a LinkState can be specified as filter
    * @param idFrom Id of the instance
    * @param state Option[LinkState] to filter for certain LinkStates. If None, no filter will be applied.
    * @return List of matching InstanceLinks
    */
  def getLinksFrom(idFrom: Long, state: Option[LinkState] = None) : List[InstanceLink]

  /**
    * Get all incoming links to the specified instance. Optionally a LinkState can be specified as filter
    * @param idFrom Id of the instance
    * @param state Option[LinkState] to filter for certain LinkStates. If None, no filter will be applied.
    * @return List of matching InstanceLinks
    */
  def getLinksTo(idFrom: Long, state: Option[LinkState] = None) : List[InstanceLink]

  /**
    * Get the current Instance Network
    * @return InstanceNetwork object
    */
  def getNetwork() : InstanceNetwork
}