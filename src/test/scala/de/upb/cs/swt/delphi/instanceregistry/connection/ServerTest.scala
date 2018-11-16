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
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.server
import akka.http.scaladsl.testkit.ScalatestRouteTest
import de.upb.cs.swt.delphi.instanceregistry.Registry
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.Instance
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import org.scalatest.{Matchers, WordSpec}
import de.upb.cs.swt.delphi.instanceregistry.connection.Server.{instanceFormat, routes}

import scala.concurrent.Future

class ServerTest extends WordSpec with Matchers with ScalatestRouteTest {

  private def buildInstance(id: Long, dockerId: Option[String] = None, state: InstanceState.Value = InstanceState.Stopped): Instance = {
    Instance(Some(id), "https://localhost", 12345, "TestInstance", ComponentType.ElasticSearch, dockerId, state)
  }
  override def beforeAll(): Unit = {
    Future(Registry.main(Array[String]()))
    Thread.sleep(3000)
  }
  override def afterAll(): Unit = {
    System.exit(0)
  }
  "The Server" should {
    "this return method not allowed while registering" in {
      Post("/register") ~> routes ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
      }
    }
    "Successfully Registered" in {
      Post("/register") ~> routes ~> check {
        assert(status === StatusCodes.OK)
        assert(response.status === 200)
        contentType should ===(ContentTypes.`application/json`)
        Get("/register") ~> routes ~> check {
        }
      }
    }
    "Invalid Input" in {
      Post("/register") ~> routes ~> check {
        assert(response.status === 405)
        responseAs[String] shouldEqual("Invalid Input")
      }
    }
    "Successfully Deregistered" in {
      Post("/deregister?Id=42") ~> routes ~> check {
        assert(status === StatusCodes.OK)
        assert(response.status === 200)
        contentType should ===(ContentTypes.`application/json`)
        entityAs[String] should === ("Successfully removed instance with id 42")
      }
    }
    "Validation Exception: Method not allowed" in {
      Post("/deregister") ~> routes ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
      }
    }
    "Could not Deregister Instance" in {
      Post("/deregister") ~> routes ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
        assert(response.status === 400)
        responseAs[String] should contain("Cannot remove instance")
      }
    }
    "Instance not found" in {
      Post("/deregister") ~> routes ~> check {
        assert(response === StatusCodes.NOT_FOUND)
      }
    }
    "Validation Exception: Method not allowed while fetching number of instances" in {
      Get("/numberOfInstances") ~> routes ~> check {
        assert(status === StatusCodes.METHOD_NOT_ALLOWED)
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
      }
    }
    "Validation Exception: Instances not found" in {
      Get("/numberOfInstances") ~> routes ~> check {
        assert(status === StatusCodes.BAD_REQUEST)
      }
    }
    "Should display number of instances of specific componentType" in {
      Get("/numberOfInstances") ~> routes ~> check {
        assert(status === StatusCodes.OK)
        assert(response.status === 200)

      }
    }
  "This method not allowed while matching instance" in {
    Get("/matchingInstance") ~> routes ~> check {
      assert(status === StatusCodes.METHOD_NOT_ALLOWED)
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
    }
  }
    "Return Matching instance of specific type" in {
      Get("/matchingInstance") ~> routes ~> check {
      assert(status === StatusCodes.OK)
      assert(response.status === 200)
    }
  }

  "Could not find matching instance" in {
    Get("/matchingInstance") ~> routes ~> check {
      status === StatusCodes.NOT_FOUND
      responseAs[String] should contain("Could not find instance")
    }
  }
  "Invalid dependency type" in {
    Get("/matchingInstance") ~> routes ~> check {
      assert(status === StatusCodes.BAD_REQUEST)
      responseAs[String] should contain("Invalid dependency type")
    }
  }
  "This Method not allowed while fetching instance" in {
    Get("/instances") ~> routes ~> check {
      assert(status === StatusCodes.METHOD_NOT_ALLOWED)
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
    }
  }
    "Display All Matching Instance" in {
      Get("/instances") ~> routes ~> check {
        assert(status === StatusCodes.OK)
        assert(response.status === 200)
      }
    }
  "Failed to deserialize while fetching instance" in {
    Get("/instances") ~> routes ~> check {
      assert(status === StatusCodes.BAD_REQUEST)
      responseAs[String] should contain("Could not deserialize parameter string")
    }
  }
  "Method not allowed in matching result" in {
    Post("/matchingResult") ~> routes ~> check {
      assert(status === StatusCodes.METHOD_NOT_ALLOWED)
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
    }
  }
    "Successfully Matched Result" in {
      Post("/matchingResult") ~> routes ~> check {
        assert(status === StatusCodes.OK)
        assert(response.status === 200)

      }
    }
  "Method not allowed in eventlist" in {
    Get("/eventList") ~> routes ~> check {
      assert(status === StatusCodes.METHOD_NOT_ALLOWED)
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
    }
  }
  "Instance ID not found in event list" in {
    Get("/eventList") ~> routes ~> check {
      assert(status === StatusCodes.NOT_FOUND)
      responseAs[String] should contain("ID not found")
    }
  }
  }
}
