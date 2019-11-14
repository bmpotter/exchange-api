/** Services routes for all of the /orgs api methods. */
package com.horizon.exchangeapi

/*
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.slf4j._
import scala.util._
//import org.scalatra._
//import org.scalatra.swagger._
//import java.net._
*/

import scala.collection._
//import scala.collection.immutable._
//import scala.collection.mutable.{ HashMap => MutableHashMap }
import com.horizon.exchangeapi.tables._
import com.horizon.exchangeapi.tables.ExchangePostgresProfile.api._
import akka.actor.{ Actor, ActorLogging, Props }
//import org.json4s.{ DefaultFormats, Formats }

import scala.concurrent.ExecutionContext

//====== These are the input and output structures for /orgs routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs */
final case class TmpOrg(orgType: String, label: String, description: String, lastUpdated: String)
final case class TmpOrgs(orgs: Seq[TmpOrg])

final case class GetOrgsResponse(orgs: Map[String, Org], lastIndex: Int) {
  //def toTmpOrgs = TmpOrgs(orgs.values.map(v => TmpOrg(v.orgType, v.label, v.description, v.lastUpdated)).to[collection.immutable.Seq])
  def toTmpOrgs = TmpOrgs(orgs.values.map(v => TmpOrg(v.orgType, v.label, v.description, v.lastUpdated)).toSeq)
}
final case class GetOrgAttributeResponse(attribute: String, value: String)

/*todo: restore
/** Input format for PUT /orgs/<org-id> */
final case class PostPutOrgRequest(orgType: Option[String], label: String, description: String, tags: Option[Map[String, String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate() = {}

  def toOrgRow(orgId: String) = OrgRow(orgId, orgType.getOrElse(""), label, description, ApiTime.nowUTC, tags.map(ts => ApiJsonUtil.asJValue(ts)))
}

final case class PatchOrgRequest(orgType: Option[String], label: Option[String], description: Option[String], tags: Option[Map[String, Option[String]]]) {
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
*/

/**
 * Implementation for all of the /orgs routes
 * Note: there is a lose coupling between OrgsRoutes and OrgsActor so that OrgsActor can be on a different server.
 */

object OrgsActor {
  // These case objects/classes provide a type for each route that can be matched on in the OrgsActor.receive() case stmt
  //final case class ActionPerformed(description: String)
  final case object GetOrgs
  //final case class CreateUser(user: User)
  //final case class GetUser(name: String)
  //final case class DeleteUser(name: String)

  def props: Props = Props[OrgsActor] // returns a "creator" function for OrgsActor (among other things)
}

//trait OrgRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
class OrgsActor extends Actor with ActorLogging {
  import OrgsActor._

  def db: Database = ExchangeApiApp.getDb //todo: eventually may need to be more flexible in getting this, when the actor is on a different svr

  //implicit val executionContext: ExecutionContext = context.system.dispatcher
  implicit val executionContext: ExecutionContext = context.dispatcher
  //def logger: Logger    // get access to the logger object in ExchangeApiApp
  //protected implicit def jsonFormats: Formats

  // Multiplexer for all of the route implementations for /orgs
  def receive: Receive = {
    case GetOrgs => getOrgs
  }

  def getOrgs = {
    // If filter is orgType=IBM then it is a different access required than reading all orgs
    /*
    val access = if (params.get("orgtype").contains("IBM")) Access.READ_IBM_ORGS else Access.READ    // read all orgs

    authenticate().authorizeTo(TOrg("*"),access)
    val resp = response
    var q = OrgsTQ.rows.subquery
    // If multiple filters are specified they are ANDed together by adding the next filter to the previous filter by using q.filter
    params.get("orgtype").foreach(orgType => { if (orgType.contains("%")) q = q.filter(_.orgType like orgType) else q = q.filter(_.orgType === orgType) })
    params.get("label").foreach(label => { if (label.contains("%")) q = q.filter(_.label like label) else q = q.filter(_.label === label) })
    */
    val q = OrgsTQ.rows

    db.run(q.result).map({ list =>
      //logger.debug("GET /orgs result size: "+list.size)
      val orgs = list.map(a => a.orgId -> a.toOrg).toMap
      //val orgs = new MutableHashMap[String, Org]
      //if (list.nonEmpty) for (a <- list) orgs.put(a.orgId, a.toOrg)
      //if (orgs.nonEmpty) resp.setStatus(HttpCode.OK)
      //else resp.setStatus(HttpCode.NOT_FOUND)

      sender() ! GetOrgsResponse(orgs, 0).toTmpOrgs // the ! method sends a 1-way fire-and-forget msg to the object (in this case sender() )
    })
  }

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