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
import org.scalatest.{Matchers, WordSpec}

class ServerTests extends WordSpec with Matchers with ScalatestRouteTest {
  "The Server" should {
    "this return method not allowed while registering" in {
      Post("register") -> server.Route -> check {
        status === StatusCodes.METHOD_NOT_ALLOWED
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
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
        status === StatusCodes.OK
      }
    }
    "Could not Deregister Instance" in {
      Post("deregister") -> server.Route -> check {
        status === StatusCodes.BAD_REQUEST
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
      }
    }
  "This method not allowed while matching instance" in {
    Get("matchingInstance") -> server.Route -> check {
      status === StatusCodes.METHOD_NOT_ALLOWED
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
    }
  }
  "Could not find matching instance" in {
    Get("matchingInstance") -> server.Route -> check {
      status === StatusCodes.NOT_FOUND
    }
  }
  "Failed to deserialize while matching instance" in {
    Get("matchingInstance") -> server.Route -> check {
      status === StatusCodes.BAD_REQUEST
    }
  }
  "This Method not allowed while fetching instance" in {
    Get("instances") -> server.Route -> check {
      status === StatusCodes.METHOD_NOT_ALLOWED
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
    }
  }
  "Failed to deserialize while fetching instance" in {
    Get("instances") -> server.Route -> check {
      status === StatusCodes.BAD_REQUEST
    }
  }
  "Method not allowed in matching result" in {
    Post("matchingResult") -> server.Route -> check {
      status === StatusCodes.METHOD_NOT_ALLOWED
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
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
  }
}
"Unable to deserialise parameter string while deploying container" in {
  Post("deploy") -> server.Route -> check {
    status === StatusCodes.BAD_REQUEST
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
  }
}
"Failed to report start, instance not running in Docker container" in {
  Post("reportStart") -> server.Route -> check {
    status === StatusCodes.BAD_REQUEST
  }
}
"Internal Server Error while reporting start" in {
  Post("reportStart") -> server.Route -> check {
    status === StatusCodes.INTERNAL_SERVER_ERROR
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
  }
}
"Failed report śtart, Instance not running in a Docker container" in {
  Post("reportStart") -> server.Route -> check {
    status === StatusCodes.BAD_REQUEST
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
