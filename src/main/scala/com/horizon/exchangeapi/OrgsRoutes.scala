/** Services routes for all of the /orgs api methods. */
package com.horizon.exchangeapi

import javax.ws.rs.{ GET, POST, Path }
import akka.actor.ActorSystem
import akka.event.{ Logging, LoggingAdapter }
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.MethodDirectives.get
import akka.http.scaladsl.server.directives.MethodDirectives.post
//import akka.http.scaladsl.server.directives.MethodDirectives.delete
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.http.scaladsl.server.directives.PathDirectives.path

//import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
//import spray.json.DefaultJsonProtocol
//import spray.json._

import de.heikoseeberger.akkahttpjackson._
import org.json4s._
//import org.json4s.jackson.JsonMethods._

import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{ Content, Schema }
//import io.swagger.v3.oas.annotations.responses.ApiResponse
//import io.swagger.v3.oas.annotations.{ Operation, Parameter }
import io.swagger.v3.oas.annotations._

import com.horizon.exchangeapi.tables._
import com.horizon.exchangeapi.tables.ExchangePostgresProfile.api._

import scala.collection.immutable._
import scala.util._
//import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/* when using actors
import akka.actor.{ ActorRef, ActorSystem }
import scala.concurrent.duration._
import com.horizon.exchangeapi.OrgsActor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.ExecutionContext
*/

// Note: These are the input and output structures for /orgs routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs */
final case class GetOrgsResponse(orgs: Map[String, Org], lastIndex: Int)
case class GetOrgAttributeResponse(attribute: String, value: String)

/** Input format for PUT /orgs/<org-id> */
case class PostPutOrgRequest(orgType: Option[String], label: String, description: String, tags: Option[Map[String, String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem: Option[String] = None // None mean no problems with input

  def toOrgRow(orgId: String) = OrgRow(orgId, orgType.getOrElse(""), label, description, ApiTime.nowUTC, tags.map(ts => ApiJsonUtil.asJValue(ts)))
}

case class PatchOrgRequest(orgType: Option[String], label: Option[String], description: Option[String], tags: Option[Map[String, Option[String]]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the org, and the attribute name being updated. */
  def getDbUpdate(orgId: String): (DBIO[_], String) = {
    import com.horizon.exchangeapi.tables.ExchangePostgresProfile.plainAPI._
    import scala.concurrent.ExecutionContext.Implicits.global
    val lastUpdated = ApiTime.nowUTC
    //todo: support updating more than 1 attribute
    // find the 1st attribute that was specified in the body and create a db action to update it for this org
    orgType match { case Some(ot) => return ((for { d <- OrgsTQ.rows if d.orgid === orgId } yield (d.orgid, d.orgType, d.lastUpdated)).update((orgId, ot, lastUpdated)), "orgType"); case _ => ; }
    label match { case Some(lab) => return ((for { d <- OrgsTQ.rows if d.orgid === orgId } yield (d.orgid, d.label, d.lastUpdated)).update((orgId, lab, lastUpdated)), "label"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- OrgsTQ.rows if d.orgid === orgId } yield (d.orgid, d.description, d.lastUpdated)).update((orgId, desc, lastUpdated)), "description"); case _ => ; }
    tags match {
      case Some(ts) =>
        val (deletes, updates) = ts.partition {
          case (_, v) => v.isEmpty
        }
        val dbUpdates =
          if (updates.isEmpty) Seq()
          else Seq(sqlu"update orgs set tags = coalesce(tags, '{}'::jsonb) || ${ApiJsonUtil.asJValue(updates)} where orgid = $orgId")

        val dbDeletes =
          for (tag <- deletes.keys.toSeq) yield {
            sqlu"update orgs set tags = tags - $tag where orgid = $orgId"
          }
        val allChanges = dbUpdates ++ dbDeletes
        return (DBIO.sequence(allChanges).map(counts => counts.sum), "tags")
      case _ =>
    }
    return (null, null)
  }
}

/** Routes for /orgs */
@Path("/v1/orgs")
class OrgsRoutes(implicit val system: ActorSystem) extends JacksonSupport /* SprayJsonSupport with DefaultJsonProtocol */ with AuthenticationSupport {
  // Tell spray how to marshal our types (models) to/from the rest client
  // old way: protected implicit def jsonFormats: Formats
  //import DefaultJsonProtocol._
  // Note: it is important to use the immutable version of collections like Map
  // Note: if you accidentally omit a class here, you may get a msg like: [error] /Users/bp/src/github.com/open-horizon/exchange-api/src/main/scala/com/horizon/exchangeapi/OrgsRoutes.scala:49:44: could not find implicit value for evidence parameter of type spray.json.DefaultJsonProtocol.JF[scala.collection.immutable.Seq[com.horizon.exchangeapi.TmpOrg]]
  /* implicit val apiResponseJsonFormat = jsonFormat2(ApiResponse)
  implicit val orgJsonFormat = jsonFormat5(Org)
  implicit val getOrgsResponseJsonFormat = jsonFormat2(GetOrgsResponse)
  implicit val getOrgAttributeResponseJsonFormat = jsonFormat2(GetOrgAttributeResponse)
  implicit val postPutOrgRequestJsonFormat = jsonFormat4(PostPutOrgRequest) */
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
  // Note: putting the orgs prefix here, because it might help performance by disqualifying all of these routes early
  def routes: Route = pathPrefix("orgs") { orgsGetRoute ~ orgGetRoute ~ orgPostRoute }

  // ====== GET /orgs ================================

  /* Akka-http Directives Notes:
  * Directives reference: https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/alphabetically.html
  * The path() directive gobbles up the rest of the url path (until the params at ?). So you can't have any other path directives after it (and path directives before it must be pathPrefix())
  * Get variable parts of the route: ( get & pathPrefix("orgs") & path(Segment) ) { orgid=>
  * Alternative to get variable parts of the route: path("orgs" / Segment) { orgid=>
  * Get the request context: get { ctx => println(ctx.request.method.name)
  * Get the request: extractRequest { request => println(request.headers.toString())
  * Concatenate directive extractions: (path("order" / IntNumber) & get & extractMethod) { (id, m) =>
  * For url query parameters, the single quote in scala means it is a symbol, the question mark means it's optional */

  // Swagger annotation reference: https://github.com/swagger-api/swagger-core/wiki/Swagger-2.X---Annotations
  // Note: i think these annotations can't have any comments between them and the method def
  @GET
  @Path("")
  @Operation(summary = "Returns all orgs", description = """Returns some or all org definitions in the exchange DB. Can be run by any user if filter orgType=IBM is used, otherwise can only be run by the root user.""",
    parameters = Array(
      new Parameter(name = "orgtype", in = ParameterIn.QUERY, required = false, description = "Filter results to only include orgs with this org type. A common org type is 'IBM'.",
        content = Array(new Content(schema = new Schema(implementation = classOf[String], allowableValues = Array("IBM"))))),
      new Parameter(name = "label", in = ParameterIn.QUERY, required = false, description = "Filter results to only include orgs with this label (can include % for wildcard - the URL encoding for % is %25)")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetOrgsResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgsGetRoute: Route = (get & pathEnd & parameter(('orgtype.?, 'label.?)) & extractCredentials) { (orgType, label, creds) =>
    logger.debug(s"Doing GET /orgs with creds:$creds, orgType:$orgType, label:$label")
    // If filter is orgType=IBM then it is a different access required than reading all orgs
    val access = if (orgType.getOrElse("").contains("IBM")) Access.READ_IBM_ORGS else Access.READ_OTHER_ORGS
    auth(creds, TOrg("*"), access) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(identity) =>
        validate(orgType.isEmpty || orgType.get == "IBM", ExchMsg.translate("org.get.orgtype")) {
          complete({ // this is an anonymous function that returns Future[(StatusCode, GetOrgsResponse)]
            logger.debug("GET /orgs identity: " + identity)
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
          }) // end of complete
        } // end of validate
    } // end of auth match
  }

  // ====== GET /orgs/{orgid} ================================
  @GET
  @Path("{orgid}")
  @Operation(summary = "Returns a org", description = """Returns the org with the specified id in the exchange DB. Can be run by any user in this org.""",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire org resource will be returned.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetOrgsResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgGetRoute: Route = (get & path(Segment) & parameter('attribute.?) & extractCredentials) { (orgId, attribute, creds) =>
    auth(creds, TOrg(orgId), Access.READ) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(_) =>
        //validate(orgType.isEmpty || orgType.get == "IBM", ExchMsg.translate("org.get.orgtype")) {
        complete({
          attribute match {
            case Some(attr) => // Only returning 1 attr of the org
              val q = OrgsTQ.getAttribute(orgId, attr) // get the proper db query for this attribute
              if (q == null) (StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("org.attr.not.part.of.org", attr)))
              else db.run(q.result).map({ list =>
                logger.debug("GET /orgs/" + orgId + " attribute result: " + list.toString)
                val code = if (list.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
                // Note: scala is unhappy when db.run returns 2 different possible types, so we can't return ApiResponse in the case of not found
                /* if (list.nonEmpty) */ (code, GetOrgAttributeResponse(attr, OrgsTQ.renderAttribute(list)))
                //else (StatusCodes.NotFound, ApiResponse(ApiResponseType.NOT_FOUND, ExchMsg.translate("org.not.found", orgId)))
              })

            case None => // Return the whole org resource
              db.run(OrgsTQ.getOrgid(orgId).result).map({ list =>
                logger.debug("GET /orgs result size: " + list.size)
                val orgs = list.map(a => a.orgId -> a.toOrg).toMap
                val code = if (orgs.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
                (code, GetOrgsResponse(orgs, 0))
              })
          } // attribute match
        }) // end of complete
      //} // end of validate
    } // end of auth match
  }

  // ====== POST /orgs/{orgid} ================================
  @POST
  @Path("{orgid}")
  @Operation(summary = "Adds an org", description = """Creates an org resource. This can only be called by the root user.""",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire org resource will be returned.")),
    requestBody = new RequestBody(description = "Request body to create an org:", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[GetOrgsResponse])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "created - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgPostRoute: Route = (post & path(Segment) & extractCredentials) { (orgId, creds) =>
    logger.debug(s"Doing POST /orgs/{orgid} with orgId:$orgId")
    auth(creds, TOrg(""), Access.CREATE) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(_) =>
        logger.debug(s"POST /orgs/{orgid}: here1")
        entity(as[PostPutOrgRequest]) { orgReq =>
          logger.debug(s"POST /orgs/{orgid}: here2")
          validate(orgReq.getAnyProblem.isEmpty, "Problem in request body") { //todo: create a custom validation directive so we can return the specific error msg from getAnyProblem to the client
            complete({ // this is an anonymous function that returns Future[(StatusCode, GetOrgsResponse)]
              db.run(orgReq.toOrgRow(orgId).insert.asTry).map({ xs =>
                logger.debug("POST /orgs/{orgid} result: " + xs.toString)
                xs match {
                  case Success(_) => (HttpCode.OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("org.created", orgId)))
                  case Failure(t) =>
                    if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("org.not.created", orgId, t.getMessage)))
                    else if (t.getMessage.contains("duplicate key value violates unique constraint")) (HttpCode.ALREADY_EXISTS, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("org.already.exists", orgId, t.getMessage)))
                    else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("org.not.created", orgId, t.toString)))
                }
              })
            }) // end of complete
          } // end of validate
        } // end of entity
    } // end of auth match
  }

  /*
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
  catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchMsg.translate("error.parsing.input.json", e))) }
  orgReq.validate()
  val resp = response
  db.run(orgReq.toOrgRow(orgId).insert.asTry).map({ xs =>
    logger.debug("POST /orgs result: "+xs.toString)
    xs match {
      case Success(_) => resp.setStatus(HttpCode.POST_OK)
        ApiResponse(ApiResponseType.OK, ExchMsg.translate("org.created", orgId))
      case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
        resp.setStatus(HttpCode.ACCESS_DENIED)
        ApiResponse(ApiResponseType.ACCESS_DENIED, ExchMsg.translate("org.not.created", orgId, t.getMessage))
      } else if (t.getMessage.contains("duplicate key value violates unique constraint")) {
        resp.setStatus(HttpCode.ALREADY_EXISTS)
        ApiResponse(ApiResponseType.ALREADY_EXISTS, ExchMsg.translate("org.already.exists", orgId, t.getMessage))
      } else {
        resp.setStatus(HttpCode.INTERNAL_ERROR)
        ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchMsg.translate("org.not.created", orgId, t.toString))
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
  catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchMsg.translate("error.parsing.input.json", e))) }
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
            ApiResponse(ApiResponseType.OK, ExchMsg.translate("org.updated"))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchMsg.translate("org.not.found", orgId))
          }
        } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchMsg.translate("org.not.updated", orgId, e)) }    // the specific exception is NumberFormatException
      case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
        ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchMsg.translate("org.not.updated", orgId, t.toString))
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
    halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchMsg.translate("invalid.input.message", request.body)))
  }
  val orgReq = try { parse(request.body).extract[PatchOrgRequest] }
  catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchMsg.translate("error.parsing.input.json", e))) }    // the specific exception is MappingException
  val access = if (orgReq.orgType.getOrElse("") == "IBM") Access.SET_IBM_ORG_TYPE else Access.WRITE
  authenticate().authorizeTo(TOrg(orgId),access)
  //logger.trace("PATCH /orgs/"+orgId+" input: "+orgReq.toString)
  val resp = response
  val (action, attrName) = orgReq.getDbUpdate(orgId)
  if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchMsg.translate("no.valid.org.attr.specified")))
  db.run(action.transactionally.asTry).map({ xs =>
    logger.debug("PATCH /orgs/"+orgId+" result: "+xs.toString)
    xs match {
      case Success(v) => try {
          val numUpdated = v.toString.toInt     // v comes to us as type Any
          if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
            resp.setStatus(HttpCode.PUT_OK)
            ApiResponse(ApiResponseType.OK, ExchMsg.translate("org.attr.updated", attrName, orgId))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchMsg.translate("org.not.found", orgId))
          }
        } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchMsg.translate("unexpected.result.from.update", e)) }
      case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
        ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchMsg.translate("org.not.updated", orgId, t.toString))
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
          ApiResponse(ApiResponseType.OK, ExchMsg.translate("org.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, ExchMsg.translate("org.not.found", orgId))
        }
      case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
        ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchMsg.translate("org.not.deleted", orgId, t.toString))
    }
  })
})
*/

}