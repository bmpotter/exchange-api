/** Services routes for all of the /orgs api methods. */
package com.horizon.exchangeapi

/*
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
//import org.scalatra._
//import org.scalatra.swagger._
import org.slf4j._

import scala.collection.immutable._
import scala.collection.mutable.{HashMap => MutableHashMap}
import scala.util._
//import java.net._
*/

//import javax.ws.rs.{GET, POST, Path}
import javax.ws.rs.{ GET, Path }
import akka.actor.ActorSystem
import akka.event.{ Logging, LoggingAdapter }
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.horizon.exchangeapi.auth.AuthException
//import akka.http.scaladsl.server.ValidationRejection
//import com.horizon.exchangeapi.auth._
import io.swagger.v3.oas.annotations.enums.ParameterIn
//import akka.http.scaladsl.server.directives.MethodDirectives.delete
import akka.http.scaladsl.server.directives.MethodDirectives.get
//import akka.http.scaladsl.server.directives.MethodDirectives.post
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.http.scaladsl.server.directives.PathDirectives.path

//import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{ Content, Schema }
//import io.swagger.v3.oas.annotations.responses.ApiResponse
//import io.swagger.v3.oas.annotations.{ Operation, Parameter }
import io.swagger.v3.oas.annotations._

import scala.collection.immutable._
import scala.util._
//import scala.concurrent.Future

/* when using actors
import akka.actor.{ ActorRef, ActorSystem }
import scala.concurrent.duration._
import com.horizon.exchangeapi.OrgsActor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.ExecutionContext
*/

import com.horizon.exchangeapi.tables._
import com.horizon.exchangeapi.tables.ExchangePostgresProfile.api._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.DefaultJsonProtocol
//import spray.json._

// Note: These are the input and output structures for /orgs routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs */
//final case class TmpOrg(orgType: String, label: String, description: String, lastUpdated: String)
//final case class TmpOrgs(orgs: Seq[TmpOrg])

final case class GetOrgsResponse(orgs: Map[String, Org], lastIndex: Int)
//def toTmpOrgs = TmpOrgs(orgs.values.map(v => TmpOrg(v.orgType, v.label, v.description, v.lastUpdated)).toSeq)
//final case class GetOrgAttributeResponse(attribute: String, value: String)

/** Routes for /orgs */
@Path("/orgs")
class OrgsRoutes(implicit val system: ActorSystem) extends SprayJsonSupport with AuthenticationSupport {
  // Tell spray how to marshal our types (models) to/from the rest client
  // old way: protected implicit def jsonFormats: Formats
  import DefaultJsonProtocol._
  // Note: it is important to use the immutable version of collections like Map
  // Note: if you accidentally omit a class here, you may get a msg like: [error] /Users/bp/src/github.com/open-horizon/exchange-api/src/main/scala/com/horizon/exchangeapi/OrgsRoutes.scala:49:44: could not find implicit value for evidence parameter of type spray.json.DefaultJsonProtocol.JF[scala.collection.immutable.Seq[com.horizon.exchangeapi.TmpOrg]]
  implicit val orgJsonFormat = jsonFormat5(Org)
  implicit val orgsJsonFormat = jsonFormat2(GetOrgsResponse)
  //implicit val actionPerformedJsonFormat = jsonFormat1(ActionPerformed)

  def db: Database = ExchangeApiApp.getDb
  lazy implicit val logger: LoggingAdapter = Logging(system, classOf[OrgsRoutes])

  /* when using actors
  implicit def system: ActorSystem
  implicit val executionContext: ExecutionContext = context.system.dispatcher
  val orgsActor: ActorRef = system.actorOf(OrgsActor.props, "orgsActor") // I think this will end up instantiating OrgsActor via the creator function that is part of props
  logger.debug("OrgsActor created")
  // Required by the `ask` (?) method below
  implicit lazy val timeout = Timeout(5.seconds) //note: get this from the system's configuration
  */

  // Note: to make swagger work, each route should be returned by its own method: https://github.com/swagger-akka-http/swagger-akka-http
  def routes: Route = orgsGetRoute

  // ====== GET /orgs ================================

  /* Akka-http Directives Notes:
  * Directives reference: https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/alphabetically.html
  * Get variable parts of the route: path("orgs" / String) { orgid=>
  * Get the request context: get { ctx => println(ctx.request.method.name)
  * Get the request: extractRequest { request => println(request.headers.toString())
  * Concatenate directive extractions: (path("order" / IntNumber) & get & extractMethod) { (id, m) =>
  * For url query parameters, the single quote in scala means it is a symbol, the question mark means it's optional */

  @GET
  @Operation(summary = "Returns all orgs", description = """Returns some or all org definitions in the exchange DB. Can be run by any user if filter orgType=IBM is used, otherwise can only be run by the root user.""",
    method = "GET",
    parameters = Array(
      new Parameter(name = "orgtype", in = ParameterIn.QUERY, required = false, description = "Filter results to only include orgs with this org type. A common org type is 'IBM'.",
        content = Array(new Content(schema = new Schema(implementation = classOf[String], allowableValues = Array("IBM"))))),
      new Parameter(name = "label", in = ParameterIn.QUERY, required = false, description = "Filter results to only include orgs with this label (can include % for wildcard - the URL encoding for % is %25)",
        content = Array(new Content(schema = new Schema(implementation = classOf[String]))))),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "GET /orgs response",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetOrgsResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgsGetRoute: Route = (get & path("orgs") & extractCredentials & parameter(('orgtype.?, 'label.?))) { (creds, orgType, label) =>
    // Note: can't use directive authenticateBasic because it only returns a String and we need to return IIdentity. See: https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/security-directives/authenticateBasic.html
    logger.debug(s"Doing GET /orgs with creds:$creds, orgType:$orgType, label:$label")
    authenticate(creds) match {
      case Failure(authException: AuthException) => reject(AuthRejection(authException))
      case Failure(t) => failWith(t)
      case Success(authenticatedIdentity) =>
        validate(orgType.isEmpty || orgType.get == "IBM", ExchangeMessage.translateMessage("org.get.orgtype")) {
          complete({ // this is an anonymous function that returns Future[(StatusCode, GetOrgsResponse)]
            logger.debug("GET /orgs identity: " + authenticatedIdentity.identity)
            /*
            // If filter is orgType=IBM then it is a different access required than reading all orgs
            val access = if (params.get("orgtype").contains("IBM")) Access.READ_IBM_ORGS else Access.READ    // read all orgs
            authenticate().authorizeTo(TOrg("*"),access)
            */
            var q = OrgsTQ.rows.subquery
            // If multiple filters are specified they are ANDed together by adding the next filter to the previous filter by using q.filter
            orgType match {
              case Some(oType) => if (oType.contains("%")) q = q.filter(_.orgType like oType) else q = q.filter(_.orgType === oType);
              case _ => ;
            }
            label match {
              case Some(lab) => if (lab.contains("%")) q = q.filter(_.label like lab) else q = q.filter(_.label === lab);
              case _ => ;
            }

            db.run(q.result).map({ list =>
              logger.debug("GET /orgs result size: " + list.size)
              val orgs = list.map(a => a.orgId -> a.toOrg).toMap
              val code = if (orgs.nonEmpty) StatusCodes.OK else StatusCodes.NotFound

              (code, GetOrgsResponse(orgs, 0))
            })
          })
        }
    }
  }

  /*
get("/orgs", operation(getOrgs)) ({
  // If filter is orgType=IBM then it is a different access required than reading all orgs
  val access = if (params.get("orgtype").contains("IBM")) Access.READ_IBM_ORGS else Access.READ    // read all orgs

  authenticate().authorizeTo(TOrg("*"),access)
  val resp = response
  var q = OrgsTQ.rows.subquery
  // If multiple filters are specified they are ANDed together by adding the next filter to the previous filter by using q.filter
  params.get("orgtype").foreach(orgType => { if (orgType.contains("%")) q = q.filter(_.orgType like orgType) else q = q.filter(_.orgType === orgType) })
  params.get("label").foreach(label => { if (label.contains("%")) q = q.filter(_.label like label) else q = q.filter(_.label === label) })

  db.run(q.result).map({ list =>
    logger.debug("GET /orgs result size: "+list.size)
    val orgs = new MutableHashMap[String,Org]
    if (list.nonEmpty) for (a <- list) orgs.put(a.orgId, a.toOrg)
    if (orgs.nonEmpty) resp.setStatus(HttpCode.OK)
    else resp.setStatus(HttpCode.NOT_FOUND)
    GetOrgsResponse(orgs.toMap, 0)
  })
})
*/

  /*
def renderAttribute(attribute: scala.Seq[Any]): String = {
  attribute.head match {
    case attr: JValue => write(attr)
    case attr => attr.toString
  }
}

// ====== GET /orgs/{orgid} ================================
val getOneOrg =
  (apiOperation[GetOrgsResponse]("getOneOrg")
    summary("Returns a org")
    description("""Returns the org with the specified id in the exchange DB. Can be run by any user in this org.""")
    parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire org resource will be returned."), paramType=ParamType.Query, required=false)
      )
    responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
    )

get("/orgs/:orgid", operation(getOneOrg)) ({
  val orgId = params("orgid")
  authenticate().authorizeTo(TOrg(orgId),Access.READ)
  val resp = response
  params.get("attribute") match {
    case Some(attribute) => ; // Only returning 1 attr of the org
      val q = OrgsTQ.getAttribute(orgId, attribute)       // get the proper db query for this attribute
      if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("org.attr.not.part.of.org")))
      db.run(q.result).map({ list =>
        //logger.trace("GET /orgs/"+orgId+" attribute result: "+list.toString)
        if (list.nonEmpty) {
          resp.setStatus(HttpCode.OK)
          GetOrgAttributeResponse(attribute, renderAttribute(list))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("not.found"))
        }
      })

    case None => ;  // Return the whole org resource
      db.run(OrgsTQ.getOrgid(orgId).result).map({ list =>
        logger.debug("GET /orgs/"+orgId+" result: "+list.toString)
        val orgs = new MutableHashMap[String,Org]
        if (list.nonEmpty) for (a <- list) orgs.put(a.orgId, a.toOrg)
        if (orgs.nonEmpty) resp.setStatus(HttpCode.OK)
        else resp.setStatus(HttpCode.NOT_FOUND)
        GetOrgsResponse(orgs.toMap, 0)
      })
  }
})

// =========== POST /orgs/{orgid} ===============================
val postOrgs =
  (apiOperation[ApiResponse]("postOrgs")
    summary "Adds a org"
    description
      """Creates an org resource. This can only be called by the root user. The **request body** structure:

```
{
"orgType": "my org type",
"label": "My org",
"description": "blah blah",
"tags": { "ibmcloud_id": "abc123def456" }
}
```""".stripMargin
    parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostPutOrgRequest],
        Option[String]("Org object that needs to be updated in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
    responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
    )
val postOrgs2 = (apiOperation[PostPutOrgRequest]("postOrgs2") summary("a") description("a"))  // for some bizarre reason, the PostOrgRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

post("/orgs/:orgid", operation(postOrgs)) ({
  val orgId = params("orgid")
  authenticate().authorizeTo(TOrg(""),Access.CREATE)
  val orgReq = try { parse(request.body).extract[PostPutOrgRequest] }
  catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }
  orgReq.validate()
  val resp = response
  db.run(orgReq.toOrgRow(orgId).insert.asTry).map({ xs =>
    logger.debug("POST /orgs result: "+xs.toString)
    xs match {
      case Success(_) => resp.setStatus(HttpCode.POST_OK)
        ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("org.created", orgId))
      case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
        resp.setStatus(HttpCode.ACCESS_DENIED)
        ApiResponse(ApiResponseType.ACCESS_DENIED, ExchangeMessage.translateMessage("org.not.created", orgId, t.getMessage))
      } else if (t.getMessage.contains("duplicate key value violates unique constraint")) {
        resp.setStatus(HttpCode.ALREADY_EXISTS)
        ApiResponse(ApiResponseType.ALREADY_EXISTS, ExchangeMessage.translateMessage("org.already.exists", orgId, t.getMessage))
      } else {
        resp.setStatus(HttpCode.INTERNAL_ERROR)
        ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("org.not.created", orgId, t.toString))
      }
    }
  })
})

// =========== PUT /orgs/{orgid} ===============================
val putOrgs =
  (apiOperation[ApiResponse]("putOrgs")
    summary "Updates a org"
    description """Does a full replace of an existing org. This can only be called by root or a user in the org with the admin role."""
    parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostPutOrgRequest],
        Option[String]("Org object that needs to be updated in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
    responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
    )
val putOrgs2 = (apiOperation[PostPutOrgRequest]("putOrgs2") summary("a") description("a"))  // for some bizarre reason, the PutOrgRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

put("/orgs/:orgid", operation(putOrgs)) ({
  val orgId = params("orgid")
  val orgReq = try { parse(request.body).extract[PostPutOrgRequest] }
  catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }
  orgReq.validate()
  val access = if (orgReq.orgType.getOrElse("") == "IBM") Access.SET_IBM_ORG_TYPE else Access.WRITE
  authenticate().authorizeTo(TOrg(orgId),access)
  val resp = response
  db.run(orgReq.toOrgRow(orgId).update.asTry).map({ xs =>
    logger.debug("PUT /orgs/"+orgId+" result: "+xs.toString)
    xs match {
      case Success(n) => try {
          val numUpdated = n.toString.toInt     // i think n is an AnyRef so we have to do this to get it to an int
          if (numUpdated > 0) {
            resp.setStatus(HttpCode.PUT_OK)
            ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("org.updated"))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("org.not.found", orgId))
          }
        } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("org.not.updated", orgId, e)) }    // the specific exception is NumberFormatException
      case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
        ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("org.not.updated", orgId, t.toString))
    }
  })
})

// =========== PATCH /orgs/{org} ===============================
val patchOrgs =
  (apiOperation[Map[String,String]]("patchOrgs")
    summary "Updates 1 attribute of a org"
    description """Updates one attribute of a org in the exchange DB. This can only be called by root or a user in the org with the admin role."""
    parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PatchOrgRequest],
        Option[String]("Partial org object that contains an attribute to be updated in this org. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
      )
    responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
    )
val patchOrgs2 = (apiOperation[PatchOrgRequest]("patchOrgs2") summary("a") description("a"))  // for some bizarre reason, the PatchOrgRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

patch("/orgs/:orgid", operation(patchOrgs)) ({
  val orgId = params("orgid")
  if(!request.body.trim.startsWith("{") && !request.body.trim.endsWith("}")){
    halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("invalid.input.message", request.body)))
  }
  val orgReq = try { parse(request.body).extract[PatchOrgRequest] }
  catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
  val access = if (orgReq.orgType.getOrElse("") == "IBM") Access.SET_IBM_ORG_TYPE else Access.WRITE
  authenticate().authorizeTo(TOrg(orgId),access)
  //logger.trace("PATCH /orgs/"+orgId+" input: "+orgReq.toString)
  val resp = response
  val (action, attrName) = orgReq.getDbUpdate(orgId)
  if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("no.valid.org.attr.specified")))
  db.run(action.transactionally.asTry).map({ xs =>
    logger.debug("PATCH /orgs/"+orgId+" result: "+xs.toString)
    xs match {
      case Success(v) => try {
          val numUpdated = v.toString.toInt     // v comes to us as type Any
          if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
            resp.setStatus(HttpCode.PUT_OK)
            ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("org.attr.updated", attrName, orgId))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("org.not.found", orgId))
          }
        } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("unexpected.result.from.update", e)) }
      case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
        ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("org.not.updated", orgId, t.toString))
    }
  })
})

// =========== DELETE /orgs/{org} ===============================
val deleteOrgs =
  (apiOperation[ApiResponse]("deleteOrgs")
    summary "Deletes a org"
    description "Deletes a org from the exchange DB. This can only be called by root or a user in the org with the admin role."
    parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
    responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
    )

delete("/orgs/:orgid", operation(deleteOrgs)) ({
  val orgId = params("orgid")
  authenticate().authorizeTo(TOrg(orgId),Access.WRITE)
  // remove does *not* throw an exception if the key does not exist
  val resp = response
  db.run(OrgsTQ.getOrgid(orgId).delete.transactionally.asTry).map({ xs =>
    logger.debug("DELETE /orgs/"+orgId+" result: "+xs.toString)
    xs match {
      case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("org.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("org.not.found", orgId))
        }
      case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
        ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("org.not.deleted", orgId, t.toString))
    }
  })
})
*/

}