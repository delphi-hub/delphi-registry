// Copyright (C) 2018 The Delphi Team.
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

// http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package de.upb.cs.swt.delphi.instanceregistry.authorization

import akka.http.scaladsl.model.DateTime
import de.upb.cs.swt.delphi.instanceregistry.authorization.AccessTokenEnums.UserType

final case class AccessToken(userId: String,
                             userType: UserType,
                             expiresAt: DateTime,
                             issuedAt: DateTime,
                             notBefore: DateTime)


object AccessTokenEnums {

  type UserType = UserType.Value

  object UserType extends Enumeration {
    val User : Value = Value("User")
    val Admin: Value = Value("Admin")
    val Component: Value = Value("Component")
  }
}

