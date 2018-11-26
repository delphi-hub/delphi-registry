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
import org.scalatest.{Matchers, WordSpec}
import de.upb.cs.swt.delphi.instanceregistry.connection.Server.routes
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}


class ServerTest extends WordSpec with Matchers with  ScalatestRouteTest {

  override def beforeAll(): Unit = {
    Future(Registry.main(Array[String]()))
    Thread.sleep(1000)
    //def a = Future(Registry.main(Array[String]()))
    //Await.ready(a, Duration("1 second"))
    //Await.ready(Future(Registry.main(Array[String]())),Duration("1 second"))
  }

  "The Server" should {
    "return method not allowed while registering" in {
      Get("/register?InstanceString=25")  ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
      }
    }
    "Successfully Register" in {
      //Post("/register?InstanceToRegister", requestBody.toJson).withHeaders("if Any") ~> Route.seal(routes) ~> check {
      //  status shouldBe StatusCodes.OK


     // Post("/register", HttpEntitywithHeaders("if Any") ~> Route.seal(routes) ~> check {
      //  status shouldBe StatusCodes.OK

      //Post("/register",
      //Post("/register" + randomId(1)+"/") ~> Route.seal(routes) ~> check {
        //assert(status === StatusCodes.OK)
      // HttpEntity(ContentTypes.`application/json`,"""{InstanceToRegister:Crawler}""")

      Post("/register", HttpEntity(ContentTypes.`application/json`,
        """{"name":"MyWebApiInstance","host":"127.0.1.1","instanceState":"Running","componentType":"WebApi","portNumber":"8089}""".stripMargin)) ~> Route.seal(routes) ~> check {
       assert(status === StatusCodes.OK)
       //assert(response.status === 200)
       //contentType should ===(ContentTypes.`application/json`)
       //responseEntity shouldBe Int

     }
    }
    "not register with Invalid Input" in {
      Post("/register?InstanceString=0") ~> routes ~> check {
        assert(status === StatusCodes.INTERNAL_SERVER_ERROR)
        responseAs[String] should === ("An internal server error occurred.")

      }
    }
    "successfully Deregister" in {
      Post("/deregister?Id=0") ~> routes ~> check {
        assert(status === StatusCodes.OK)
        entityAs[String] should === ("Successfully removed instance with id 0")
      }
    }
    "return validation exception: Method not allowed" in {
      Get("/deregister?Id=0") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
      }
    }
    "return could not deregister instance when wrong parameter is passed" in {
      Post("/deregister?Id=kilo") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.BAD_REQUEST)

      }
    }
    "return instance not found if instance ID doesnot exists" in {
      Post("/deregister?Id=30") ~> routes ~> check {
        assert(status === StatusCodes.NOT_FOUND)
        responseAs[String] shouldEqual("Id 30 not known to the server")
      }
    }
    "throw validation exception: Method not allowed while fetching number of instances" in {
      Post("/numberOfInstances?ComponentType=Crawler") ~> Route.seal(routes) ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
      }
    }
    "return Error 400 Bad Request" in {
      Get("/numberOfInstances?ComponentType=Crawlerr") ~> routes ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
      }
    }
    "should display number of instances of specific Component Type" in {
      Get("/numberOfInstances?ComponentType=ElasticSearch") ~> routes ~> check {
        assert(status === StatusCodes.OK)
        //responseAs[String] shouldEqual("0")
      }
    }
    /* Always returns 200 ok even when no instance of a component is running
    "Return Instances not found if no instance of specific type is present" in {
      Get("/numberOfInstances?ComponentType=WebApi") ~> routes ~> check {
        assert(status === StatusCodes.NOT_FOUND)

      }
    }*/

  "return POST method not allowed while matching instance" in {
    Post("/matchingInstance?Id=0&ComponentType=ElasticSearch") ~> Route.seal(routes) ~> check {
      assert(status === StatusCodes.METHOD_NOT_ALLOWED)
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
    }
  }
    /* Throwing Error 404 even with the right commands (as specified in server.scala).
     Tested with Elastic search as it is always running. Also with other components after running them.

    "Return Matching instance of specific type" in {
      Get("/matchingInstance?Id=1&ComponentType=Crawler") ~> routes ~> check {
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
  }
}