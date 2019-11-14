package com.horizon.exchangeapi

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.DefaultJsonProtocol

//todo: where should this go?
trait JsonSupport extends SprayJsonSupport {
  // import the default encoders for primitive types (Int, String, Lists etc)
  import DefaultJsonProtocol._

  // Note: if you accidentally omit a class here, you may get a msg like: [error] /Users/bp/src/github.com/open-horizon/exchange-api/src/main/scala/com/horizon/exchangeapi/OrgsRoutes.scala:49:44: could not find implicit value for evidence parameter of type spray.json.DefaultJsonProtocol.JF[scala.collection.immutable.Seq[com.horizon.exchangeapi.TmpOrg]]

  implicit val orgJsonFormat = jsonFormat4(TmpOrg)
  implicit val orgsJsonFormat = jsonFormat1(TmpOrgs)
  //implicit val orgsJsonFormat = jsonFormat2(GetOrgsResponse)

  //implicit val actionPerformedJsonFormat = jsonFormat1(ActionPerformed)
}
