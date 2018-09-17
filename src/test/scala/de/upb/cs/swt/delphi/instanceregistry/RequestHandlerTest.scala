package de.upb.cs.swt.delphi.instanceregistry

import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

class RequestHandlerTest extends FlatSpec with Matchers with BeforeAndAfterEach{

  val handler : RequestHandler = new RequestHandler(new Configuration())

  override protected def beforeEach(): Unit = {

  }

  override protected def afterEach(): Unit = {

  }

}
