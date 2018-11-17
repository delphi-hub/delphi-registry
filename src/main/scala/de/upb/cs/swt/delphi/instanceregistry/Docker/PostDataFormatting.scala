package de.upb.cs.swt.delphi.instanceregistry.Docker

import akka.util.ByteString

object PostDataFormatting {

  def commandJsonRequest(cmd: String,
                         attachStdin: Option[Boolean],
                         attachStdout: Option[Boolean],
                         attachStderr: Option[Boolean],
                         detachKeys: Option[String],
                         privileged: Option[Boolean],
                         tty: Option[Boolean],
                         user: Option[String]
                        ): ByteString ={
    var data = s""""Cmd":["$cmd"]"""
    if(attachStdin.isDefined) data = data.concat(s""","AttachStdin":${attachStdin.get}""")
    if(attachStdout.isDefined) data = data.concat(s""","AttachStdout":${attachStdout.get}""")
    if(attachStderr.isDefined) data = data.concat(s""","AttachStderr":${attachStderr.get}""")
    if(detachKeys.isDefined) data = data.concat(s""","DetachKeys":${detachKeys.get}""")
    if(privileged.isDefined) data = data.concat(s""","Privileged":${privileged.get}""")
    if(tty.isDefined)data = data.concat(s""","Tty":${tty.get}""")
    if(user.isDefined) data = data.concat(s""","User":${user.get}""")

    ByteString(
      s"""
         |{
         |  $data
         |}
        """.stripMargin)
  }

}
