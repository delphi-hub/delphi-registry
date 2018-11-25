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

package de.upb.cs.swt.delphi.registry.connection
import akka.http.javadsl.model.StatusCodes
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import de.upb.cs.swt.delphi.instanceregistry.Registry
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.Instance
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import org.scalatest.{Matchers, WordSpec}
import de.upb.cs.swt.delphi.instanceregistry.connection.Server.routes

import scala.concurrent.Future
import scala.util.Random

class ServerTest extends WordSpec with Matchers with  ScalatestRouteTest {

  private def buildInstance(id: Long, dockerId: Option[String] = None, state: InstanceState.Value = InstanceState.Stopped): Instance = {
    Instance(Some(id), "https://localhost", 12345, "TestInstance", ComponentType.ElasticSearch, dockerId, state)
  }
  def randomId(length: Int = 12) =
    Random.alphanumeric.take(length).mkString


  override def beforeAll(): Unit = {
    Future(Registry.main(Array[String]()))
    Thread.sleep(1000)
      //Await.ready(Future(Registry.main(Array[String]())),Duration("5 seconds"))
  }

  "The Server" should {
    "return method not allowed while registering" in {
      Get("/register?InstanceString=25")  ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
      }
    }
    "Successfully Register" in {
      //Post("/register", requestBody.toJson).withHeaders("if Any") ~> Route.seal(routes) ~> check {
      //  status shouldBe StatusCodes.OK


     // Post("/register", HttpEntitywithHeaders("if Any") ~> Route.seal(routes) ~> check {
      //  status shouldBe StatusCodes.OK

      //Post("/register",
      //Post("/register" + randomId(1)+"/") ~> Route.seal(routes) ~> check {
        //assert(status === StatusCodes.OK)
      // HttpEntity(ContentTypes.`application/json`,"""{InstanceToRegister:body}""")

      Post("/register?InstanceString", HttpEntity(ContentTypes.`application/json`,
        """{"name":"MyWebApiInstance","host":"127.0.1.2","instanceState":"Running","componentType":"WebApi","portNumber":"8086}""".stripMargin)) ~> Route.seal(routes) ~> check {
       assert(status === StatusCodes.OK)
       //assert(response.status === 200)
       //contentType should ===(ContentTypes.`application/json`)
       //responseEntity shouldBe Int

     }
    }
    "Not register with Invalid Input" in {
      Post("/register?InstanceString=0") ~> routes ~> check {
        assert(status === StatusCodes.INTERNAL_SERVER_ERROR)
        responseAs[String] should === ("An internal server error occurred.")

      }
    }
    "Successfully Deregister" in {
      Post("/deregister?Id=0") ~> routes ~> check {
        assert(status === StatusCodes.OK)

        entityAs[String] should === ("Successfully removed instance with id 0")
      }
    }
    "Validation Exception: Method not allowed" in {
      Get("/deregister?Id=0") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
      }
    }
    "Could not Deregister Instance" in {
      Post("/deregister?Id=kilo") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.BAD_REQUEST)

      }
    }
    "Instance not found" in {
      Post("/deregister?Id=30") ~> routes ~> check {
        responseAs[String] shouldEqual("Id 30 not known to the server")
      }
    }
    "Validation Exception: Method not allowed while fetching number of instances" in {
      Post("/numberOfInstances?ComponentType=Crawler") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
      }
    }
    "Return Error 400 Bad Request" in {
      Get("/numberOfInstances?ComponentType=Crawlerr") ~> routes ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
      }
    }
    "Should display number of instances of specific componentType" in {
      Get("/numberOfInstances?ComponentType=ElasticSearch") ~> routes ~> check {
        assert(status === StatusCodes.OK)
      }
    }
    "Instances not found" in {
      Get("/numberOfInstances?ComponentType=Crawler") ~> routes ~> check {
        assert(status === StatusCodes.NOT_FOUND)
      }
    }

  "Return POST method not allowed while matching instance" in {
    Post("/matchingInstance?ComponentType=ElasticSearch") ~> Route.seal(routes) ~> check {
      assert(status === StatusCodes.METHOD_NOT_ALLOWED)
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
    }
  }
    "Return Matching instance of specific type" in {
      Get("/matchingInstance?ComponentType=ElasticSearch", HttpEntity(ContentTypes.`application/json`,"ElasticSearch")) ~> routes ~> check {
      assert(status === StatusCodes.OK)
    }
  }

  "Return Bad Request:Could not find matching instance" in {
    Get("/matchingInstance?Id=0&ComponentType=ElasticSarch") ~> Route.seal(routes) ~> check {
      assert(status === StatusCodes.BAD_REQUEST)
    }
  }
  "This Method not allowed while fetching instance" in {
    Post("/instances?ComponentType=ElasticSearch") ~> Route.seal(routes) ~> check {
      assert(status === StatusCodes.METHOD_NOT_ALLOWED)
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
    }
  }
    "Display All Matching Instance" in {
      Get("/instances?ComponentType=ElasticSearch") ~> routes ~> check {
        assert(status === StatusCodes.OK)
      }
    }
  "Return Failed to deserialize while fetching instance" in {
    Get("/instances?ComponentType=ElastcSearch") ~> Route.seal(routes) ~> check {
      assert(status === StatusCodes.BAD_REQUEST)
    }
  }
  "Return GET Method not allowed in matching result" in {
    Get("/matchingResult?Id=1&MatchingSuccessful=0") ~> Route.seal(routes) ~> check {
      assert(status === StatusCodes.METHOD_NOT_ALLOWED)
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
    }
  }
    "Return Matching Result not found" in {
      Post("/matchingResult?Id=1&MatchingSuccessful=0") ~> routes ~> check {
        assert(status === StatusCodes.NOT_FOUND)
      }
    }
    "Return Invalid ID if input is wrong" in {
      Post("/matchingResult?Id=lm&MatchingSuccessful=0") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
      }
    }

  "Method not allowed in eventlist" in {
    Post("/eventList?Id=0") ~> Route.seal(routes) ~> check {
      assert(status === StatusCodes.METHOD_NOT_ALLOWED)
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
    }
  }
  " return Instance ID not found in event list" in {
    Get("/eventList?Id=45") ~> routes ~> check {
      assert(status === StatusCodes.NOT_FOUND)
    }
  }
  }
}