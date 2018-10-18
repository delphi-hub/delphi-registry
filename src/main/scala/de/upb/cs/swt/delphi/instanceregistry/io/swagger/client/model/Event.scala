package de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.EventEnums.EventType
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.ComponentType
import spray.json.{DefaultJsonProtocol, DeserializationException, JsObject, JsString, JsValue, JsonFormat}

trait EventJsonSupport extends SprayJsonSupport with DefaultJsonProtocol with InstanceJsonSupport {

  implicit val eventTypeFormat  : JsonFormat[EventType] = new JsonFormat[EventType] {

    def write(eventType : EventType) = JsString(eventType.toString)

    def read(value: JsValue) : EventType = value match {
      case JsString(s) => s match {
        case "StateChangedEvent" => EventType.StateChangedEvent
        case "InstanceAddedEvent" => EventType.InstanceAddedEvent
        case "InstanceRemovedEvent" => EventType.InstanceRemovedEvent
        case "NumbersChangedEvent" => EventType.NumbersChangedEvent
        case x => throw DeserializationException(s"Unexpected string value $x for event type.")
      }
      case y => throw DeserializationException(s"Unexpected type $y during deserialization event type.")
    }
  }

  implicit val registryEventPayloadFormat: JsonFormat[RegistryEventPayload] = new JsonFormat[RegistryEventPayload] {

    def write(payload: RegistryEventPayload) : JsValue = payload match {
      case ncp: NumbersChangedPayload => numbersChangedPayloadFormat.write(ncp)
      case ip:  InstancePayload => instancePayloadFormat.write(ip)
      case _ => throw new RuntimeException("Unsupported type of payload!")
    }

    def read(json: JsValue): RegistryEventPayload = json match{
      case jso: JsObject => if(jso.fields.isDefinedAt("instance")){
        instancePayloadFormat.read(jso)
      } else if(jso.fields.isDefinedAt("noOfCrawlers")){
        numbersChangedPayloadFormat.read(jso)
      } else {
        throw DeserializationException("Unexpected type for event payload!")
      }
      case _ => throw DeserializationException("Unexpected type for event payload!")
    }

  }

  implicit val eventFormat : JsonFormat[RegistryEvent] = jsonFormat2(RegistryEvent)
  implicit val numbersChangedPayloadFormat: JsonFormat[NumbersChangedPayload] = jsonFormat2(NumbersChangedPayload)
  implicit val instancePayloadFormat: JsonFormat[InstancePayload] = jsonFormat1(InstancePayload)

}

final case class RegistryEvent (
  eventType: EventType.Value,
  payload: RegistryEventPayload
)

object RegistryEventFactory {

  def createNumbersChangedEvent(componentType: ComponentType, newNumber: Int) : RegistryEvent =
    RegistryEvent(EventType.NumbersChangedEvent, NumbersChangedPayload(componentType, newNumber))

  def createInstanceAddedEvent(instance: Instance) : RegistryEvent =
    RegistryEvent(EventType.InstanceAddedEvent, InstancePayload(instance))

  def createInstanceRemovedEvent(instance: Instance) : RegistryEvent =
    RegistryEvent(EventType.InstanceRemovedEvent, InstancePayload(instance))

  def createStateChangedEvent(instance: Instance) : RegistryEvent =
    RegistryEvent(EventType.StateChangedEvent, InstancePayload(instance))

}


abstract class RegistryEventPayload

final case class NumbersChangedPayload (componentType: ComponentType, newNumber: Int) extends RegistryEventPayload
final case class InstancePayload(instance: Instance) extends RegistryEventPayload


object EventEnums {

  type EventType = EventType.Value
  object EventType extends Enumeration {
    val StateChangedEvent: Value = Value("StateChangedEvent")
    val InstanceAddedEvent: Value = Value("InstanceAddedEvent")
    val InstanceRemovedEvent: Value = Value("InstanceRemovedEvent")
    val NumbersChangedEvent: Value = Value("NumbersChangedEvent")
  }
}
