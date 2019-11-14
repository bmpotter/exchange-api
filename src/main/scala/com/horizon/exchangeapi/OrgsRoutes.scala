/** Services routes for all of the /orgs api methods. */
package com.horizon.exchangeapi

/*
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import com.horizon.exchangeapi.tables._
//import org.scalatra._
//import org.scalatra.swagger._
import org.slf4j._
import com.horizon.exchangeapi.tables.ExchangePostgresProfile.api._

import scala.collection.immutable._
import scala.collection.mutable.{HashMap => MutableHashMap}
import scala.util._
//import java.net._
*/

//import com.horizon.exchangeapi.tables._

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.Logging

import scala.concurrent.duration._
import akka.http.scaladsl.server.Directives._
//import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
//import akka.http.scaladsl.server.directives.MethodDirectives.delete
import akka.http.scaladsl.server.directives.MethodDirectives.get
//import akka.http.scaladsl.server.directives.MethodDirectives.post
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.http.scaladsl.server.directives.PathDirectives.path

import scala.concurrent.Future
import com.horizon.exchangeapi.OrgsActor._
import akka.pattern.ask
import akka.util.Timeout

/** Routes for /orgs */
trait OrgsRoutes extends JsonSupport {
  //def db: Database = ExchangeDatabase.db     // get access to the db object
  //def logger: Logger    // get access to the logger object in ExchangeApiApp
  //protected implicit def jsonFormats: Formats

  // we leave these abstract, since they will be provided by the App
  implicit def system: ActorSystem

  lazy val log = Logging(system, classOf[OrgsRoutes])

  def orgsActor: ActorRef // this comes from ExchangeApiApp

  // Required by the `ask` (?) method below
  implicit lazy val timeout = Timeout(5.seconds) //todo: get this from the system's configuration

  val orgsRoutes: Route = orgsGetRoute

  // ====== GET /orgs ================================
  /*
  val getOrgs =
    (apiOperation[GetOrgsResponse]("getOrgs")
      summary("Returns all orgs")
      description("""Returns some or all org definitions in the exchange DB. Can be run by any user if filter orgType=IBM is used, otherwise can only be run by the root user.""")
      parameters(
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("orgtype", DataType.String, Option[String]("Filter results to only include orgs with this org type. A common org type is 'IBM'."), paramType=ParamType.Query, required=false),
        Parameter("label", DataType.String, Option[String]("Filter results to only include orgs with this label (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED, "access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  */

  def orgsGetRoute: Route = get {
    path("orgs") {
      val orgs: Future[TmpOrgs] = (orgsActor ? GetOrgs).mapTo[TmpOrgs]
      complete(orgs)
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