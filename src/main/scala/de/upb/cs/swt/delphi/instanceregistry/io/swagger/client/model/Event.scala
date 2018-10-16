package de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, DeserializationException, JsString, JsValue, JsonFormat}

trait EventJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

  implicit val eventTypeFormat  : JsonFormat[EventEnums.EventType] = new JsonFormat[EventEnums.EventType] {

    def write(eventType : EventEnums.EventType) = JsString(eventType.toString)

    def read(value: JsValue) : EventEnums.EventType = value match {
      case JsString(s) => s match {
        case "StateChangedEvent" => EventEnums.EventType.StateChangedEvent
        case "InstanceAddedEvent" => EventEnums.EventType.InstanceAddedEvent
        case "InstanceRemovedEvent" => EventEnums.EventType.InstanceRemovedEvent
        case "NumbersChangedEvent" => EventEnums.EventType.NumbersChangedEvent
        case x => throw DeserializationException(s"Unexpected string value $x for event type.")
      }
      case y => throw DeserializationException(s"Unexpected type $y while deserializing event type.")
    }
  }

  implicit val eventFormat : JsonFormat[Event] = jsonFormat2(Event)
}

final case class Event (
     eventType: EventEnums.EventType,
     additionalData: String)

object EventEnums {

  type EventType = EventType.Value
  object EventType extends Enumeration {
    val StateChangedEvent: Value = Value("StateChangedEvent")
    val InstanceAddedEvent: Value = Value("InstanceAddedEvent")
    val InstanceRemovedEvent: Value = Value("InstanceRemovedEvent")
    val NumbersChangedEvent: Value = Value("NumbersChangedEvent")
  }
}
