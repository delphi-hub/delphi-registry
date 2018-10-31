package de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.EventEnums.EventType
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType
import spray.json.{DefaultJsonProtocol, DeserializationException, JsObject, JsString, JsValue, JsonFormat}

/**
  * Trait defining the implicit JSON formats needed to work with RegistryEvents
  */
trait EventJsonSupport extends SprayJsonSupport with DefaultJsonProtocol with InstanceJsonSupport {

  //Custom JSON format for an EventType
  implicit val eventTypeFormat  : JsonFormat[EventType] = new JsonFormat[EventType] {

    /**
      * Custom write method for serializing an EventType
      * @param eventType The EventType to serialize
      * @return JsString containing the serialized value
      */
    def write(eventType : EventType) = JsString(eventType.toString)

    /**
      * Custom read method for deserialization of an EventType
      * @param value JsValue to deserialize (must be a JsString)
      * @return EventType that has been read
      * @throws DeserializationException Exception thrown when JsValue is in incorrect format
      */
    def read(value: JsValue) : EventType = value match {
      case JsString(s) => s match {
        case "StateChangedEvent" => EventType.StateChangedEvent
        case "InstanceAddedEvent" => EventType.InstanceAddedEvent
        case "InstanceRemovedEvent" => EventType.InstanceRemovedEvent
        case "NumbersChangedEvent" => EventType.NumbersChangedEvent
        case "DockerOperationErrorEvent" => EventType.DockerOperationErrorEvent
        case x => throw DeserializationException(s"Unexpected string value $x for event type.")
      }
      case y => throw DeserializationException(s"Unexpected type $y during deserialization event type.")
    }
  }

  //Custom JSON format for an RegistryEventPayload
  implicit val registryEventPayloadFormat: JsonFormat[RegistryEventPayload] = new JsonFormat[RegistryEventPayload] {

    /**
      * Custom write method for serializing an RegistryEventPayload
      * @param payload The payload to serialize
      * @return JsString containing the serialized value
      */
    def write(payload: RegistryEventPayload) : JsValue = payload match {
      case ncp: NumbersChangedPayload => numbersChangedPayloadFormat.write(ncp)
      case ip:  InstancePayload => instancePayloadFormat.write(ip)
      case doep: DockerOperationErrorPayload => dockerOperationErrorPayloadFormat.write(doep)
      case _ => throw new RuntimeException("Unsupported type of payload!")
    }

    /**
      * Custom read method for deserialization of an RegistryEventPayload
      * @param json JsValue to deserialize
      * @return RegistryEventPayload that has been read
      *@throws DeserializationException Exception thrown when JsValue is in incorrect format
      */
    def read(json: JsValue): RegistryEventPayload = json match{
      case jso: JsObject => if(jso.fields.isDefinedAt("instance")){
        instancePayloadFormat.read(jso)
      } else if(jso.fields.isDefinedAt("noOfCrawlers")){
        numbersChangedPayloadFormat.read(jso)
      } else if(jso.fields.isDefinedAt("errorMessage")) {
        dockerOperationErrorPayloadFormat.read(jso)
      } else {
        throw DeserializationException("Unexpected type for event payload!")
      }
      case _ => throw DeserializationException("Unexpected type for event payload!")
    }

  }

  //JSON format for RegistryEvents
  implicit val eventFormat : JsonFormat[RegistryEvent] = jsonFormat2(RegistryEvent)

  //JSON format for an NumbersChangedPayload
  implicit val numbersChangedPayloadFormat: JsonFormat[NumbersChangedPayload] = jsonFormat2(NumbersChangedPayload)

  //JSON format for an InstancePayload
  implicit val instancePayloadFormat: JsonFormat[InstancePayload] = jsonFormat1(InstancePayload)

  //JSON format for an DockerOperationErrorPayload
  implicit val dockerOperationErrorPayloadFormat: JsonFormat[DockerOperationErrorPayload] =
    jsonFormat2(DockerOperationErrorPayload)

}

/**
  * The RegistryEvent used for communicating with the management application
  * @param eventType Type of the event
  * @param payload Payload of the event, depends on the type
  */
final case class RegistryEvent (
  eventType: EventType.Value,
  payload: RegistryEventPayload
)

/**
  * Factory object for creating different types of events
  */
object RegistryEventFactory {

  /**
    * Creates a new NumbersChangedEvent. Sets EventType and payload accordingly.
    * @param componentType ComponentType which's numbers have been updated
    * @param newNumber New number of components of the specified type
    * @return RegistryEvent with the respective type and payload.
    */
  def createNumbersChangedEvent(componentType: ComponentType, newNumber: Int) : RegistryEvent =
    RegistryEvent(EventType.NumbersChangedEvent, NumbersChangedPayload(componentType, newNumber))

  /**
    * Creates a new InstanceAddedEvent. Sets EventType and payload accordingly.
    * @param instance Instance that has been added.
    * @return RegistryEvent with the respective type and payload.
    */
  def createInstanceAddedEvent(instance: Instance) : RegistryEvent =
    RegistryEvent(EventType.InstanceAddedEvent, InstancePayload(instance))

  /**
    * Creates a new InstanceRemovedEvent. Sets EventType and payload accordingly.
    * @param instance Instance that has been removed.
    * @return RegistryEvent with the respective type and payload.
    */
  def createInstanceRemovedEvent(instance: Instance) : RegistryEvent =
    RegistryEvent(EventType.InstanceRemovedEvent, InstancePayload(instance))

  /**
    * Creates a new StateChangedEvent. Sets EventType and payload accordingly.
    * @param instance Instance which's state was changed.
    * @return RegistryEvent with tht respective type and payload.
    */
  def createStateChangedEvent(instance: Instance) : RegistryEvent =
    RegistryEvent(EventType.StateChangedEvent, InstancePayload(instance))

  /**
    * Creates a new DockerOperationErrorEvent. Sets EventType and payload accordingly.
    * @param affectedInstance Option[Instance] containing the instance that may be affected
    * @param message Error message
    * @return RegistryEvent with the respective type and payload.
    */
  def createDockerOperationErrorEvent(affectedInstance: Option[Instance], message: String) : RegistryEvent =
    RegistryEvent(EventType.DockerOperationErrorEvent, DockerOperationErrorPayload(affectedInstance, message))

}

/**
  * Abstract superclass for the payload of RegistryEvents. Does not declare any members, but needs to be
  * extended by classes that will be sent as payloads of events
  */
abstract class RegistryEventPayload

/**
  * The NumbersChangedPayload is sent with events of type NumbersChangedEvent. It contains the ComponentType
  * which's number has been updated, and the new number of the respective type of components.
  * @param componentType ComponentType which's number was updated
  * @param newNumber New number of instances for the ComponentType
  */
final case class NumbersChangedPayload (componentType: ComponentType, newNumber: Int) extends RegistryEventPayload

/**
  * The InstancePayload is sent with events of type InstanceAdded- /InstanceRemoved- /StateChanged-Event. It contains an
  * instance that was added / removed / changed.
  * @param instance Instance that caused the event.
  */
final case class InstancePayload(instance: Instance) extends RegistryEventPayload

/**
  * This InstancePayload is sent with events of type DockerOperationErrorEvent. It contains an error message an optionally
  * the instance that was affected by the error.
  * @param affectedInstance Option[Instance] which may contain the instance affected
  * @param errorMessage ErrorMessage that was issued
  */
final case class DockerOperationErrorPayload(affectedInstance: Option[Instance], errorMessage: String)
  extends RegistryEventPayload


/**
  * Enumerations concerning Events
  */
object EventEnums {

  //Type to use when working with component types
  type EventType = EventType.Value

  /**
    * EventType enumeration defining the valid types of events issued by the instance registry
    */
  object EventType extends Enumeration {
    val StateChangedEvent: Value = Value("StateChangedEvent")
    val InstanceAddedEvent: Value = Value("InstanceAddedEvent")
    val InstanceRemovedEvent: Value = Value("InstanceRemovedEvent")
    val NumbersChangedEvent: Value = Value("NumbersChangedEvent")
    val DockerOperationErrorEvent: Value = Value("DockerOperationErrorEvent")
  }
}
