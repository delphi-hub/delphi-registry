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

package de.upb.cs.swt.delphi.webapi

import akka.http.javadsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.testkit.ScalatestRouteTest
import de.upb.cs.swt.delphi.instanceregistry.Docker.DockerConnection
import de.upb.cs.swt.delphi.instanceregistry.connection.Server.{complete, handler}
import de.upb.cs.swt.delphi.instanceregistry.{Configuration, RequestHandler}
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.Instance
import de.upb.cs.swt.delphi.instanceregistry.io.swagger.client.model.InstanceEnums.{ComponentType, InstanceState}
import org.scalatest.{Matchers, WordSpec}

class ServerTests extends WordSpec with Matchers with ScalatestRouteTest {
  val handler: RequestHandler = new RequestHandler(new Configuration(), DockerConnection.fromEnvironment())
  private def buildInstance(id: Long, dockerId: Option[String] = None, state: InstanceState.Value = InstanceState.Stopped): Instance = {
    Instance(Some(id), "https://localhost", 12345, "TestInstance", ComponentType.ElasticSearch, dockerId, state)
  }
  "The Server" should {
    "this return method not allowed while registering" in {
      Post("register") -> server.Route -> check {
        status === StatusCodes.METHOD_NOT_ALLOWED
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
      }
    }
    "Successfully Registered" in {
      Post("register") -> server.Route -> check {
        assert(status === StatusCodes.OK)
        assert(response.status === 200)
        val registerNewInstance = handler.handleRegister(buildInstance(Long.MaxValue))
        assert(registerNewInstance.isSuccess)
      }
    }
    "Internal Server Error occured while registering" in {
      Post("register") -> server.Route -> check {
        status === StatusCodes.INTERNAL_SERVER_ERROR
        responseAs[String] shouldEqual "An internal server error occurred."
      }
    }
    "Could not deserialize parameter instance while registering instance" in {
      Post("register") -> server.Route -> check {
        status === StatusCodes.BAD_REQUEST
        responseAs[String] should contain("Could not deserialize parameter instance")
      }
    }
    "This Method not allowed in order to deregister instance" in {
      Post("deregister") -> server.Route -> check {
        status === StatusCodes.METHOD_NOT_ALLOWED
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
      }
    }
    "Successfully Deregistered" in {
      Post("deregister") -> server.Route -> check {
        assert(status === StatusCodes.OK)
        assert(response.status === 200)
        val registerInstance = handler.handleRegister(buildInstance(1, None))
        assert(registerInstance.isSuccess)
      }
    }
    "Could not Deregister Instance" in {
      Post("deregister") -> server.Route -> check {
        status === StatusCodes.BAD_REQUEST
        responseAs[String] should contain("Cannot remove instance")
      }
    }
    "This Method not allowed while fetching number of instances" in {
      Get("numberOfInstances") -> server.Route -> check {
        status === StatusCodes.METHOD_NOT_ALLOWED
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
      }
    }
    "Could not deserialize parameter string while fetching number of Instances" in {
      Get("numberOfInstances") -> server.Route -> check {
        status === StatusCodes.BAD_REQUEST
        responseAs[String] should contain("Could not deserialize parameter string")
      }
    }
    "Should display number of instances of specific componentType" in {
      Get("numberOfInstances") -> server.Route -> check {
        assert(status === StatusCodes.OK)
        assert(response.status === 200)
        handler.initialize()
        assert(handler.getNumberOfInstances(ComponentType.ElasticSearch) == 1)
      }
    }
  "This method not allowed while matching instance" in {
    Get("matchingInstance") -> server.Route -> check {
      status === StatusCodes.METHOD_NOT_ALLOWED
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
    }
  }
    "Return Matching instance of specific type" in {
    Get("matchingInstance") -> server.Route -> check {
      assert(status === StatusCodes.OK)
      assert(response.status === 200)
      handler.initialize()
      val matchingInstance = handler.getMatchingInstanceOfType(ComponentType.ElasticSearch)
      assert(matchingInstance.isSuccess)
    }
  }

  "Could not find matching instance" in {
    Get("matchingInstance") -> server.Route -> check {
      status === StatusCodes.NOT_FOUND
      responseAs[String] should contain("Could not find instance")
    }
  }
  "Invalid dependency type" in {
    Get("matchingInstance") -> server.Route -> check {
      status === StatusCodes.BAD_REQUEST
      responseAs[String] should contain("Invalid dependency type")
    }
  }
  "This Method not allowed while fetching instance" in {
    Get("instances") -> server.Route -> check {
      status === StatusCodes.METHOD_NOT_ALLOWED
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
    }
  }
    " Displax All Matching Instance" in {
      Get("instances") -> server.Route -> check {
        assert(status === StatusCodes.OK)
        assert(response.status === 200)
      }
    }
  "Failed to deserialize while fetching instance" in {
    Get("instances") -> server.Route -> check {
      status === StatusCodes.BAD_REQUEST
      responseAs[String] should contain("Could not deserialize parameter string")
    }
  }
  "Method not allowed in matching result" in {
    Post("matchingResult") -> server.Route -> check {
      status === StatusCodes.METHOD_NOT_ALLOWED
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
    }
  }
    "Successfully Matched Result" in {
      Post("matchingResult") -> server.Route -> check {
        assert(status === StatusCodes.OK)
        assert(response.status === 200)
        handler.initialize()
        assert(handler.handleMatchingResult(0, result = true) == handler.OperationResult.Ok)
      }
    }
  "Method not allowed in eventlist" in {
    Get("eventList") -> server.Route -> check {
      status === StatusCodes.METHOD_NOT_ALLOWED
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
    }
  }
  "Instance ID not found in event list" in {
    Get("eventList") -> server.Route -> check {
      status === StatusCodes.NOT_FOUND
      responseAs[String] should contain("ID not found")
    }
  }
"Successfully Deployed Container" in {
  Post("deploy") -> server.Route -> check {
    status === StatusCodes.ACCEPTED
  }
}
"Internal Server Error while deploying container" in {
  Post("deploy") -> server.Route -> check {
    status === StatusCodes.INTERNAL_SERVER_ERROR
    responseAs[String] should contain("Internal server error")
  }
}
"Unable to deserialise parameter string while deploying container" in {
  Post("deploy") -> server.Route -> check {
    status === StatusCodes.BAD_REQUEST
    responseAs[String] should contain("Could not deserialize parameter string")
  }
}
"This Method not allowed to deploy container" in {
  Post("deploy") -> server.Route -> check {
    status === StatusCodes.METHOD_NOT_ALLOWED
    responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
  }
}

"Method not allowed while report start" in {
  Post("reportStart") -> server.Route -> check {
    status === StatusCodes.METHOD_NOT_ALLOWED
    responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
  }
}
"failed to report start, instance ID not found" in {
  Post("reportStart") -> server.Route -> check {
    status === StatusCodes.NOT_FOUND
    responseAs[String] should contain("not found")
  }
}
"Failed to report start, instance not running in Docker container" in {
  Post("reportStart") -> server.Route -> check {
    status === StatusCodes.BAD_REQUEST
    responseAs[String] should contain("not running in a docker container")
  }
}
"Internal Server Error while reporting start" in {
  Post("reportStart") -> server.Route -> check {
    status === StatusCodes.INTERNAL_SERVER_ERROR
    responseAs[String] should contain("Internal server error, unknown operation result")
  }
}
"Failed to report stop, Method not allowed" in {
  Post("reportStop") -> server.Route -> check {
    status === StatusCodes.METHOD_NOT_ALLOWED
    responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
  }
}
"ID not found, failed to report stop" in {
  Post("reportStop") -> server.Route -> check {
    status === StatusCodes.NOT_FOUND
    responseAs[String] should contain("not found")
  }
}
"Failed report śtop, Instance not running in a Docker container" in {
  Post("reportStop") -> server.Route -> check {
    status === StatusCodes.BAD_REQUEST
    responseAs[String] should contain("not running in a docker container")
  }
}
"Internal Server Error while reporting stop command" in {
  Post("reportStop") -> server.Route -> check {
    status === StatusCodes.INTERNAL_SERVER_ERROR
  }
}
"Failed to report failure, Method not allowed" in {
  Post("reportFailure") -> server.Route -> check {
    status === StatusCodes.METHOD_NOT_ALLOWED
    responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
  }
}
"Failed to report failure, instance ID not found" in {
  Post("reportFailure") -> server.Route -> check {
    status === StatusCodes.NOT_FOUND
  }
}
"Failed to report failure, instance not running in a Docker container" in {
  Post("reportFailure") -> server.Route -> check {
    status === StatusCodes.BAD_REQUEST
  }
}

"Internal Server Error while reporting failure" in {
  Post("reportFailure") -> server.Route -> check {
    status === StatusCodes.INTERNAL_SERVER_ERROR
  }
}
"Failed to pause, Method not allowed" in {
  Post("pause") -> server.Route -> check {
    status === StatusCodes.METHOD_NOT_ALLOWED
    responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
  }
}
"Failed to pause, Instance ID not found" in {
  Post("pause") -> server.Route -> check {
    status === StatusCodes.NOT_FOUND
  }
}
"Failed to pause instance,Not running in a Docker container" in {
  Post("pause") -> server.Route -> check {
    status === StatusCodes.BAD_REQUEST
  }
}
"Operation Accepted, Instance paused" in {
  Post("pause") -> server.Route -> check {
    status === StatusCodes.ACCEPTED
  }
}
"Internal Server Error while pausing instance" in {
  Post("pause") -> server.Route -> check {
    status === StatusCodes.INTERNAL_SERVER_ERROR
  }
}
"Method not allowed, Failed to resume" in {
  Post("resume") -> server.Route -> check {
    status === StatusCodes.METHOD_NOT_ALLOWED
    responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
  }
}
"Failed to resume, ID not found" in {
  Post("resume") -> server.Route -> check {
    status === StatusCodes.NOT_FOUND
  }
}
"Failed to resume instance, Not running in a Docker container" in {
  Post("resume") -> server.Route -> check {
    status === StatusCodes.BAD_REQUEST
  }
}
"Operation Accepted, Instance resumed" in {
  Post("resume") -> server.Route -> check {
    status === StatusCodes.ACCEPTED
  }
}
"Internal Server Error while resuming instance" in {
  Post("resume") -> server.Route -> check {
    status === StatusCodes.INTERNAL_SERVER_ERROR
  }
}

"Failed to stop, Method not allowed" in {
  Post("stop") -> server.Route -> check {
    status === StatusCodes.METHOD_NOT_ALLOWED
    responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
  }
}
"Failed to Stop, ID not found" in {
  Post("stop") -> server.Route -> check {
    status === StatusCodes.NOT_FOUND
  }
}
"Failed to stop, not running in a Docker container" in {
  Post("stop") -> server.Route -> check {
    status === StatusCodes.BAD_REQUEST
  }
}
"Operation Accepted and Docker container stopped" in {
  Post("stop") -> server.Route -> check {
    status === StatusCodes.ACCEPTED
  }
}
"Internal Server Error while stopping docker container" in {
  Post("stop") -> server.Route -> check {
    status === StatusCodes.INTERNAL_SERVER_ERROR
  }
}
"Method not allowed in Start Docker container" in {
  Post("start") -> server.Route -> check {
    status === StatusCodes.METHOD_NOT_ALLOWED
    responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
  }
}
"Failed to Start, ID not found" in {
  Post("start") -> server.Route -> check {
    status === StatusCodes.NOT_FOUND
  }
}
"Cannot Start, Not running in a Docker container" in {
  Post("start") -> server.Route -> check {
    status === StatusCodes.BAD_REQUEST
  }
}
"Operation Accepted in Docker Container" in {
  Post("start") -> server.Route -> check {
    status === StatusCodes.ACCEPTED
  }
}
"Internal Server Error while starting Docker" in {
  Post("start") -> server.Route -> check {
    status === StatusCodes.INTERNAL_SERVER_ERROR
  }
}
"Method not allowed in Delete Container" in {
  Post("delete") -> server.Route -> check {
    status === StatusCodes.METHOD_NOT_ALLOWED
    responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
  }
}
"Failed to Delete, ID not found" in {
  Post("delete") -> server.Route -> check {
    status === StatusCodes.NOT_FOUND
  }
}
"Can not delete, Instance not running in a Docker container" in {
  Post("delete") -> server.Route -> check {
    status === StatusCodes.BAD_REQUEST
  }
}
"Operation Accepted and Container Deleted" in {
  Post("delete") -> server.Route -> check {
    status === StatusCodes.ACCEPTED
  }
}
"Internal Server Error while deleting container" in {
  Post("delete") -> server.Route -> check {
    status === StatusCodes.INTERNAL_SERVER_ERROR
  }
}
  }
}
