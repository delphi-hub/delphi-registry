package de.upb.cs.swt.delphi.instanceregistry.authorization

//TODO: Type this class
final case class AccessToken(userId: String,
                             userType: String,
                             expiresAt: Long,
                             issuedAt: Long,
                             notBefore: Long,
                             scope: List[String])

