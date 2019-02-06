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

