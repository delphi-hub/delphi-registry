package de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, DeserializationException, JsObject, JsString, JsValue, JsonFormat}

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

  implicit val eventFormat : JsonFormat[Event] = new JsonFormat[Event] {

    override def write(event: Event): JsValue = event match {
      case nce: NumbersChangedEvent => numbersChangedEventFormat.write(nce)
      case iae: InstanceAddedEvent => instanceAddedEventFormat.write(iae)
      case unrecognized => throw new RuntimeException(s"Unexpected type for event: $unrecognized")
    }

    override def read(json: JsValue): Event = json match {
      case known:JsObject if known.fields.contains("eventType") =>
        known.fields("eventType") match {
          case JsString("NumbersChangedEvent") => numbersChangedEventFormat.read(known)
          case JsString("InstanceAddedEvent") => instanceAddedEventFormat.read(known)
          case unknown => throw DeserializationException(s"Unknown event type $unknown while deserializing.")
        }
      case _ => throw DeserializationException(s"Unknown event object, no type present.")
    }
  }

  implicit val numbersChangedEventFormat: JsonFormat[NumbersChangedEvent] = jsonFormat4(NumbersChangedEvent)
  implicit val instanceAddedEventFormat: JsonFormat[InstanceAddedEvent] = jsonFormat2(InstanceAddedEvent)

}

abstract class Event {
  val eventType: EventEnums.EventType.Value
}

final case class NumbersChangedEvent (
  noOfCrawlers: Int,
  noOfApis: Int,
  noOfWebApps: Int,
  override val eventType: EventEnums.EventType.Value = EventEnums.EventType.NumbersChangedEvent
) extends Event

final case class InstanceAddedEvent (
   instanceAdded: Instance,
   override val eventType: EventEnums.EventType.Value = EventEnums.EventType.InstanceAddedEvent
) extends Event

object EventEnums {

  type EventType = EventType.Value
  object EventType extends Enumeration {
    val StateChangedEvent: Value = Value("StateChangedEvent")
    val InstanceAddedEvent: Value = Value("InstanceAddedEvent")
    val InstanceRemovedEvent: Value = Value("InstanceRemovedEvent")
    val NumbersChangedEvent: Value = Value("NumbersChangedEvent")
  }
}
