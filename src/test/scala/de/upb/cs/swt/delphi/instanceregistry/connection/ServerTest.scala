// Copyright (C) 2018 The Delphi Team.
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package de.upb.cs.swt.delphi.instanceregistry.connection

import akka.http.javadsl.model.StatusCodes
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import de.upb.cs.swt.delphi.instanceregistry.Docker.DockerConnection
import de.upb.cs.swt.delphi.instanceregistry.{Configuration, Registry, RequestHandler}
import org.scalatest.{Matchers, WordSpec}
import de.upb.cs.swt.delphi.instanceregistry.daos.{DynamicInstanceDAO, InstanceDAO}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.EventEnums.EventType
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model._
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.LinkEnums.LinkState
import spray.json._

import scala.concurrent.duration.Duration
import scala.concurrent.Await
import scala.util.{Failure, Success, Try}


class ServerTest
  extends WordSpec
  with Matchers
  with ScalatestRouteTest
  with InstanceJsonSupport
  with EventJsonSupport {

  private val configuration: Configuration = new Configuration()
  private val dao: InstanceDAO = new DynamicInstanceDAO(configuration)
  private val requestHandler: RequestHandler = new RequestHandler(configuration, dao, DockerConnection.fromEnvironment())
  private val server: Server = new Server(requestHandler)

  //JSON CONSTANTS
  private val validJsonInstance = Instance(id = None, host = "http://localhost", portNumber = 4242,
    name = "ValidInstance", componentType = ComponentType.Crawler, dockerId = Some("randomId"),
    instanceState = InstanceState.Running, labels = List("some_label"), linksTo = List.empty, linksFrom = List.empty)
    .toJson(instanceFormat).toString
  //Valid Json syntax but missing a required member for instances
  private val validJsonInstanceMissingRequiredMember = validJsonInstance.replace(""""name":"ValidInstance",""", "")
  //Invalid Json syntax: missing quotation mark
  private val invalidJsonInstance = validJsonInstance.replace(""""name":"ValidInstance",""", """"name":Invalid", """)




  /**
    * Before all tests: Initialize handler and wait for server binding to be ready.
    */
  override def beforeAll(): Unit = {
    requestHandler.initialize()
  }

  /**
    * After all tests: Unbind the server, shutdown handler and await termination of both actor systems.
    */
  override def afterAll(): Unit = {
    requestHandler.shutdown()
    Await.ready(Registry.system.terminate(), Duration.Inf)
    Await.ready(system.terminate(), Duration.Inf)
  }

  "The Server" should {

    //Valid register and deregister
    "successfully register and deregister when entity is valid" in {
      //Register, actual test
      val id = assertValidRegister(ComponentType.Crawler)
      //Deregister to not pollute DB
      assertValidDeregister(id)
    }

    //Invalid register
    "not register when entity is invalid" in {
      //No entity
      Post("/register") ~> server.routes ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
        responseAs[String].toLowerCase should include("failed to parse json")
      }

      //Wrong JSON syntax
      Post("/register", HttpEntity(ContentTypes.`application/json`, invalidJsonInstance.stripMargin)) ~> server.routes ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
        responseAs[String].toLowerCase should include("failed to parse json")
      }

      //Missing required JSON members
      Post("/register", HttpEntity(ContentTypes.`application/json`, validJsonInstanceMissingRequiredMember.stripMargin)) ~> server.routes ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
        responseAs[String].toLowerCase should include("could not deserialize parameter instance")
      }

      //Invalid HTTP method
      Get("/register?InstanceString=25") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
      }

    }

    //Invalid deregister
    "not deregister if method is invalid, id is missing or invalid" in {
      //Id missing
      Post("/deregister") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.NOT_FOUND)
        responseAs[String].toLowerCase should include("missing required query parameter")
      }

      //Id wrong type
      Post("/deregister?Id=kilo") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
        responseAs[String].toLowerCase should include("not a valid 64-bit signed integer value")
      }

      //Id not present
      Post(s"/deregister?Id=${Long.MaxValue}") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.NOT_FOUND)
        responseAs[String].toLowerCase should include("not known to the server")
      }

      //Wrong HTTP method
      Get("/deregister?Id=0") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
      }
    }

    //Valid get instances
    "successfully retrieve list of instances if parameter is valid" in {
      Get("/instances?ComponentType=ElasticSearch") ~> server.routes ~> check {
        assert(status === StatusCodes.OK)
        Try(responseAs[String].parseJson.convertTo[List[Instance]](listFormat(instanceFormat))) match {
          case Success(listOfESInstances) =>
            listOfESInstances.size shouldEqual 1
            listOfESInstances.exists(instance => instance.name.equals("Default ElasticSearch Instance")) shouldBe true
          case Failure(ex) =>
            fail(ex)
        }

      }
      //No instances of that type present, still need to be 200 OK
      Get("/instances?ComponentType=WebApp") ~> server.routes ~> check {
        assert(status === StatusCodes.OK)
      }
    }

    //Invalid get instances
    "not retrieve instances if method is invalid, ComponentType is missing or invalid" in {
      //Wrong HTTP method
      Post("/instances?ComponentType=Crawler") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
      }

      //Wrong parameter value
      Get("/instances?ComponentType=Car") ~> server.routes ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
        responseAs[String].toLowerCase should include("could not deserialize parameter")
      }
    }

    //Valid get number of instances
    "successfully retrieve number of instances if parameter is valid" in {
      Get("/numberOfInstances?ComponentType=ElasticSearch") ~> server.routes ~> check {
        assert(status === StatusCodes.OK)
        Try(responseAs[String].toLong) match {
          case Success(numberOfEsInstances) =>
            numberOfEsInstances shouldEqual 1
          case Failure(ex) =>
            fail(ex)
        }
      }

      //No instances of that type present, still need to be 200 OK
      Get("/numberOfInstances?ComponentType=WebApp") ~> server.routes ~> check {
        assert(status === StatusCodes.OK)
        Try(responseAs[String].toLong) match {
          case Success(numberOfEsInstances) =>
            numberOfEsInstances shouldEqual 0
          case Failure(ex) =>
            fail(ex)
        }
      }
    }

    //Invalid get number of instances
    "not retrieve number of instances if method is invalid, ComponentType is missing or invalid" in {
      //Wrong HTTP method
      Post("/numberOfInstances?ComponentType=Crawler") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
      }

      //Wrong parameter value
      Get("/numberOfInstances?ComponentType=Car") ~> server.routes ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
        responseAs[String].toLowerCase should include("could not deserialize parameter")
      }
    }

    //Valid GET /instance
    "return an instance if id is valid and instance is present" in {
      Get("/instance?Id=0") ~> server.routes ~> check {
        assert(status === StatusCodes.OK)
        Try(responseAs[String].parseJson.convertTo[Instance](instanceFormat)) match {
          case Success(instance) =>
            instance.id.get shouldEqual 0
            instance.name should include ("Default ElasticSearch")
          case Failure(ex) =>
            fail(ex)
        }
      }
    }

    //Invalid GET /instance
    "return 404 if instance id is not known" in {
      Get("/instance?Id=45") ~> server.routes ~> check {
        assert(status === StatusCodes.NOT_FOUND)
        responseAs[String] shouldEqual "Id 45 was not found on the server."
      }
    }


    //Valid GET /matchingInstance
    "return matching instance of specific type if parameters are valid" in {
      //Add a crawler instance for testing
      val id = assertValidRegister(ComponentType.Crawler, dockerId = None)

      //Actual test
      Get(s"/matchingInstance?Id=$id&ComponentType=ElasticSearch") ~> server.routes ~> check {
        assert(status === StatusCodes.OK)
        Try(responseAs[String].parseJson.convertTo[Instance](instanceFormat)) match {
          case Success(esInstance) =>
            esInstance.id.get shouldEqual 0
            esInstance.name shouldEqual "Default ElasticSearch Instance"
          case Failure(ex) =>
            fail(ex)
        }
      }

      //Remove crawler instance
      assertValidDeregister(id)
    }

    //Invalid GET /matchingInstance
    "return bad request when ComponentType is Invalid, Component is not found and Method not allowed" in {

      val webApiId = assertValidRegister(ComponentType.WebApi)
      val crawlerId = assertValidRegister(ComponentType.Crawler)
      val webAppId = assertValidRegister(ComponentType.WebApp)

      //Invalid ComponentType
      Get(s"/matchingInstance?Id=$webApiId&ComponentType=Search") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
      }

      //Unknown callee id, expect 404
      Get("/matchingInstance?Id=45&ComponentType=Crawler") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.NOT_FOUND)
        responseAs[String].toLowerCase should include ("id 45 was not found")
      }

      //Method Not allowed
      Post(s"/matchingInstance?Id=$webApiId&ComponentType=ElasticSearch") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
      }

      //Incompatible types, api asks for crawler - expect 400
      Get(s"/matchingInstance?Id=$webApiId&ComponentType=Crawler") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
        responseAs[String].toLowerCase should include ("invalid dependency type")
      }

      //No instance of desired type present - expect 404
      assertValidDeregister(webApiId)
      Get(s"/matchingInstance?Id=$webAppId&ComponentType=WebApi") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.NOT_FOUND)
        responseAs[String].toLowerCase should include ("could not find matching instance")
      }

      assertValidDeregister(webAppId)
      assertValidDeregister(crawlerId)

    }

    //Valid POST /matchingResult
    "apply a matching result if parameters are valid" in {
      //Add a webapp instance for testing
      val id1 = assertValidRegister(ComponentType.WebApp)
      //Add a WebApi instance for testing
      val id2 = assertValidRegister(ComponentType.WebApi)

      Post(s"/matchingResult?CallerId=$id1&MatchedInstanceId=$id2&MatchingSuccessful=1") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.OK)
        responseAs[String] shouldEqual "Matching result true processed."
      }

      //Remove Instances
      assertValidDeregister(id1)
      assertValidDeregister(id2)
    }

    //Invalid POST /matchingResult
    "not process matching result if method or parameters are invalid" in {
      //Wrong method
      Get("/matchingResult?CallerId=0&MatchedInstanceId=0&MatchingSuccessful=1") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
      }

      //Invalid IDs - expect 404
      Post("/matchingResult?CallerId=1&MatchedInstanceId=2&MatchingSuccessful=0") ~> server.routes ~> check {
        assert(status === StatusCodes.NOT_FOUND)
      }

      //Wrong parameters, caller is same as callee - expect bad request
      Post("/matchingResult?CallerId=0&MatchedInstanceId=0&MatchingSuccessful=O") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
      }
    }

    //Valid GET /eventList
    "returns registry events that are associated to the instance if id is valid" in {
      val id = assertValidRegister(ComponentType.Crawler)
      //TestCase
      Get(s"/eventList?Id=$id") ~> server.routes ~> check {
        assert(status === StatusCodes.OK)
        Try(responseAs[String].parseJson.convertTo[List[RegistryEvent]](listFormat(eventFormat))) match {
          case Success(listOfEvents) =>
            listOfEvents.size shouldBe 1
            listOfEvents.head.eventType shouldEqual EventType.InstanceAddedEvent
          case Failure(ex) =>
            fail(ex)
        }
      }
      //Remove crawler instance
      assertValidDeregister(id)
    }

    //Invalid GET /eventList
    "does not return events if method is invalid or id is not found" in {
      //Wrong Http method
      Post("/eventList?Id=0") ~> Route.seal(server.routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
      }
      //Wrong ID
      Get("/eventList?Id=45") ~> server.routes ~> check {
        assert(status === StatusCodes.NOT_FOUND)
        responseAs[String] shouldEqual "Id 45 not found."

      }
    }

    //Valid GET /network
    "get the whole network graph of the current registry" in {
      Get("/network") ~> server.routes ~> check {
        assert(status === StatusCodes.OK)
        Try(responseAs[String].parseJson.convertTo[List[Instance]](listFormat(instanceFormat))) match {
          case Success(listOfInstances) =>
            listOfInstances.size shouldBe 1
            listOfInstances.head.componentType shouldEqual ComponentType.ElasticSearch
          case Failure(ex) =>
            fail(ex)
        }
      }
    }

    //Valid GET /linksFrom
    "get a list of links from an instance if id is valid and links are present" in {
      //Register a crawler
      val id = assertValidRegister(ComponentType.Crawler)

      //Fake connection from crawler to default ES instance
      Get(s"/matchingInstance?Id=$id&ComponentType=ElasticSearch") ~> server.routes ~> check {
        assert(status === StatusCodes.OK)
        Try(responseAs[String].parseJson.convertTo[Instance](instanceFormat)) match {
          case Success(esInstance) =>
            esInstance.id.get shouldEqual 0
            esInstance.name shouldEqual "Default ElasticSearch Instance"
          case Failure(ex) =>
            fail(ex)
        }
      }

      //Get links from crawler, should be one link to default ES instance
      Get(s"/linksFrom?Id=$id") ~> server.routes ~> check {
        assert(status === StatusCodes.OK)
        Try(responseAs[String].parseJson.convertTo[List[InstanceLink]](listFormat(instanceLinkFormat))) match {
          case Success(listOfLinks) =>
            listOfLinks.size shouldEqual 1
            val link = listOfLinks.head
            link.idFrom shouldEqual id
            link.idTo shouldEqual 0
            link.linkState shouldEqual LinkState.Assigned
          case Failure(ex) =>
            fail(ex)
        }
      }

      //Deregister crawler to not pollute DB
      assertValidDeregister(id)
    }

    //Invalid GET /linksFrom
    "return no links found for invalid id" in {
      Get("/linksFrom?Id=45") ~> server.routes ~> check {
        assert(status === StatusCodes.NOT_FOUND)
      }
    }

    //Valid GET /linksTo
    "get a list of links to the instance with the specified id" in {
      val id = assertValidRegister(ComponentType.Crawler)

      //Fake connection from crawler to default ES instance
      Get(s"/matchingInstance?Id=$id&ComponentType=ElasticSearch") ~> server.routes ~> check {
        assert(status === StatusCodes.OK)
        Try(responseAs[String].parseJson.convertTo[Instance](instanceFormat)) match {
          case Success(esInstance) =>
            esInstance.id.get shouldEqual 0
            esInstance.name shouldEqual "Default ElasticSearch Instance"
          case Failure(ex) =>
            fail(ex)
        }
      }

      //Get links to default ES instance, should be one link from crawler
      Get(s"/linksTo?Id=0") ~> server.routes ~> check {
        assert(status === StatusCodes.OK)
        Try(responseAs[String].parseJson.convertTo[List[InstanceLink]](listFormat(instanceLinkFormat))) match {
          case Success(listOfLinks) =>
            listOfLinks.size shouldEqual 1
            val link = listOfLinks.head
            link.idFrom shouldEqual id
            link.idTo shouldEqual 0
            link.linkState shouldEqual LinkState.Assigned
          case Failure(ex) =>
            fail(ex)
        }
      }

      //Deregister crawler to not pollute DB
      assertValidDeregister(id)
    }

    //Invalid GET /linksTo
    "return no links found to specified id" in {
      Get("/linksTo?Id=45") ~> server.routes ~> check {
        assert(status === StatusCodes.NOT_FOUND)
      }
    }

    //Valid POST /addLabel
    "add a generic label to an instance is label and id are valid" in {
      Post("/addLabel?Id=0&Label=ElasticSearchDefaultLabel") ~> server.routes ~> check {
        assert(status === StatusCodes.OK)
        responseAs[String] shouldEqual "Successfully added label"
      }
    }

    //Invalid POST /addLabel
    "fail to add label if id is invalid or label too long" in{
      //Unknown id - expect 404
      Post("/addLabel?Id=45&Label=Private") ~> server.routes ~> check {
        assert(status === StatusCodes.NOT_FOUND)
        responseAs[String] shouldEqual "Cannot add label, id 45 not found."
      }

      val tooLongLabel = "VeryVeryExtraLongLabelThatDoesNotWorkWhileAddingLabel"
      //Label out of bounds - expect 400
      Post(s"/addLabel?Id=0&Label=$tooLongLabel") ~> server.routes ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
        responseAs[String].toLowerCase should include ("exceeds character limit")
      }
    }

    /**Minimal tests for docker operations**/

    "fail to deploy if component type is invalid" in {
      Post("/deploy?ComponentType=Car") ~> server.routes ~> check {
        status shouldEqual StatusCodes.BAD_REQUEST
        responseAs[String].toLowerCase should include ("could not deserialize")
      }
    }

    "fail to execute docker operations if id is invalid" in {
      Post("/reportStart?Id=42") ~> server.routes ~> check {
        status shouldEqual StatusCodes.NOT_FOUND
        responseAs[String].toLowerCase should include ("not found")
      }
      Post("/reportStop?Id=42") ~> server.routes ~> check {
        status shouldEqual StatusCodes.NOT_FOUND
        responseAs[String].toLowerCase should include ("not found")
      }
      Post("/reportFailure?Id=42") ~> server.routes ~> check {
        status shouldEqual StatusCodes.NOT_FOUND
        responseAs[String].toLowerCase should include ("not found")
      }
      Post("/pause?Id=42") ~> server.routes ~> check {
        status shouldEqual StatusCodes.NOT_FOUND
        responseAs[String].toLowerCase should include ("not found")
      }
      Post("/resume?Id=42") ~> server.routes ~> check {
        status shouldEqual StatusCodes.NOT_FOUND
        responseAs[String].toLowerCase should include ("not found")
      }
      Post("/stop?Id=42") ~> server.routes ~> check {
        status shouldEqual StatusCodes.NOT_FOUND
        responseAs[String].toLowerCase should include ("not found")
      }
      Post("/start?Id=42") ~> server.routes ~> check {
        status shouldEqual StatusCodes.NOT_FOUND
        responseAs[String].toLowerCase should include ("not found")
      }
      Post("/delete?Id=42") ~> server.routes ~> check {
        status shouldEqual StatusCodes.NOT_FOUND
        responseAs[String].toLowerCase should include ("not found")
      }
      Post("/assignInstance?Id=42&AssignedInstanceId=43") ~> server.routes ~> check {
        status shouldEqual StatusCodes.NOT_FOUND
        responseAs[String].toLowerCase should include ("not found")
      }
    }

    "fail to execute docker operations if instance is no docker container" in {
      val id = assertValidRegister(ComponentType.Crawler, dockerId = None)
      Post(s"/reportStart?Id=$id") ~> server.routes ~> check {
        status shouldEqual StatusCodes.BAD_REQUEST
      }
      Post(s"/reportStop?Id=$id") ~> server.routes ~> check {
        status shouldEqual StatusCodes.BAD_REQUEST
      }
      Post(s"/reportFailure?Id=$id") ~> server.routes ~> check {
        status shouldEqual StatusCodes.BAD_REQUEST
      }
      Post(s"/pause?Id=$id") ~> server.routes ~> check {
        status shouldEqual StatusCodes.BAD_REQUEST
      }
      Post(s"/resume?Id=$id") ~> server.routes ~> check {
        status shouldEqual StatusCodes.BAD_REQUEST
      }
      Post(s"/start?Id=$id") ~> server.routes ~> check {
        status shouldEqual StatusCodes.BAD_REQUEST
      }
      Post(s"/delete?Id=$id") ~> server.routes ~> check {
        status shouldEqual StatusCodes.BAD_REQUEST
      }
    }

    "Requests" should {
      "throttle when limit reached" in {
        for(i <- 1 to configuration.maxIndividualIpReq){
          Get(s"/linksTo?Id=0")~> server.routes ~> check {}
        }

        Get(s"/linksTo?Id=0") ~> server.routes ~> check {
          status shouldEqual StatusCodes.BAD_REQUEST
          responseAs[String].toLowerCase should include ("request limit exceeded")
        }
      }
    }
  }
  private def assertValidRegister(compType: ComponentType,
                                  dockerId: Option[String] = Some("randomId"),
                                  labels: List[String] = List("some_label")) : Long = {

    val instanceString = Instance(id = None, host = "http://localhost", portNumber = 4242,
      name = "ValidInstance", componentType = compType, dockerId = dockerId,
      instanceState = InstanceState.Running, labels = labels, linksTo = List.empty, linksFrom = List.empty)
      .toJson(instanceFormat).toString

    Post("/register", HttpEntity(ContentTypes.`application/json`,
      instanceString.stripMargin)) ~> Route.seal(server.routes) ~> check {
      assert(status === StatusCodes.OK)
      responseEntity match {
        case HttpEntity.Strict(_, data) =>
          val responseEntityString = data.utf8String
          assert(Try(responseEntityString.toLong).isSuccess)
          responseEntityString.toLong
        case x =>
          fail(s"Invalid response type $x")
      }
    }
  }

  private def assertValidDeregister(id: Long): Unit = {
    Post(s"/deregister?Id=$id") ~> server.routes ~> check {
      assert(status === StatusCodes.OK)
      entityAs[String].toLowerCase should include("successfully removed instance")
    }
  }


}
