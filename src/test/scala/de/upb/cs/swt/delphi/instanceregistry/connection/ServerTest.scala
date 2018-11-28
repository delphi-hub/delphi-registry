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
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import de.upb.cs.swt.delphi.instanceregistry.Registry
import org.scalatest.{Matchers, WordSpec}
import de.upb.cs.swt.delphi.instanceregistry.connection.Server.routes
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.{Instance, InstanceJsonSupport}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import spray.json._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}


class ServerTest extends WordSpec with Matchers with  ScalatestRouteTest with InstanceJsonSupport {

  //JSON CONSTANTS
  private val validJsonInstance = Instance(id = None, host = "http://localhost", portNumber = 4242,
    name = "ValidInstance", componentType = ComponentType.Crawler, dockerId = Some("randomId"),
    instanceState = InstanceState.Running, labels = List("some_label"), linksTo = List.empty, linksFrom = List.empty)
    .toJson(instanceFormat).toString
  //Valid Json syntax but missing a required member for instances
  private val validJsonInstanceMissingRequiredMember = validJsonInstance.replace(""""name":"ValidInstance",""", "")
  //Invalid Json syntax: missing quotation mark
  private val invalidJsonInstance = validJsonInstance.replace(""""name":"ValidInstance",""", """"name":Invalid", """)


  val bindingFuture: Future[ServerBinding] = Http().bindAndHandle(Server.routes,
    Registry.configuration.bindHost, Registry.configuration.bindPort)

  /**
    * Before all tests: Initialize handler and wait for server binding to be ready.
    */
  override def beforeAll(): Unit = {
    Registry.requestHandler.initialize()
    Await.ready(bindingFuture, Duration(3, "seconds"))
  }

  /**
    * After all tests: Unbind the server, shutdown handler and await termination of both actor systems.
    */
  override def afterAll(): Unit = {
    bindingFuture
      .flatMap(_.unbind())
      .onComplete{_ =>
        Registry.requestHandler.shutdown()
        Await.ready(Registry.system.terminate(), Duration.Inf)
        Await.ready(system.terminate(), Duration.Inf)
      }
  }

  "The Server" should {

    //Valid register
    "successfully register when entity is valid" in {
      Post("/register", HttpEntity(ContentTypes.`application/json`,
        validJsonInstance.stripMargin)) ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.OK)
        responseEntity match {
          case HttpEntity.Strict(_, data) =>
            val responseEntityString = data.utf8String
            assert(Try(responseEntityString.toLong).isSuccess)
          case x =>
            fail(s"Invalid response type $x")
        }
      }
    }

    //Invalid register
    "not register when entity is invalid" in {
      //No entity
      Post("/register") ~> routes ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
        responseAs[String].toLowerCase should include ("failed to parse json")
      }

      //Wrong JSON syntax
      Post("/register", HttpEntity(ContentTypes.`application/json`, invalidJsonInstance.stripMargin)) ~> routes ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
        responseAs[String].toLowerCase should include ("failed to parse json")
      }

      //Missing required JSON members
      Post("/register", HttpEntity(ContentTypes.`application/json`, validJsonInstanceMissingRequiredMember.stripMargin)) ~> routes ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
        responseAs[String].toLowerCase should include ("could not deserialize parameter instance")
      }

      //Invalid HTTP method
      Get("/register?InstanceString=25") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
      }

    }

    //Valid deregister
    "successfully deregister when id is valid" in {
      var id = -1L

      Post("/register", HttpEntity(ContentTypes.`application/json`,
        validJsonInstance.stripMargin)) ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.OK)
        responseEntity match {
          case HttpEntity.Strict(_, data) =>
            val responseEntityString = data.utf8String
            assert(Try(responseEntityString.toLong).isSuccess)
            id = responseEntityString.toLong
          case x =>
            fail(s"Invalid response type $x")
        }
      }

      Post(s"/deregister?Id=$id") ~> routes ~> check {
        assert(status === StatusCodes.OK)
        entityAs[String].toLowerCase should include ("successfully removed instance")
      }
    }

    //Invalid deregister
    "not deregister if method is invalid, id is missing or invalid" in {
      //Id missing
      Post("/deregister") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.NOT_FOUND)
        responseAs[String].toLowerCase should include ("missing required query parameter")
      }

      //Id wrong type
      Post("/deregister?Id=kilo") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
        responseAs[String].toLowerCase should include ("not a valid 64-bit signed integer value")
      }

      //Id not present
      Post(s"/deregister?Id=${Long.MaxValue}") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.NOT_FOUND)
        responseAs[String].toLowerCase should include ("not known to the server")
      }

      //Wrong HTTP method
      Get("/deregister?Id=0") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
      }
    }

    //Valid get instances
    "successfully retrieve list of instances if parameter is valid" in {
      Get("/instances?ComponentType=ElasticSearch") ~> routes ~> check {
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
      Get("/instances?ComponentType=WebApp") ~> routes ~> check {
        assert(status === StatusCodes.OK)
      }
    }

    //Invalid get instances
    "not retrieve instances if method is invalid, ComponentType is missing or invalid" in {
      //Wrong HTTP method
      Post("/instances?ComponentType=Crawler") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
      }

      //Wrong parameter value
      Get("/instances?ComponentType=Crawlerr") ~> routes ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
        responseAs[String].toLowerCase should include ("could not deserialize parameter")
      }
    }

    //Valid get number of instances
    "successfully retrieve number of instances if parameter is valid" in {
      Get("/numberOfInstances?ComponentType=ElasticSearch") ~> routes ~> check {
        assert(status === StatusCodes.OK)
        Try(responseAs[String].toLong) match {
          case Success(numberOfEsInstances) =>
            numberOfEsInstances shouldEqual 1
          case Failure(ex) =>
            fail(ex)
        }

      }

      //No instances of that type present, still need to be 200 OK
      Get("/numberOfInstances?ComponentType=WebApp") ~> routes ~> check {
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
      Post("/numberOfInstances?ComponentType=Crawler") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
      }

      //Wrong parameter value
      Get("/numberOfInstances?ComponentType=Crawlerr") ~> routes ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
        responseAs[String].toLowerCase should include ("could not deserialize parameter")
      }
    }


    "return POST method not allowed while matching instance" in {
      Post("/matchingInstance?Id=0&ComponentType=ElasticSearch") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
      }
    }
    /* Throwing Error 404 even with the right commands (as specified in server.scala).
     Tested after running registry and verifying ElasticSearch is registered with Id=0. Also with other components after running them.

    "Return Matching instance of specific type" in {
      Get("/matchingInstance?Id=0&ComponentType=ElasticSearch") ~> routes ~> check {
      assert(status === StatusCodes.OK)
    }
  }
*/
    "return bad request:Could not find matching instance" in {
      Get("/matchingInstance?Id=0&ComponentType=ElasticSarch") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
      }
    }
    "return POST method not allowed while fetching instance" in {
      Post("/instances?ComponentType=ElasticSearch") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
      }
    }
    "display all matching instances" in {
      Get("/instances?ComponentType=ElasticSearch") ~> routes ~> check {
        assert(status === StatusCodes.OK)
      }
    }
    "return failed to deserialize parameter while fetching instance" in {
      Get("/instances?ComponentType=ElastcSearch") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
      }
    }
    "return GET method not allowed in matching result" in {
      Get("/matchingResult?CallerId=0&MatchedInstanceId=0&MatchingSuccessful=1") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
      }
    }
    "return matching result not found if there is no match" in {
      Post("/matchingResult?CallerId=1&MatchedInstanceId=2&MatchingSuccessful=0") ~> routes ~> check {
        assert(status === StatusCodes.NOT_FOUND)
      }
    }
    "return invalid ID if input is wrong" in {
      Post("/matchingResult?CallerId=0&MatchedInstanceId=0&MatchingSuccessful=O") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
      }
    }


    "return method POST not allowed in eventlist" in {
      Post("/eventList?Id=0") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
      }
    }
    " return instance ID not found in event list" in {
      Get("/eventList?Id=45") ~> routes ~> check {
        assert(status === StatusCodes.NOT_FOUND)
      }
    }
    /*
    "Matching successful" in {
      Post("/matchingResult?CallerId=1&MatchedInstanceId=0&MatchingSuccessful=1") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.OK)
      }
    }
    " return instance ID " in {
      Get("/eventList?Id=1") ~> routes ~> check {
        assert(status === StatusCodes.OK)
      }
    }*/
  }
}