/**
 * Exchange API main scalatra servlet app.
 *
 *  Used https://github.com/scalatra/scalatra-website-examples/tree/master/2.4/persistence/scalatra-slick as an initial example.
 */

package com.horizon.exchangeapi

import akka.event.{ Logging, LoggingAdapter }

import scala.util.matching.Regex
//import akka.http.scaladsl.model.headers.HttpCredentials
import akka.http.scaladsl.server.RouteResult.Rejected
import akka.http.scaladsl.server.directives.{ DebuggingDirectives, LogEntry }
import com.mchange.v2.c3p0.ComboPooledDataSource
import slick.jdbc.PostgresProfile.api._
//import org.scalatra.json._
//import org.scalatra.swagger._
//import org.scalatra.CorsSupport   // allow cross-domain requests. Note: this is pulled in automatically by SwaggerSupport
//import org.slf4j.LoggerFactory

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success }

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
//import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer

import akka.http.scaladsl.server.Directives._
//import StatusCodes._

//import spray.json.DefaultJsonProtocol
//import spray.json._
import de.heikoseeberger.akkahttpjackson._
import org.json4s._
//import org.json4s.DefaultFormats
//import org.json4s.jackson.JsonMethods._
//import org.json4s.jackson.Serialization.write

//import com.typesafe.config.Optional
import com.typesafe.config._
//import java.util.Base64

object ExchangeApiConstants {
  val serviceHost = "localhost"
  val servicePort = 8080
}

/**
 * Main akka server for the Exchange REST API.
 */
//class ExchangeApiApp(val db: Database)(implicit val swagger: Swagger) extends ScalatraServlet
//with FutureSupport with NativeJsonSupport with SwaggerSupport with CorsSupport with AuthenticationSupport with NodesRoutes with AgbotsRoutes with UsersRoutes with AdminRoutes with ServiceRoutes with PatternRoutes with OrgRoutes with BusinessRoutes with CatalogRoutes {
class ExchangeApiApp {} // so far just for the Logging
object ExchangeApiApp extends App {

  /** Sets up automatic case class to JSON output serialization, required by the JValueResult trait. */
  //protected implicit val jsonFormats: Formats = DefaultFormats
  // implicit val formats = Serialization.formats(NoTypeHints)     // needed for serializing the softwareVersions map to a string (and back)
  //import DefaultJsonProtocol._
  //implicit val apiRespJsonFormat = jsonFormat2(ApiResponse)
  import JacksonSupport._
  private implicit val formats = DefaultFormats

  // Set up ActorSystem and other dependencies here
  ExchConfig.load() // get config file, normally in /etc/horizon/exchange/config.json
  val actorConfig = ConfigFactory.parseString("akka.loglevel=" + ExchConfig.getLogLevel)
  // Note: this object extends App which extends DelayedInit, so these values won't be available immediately. See https://stackoverflow.com/questions/36710169/why-are-implicit-variables-not-initialized-in-scala-when-called-from-unit-test/36710170
  implicit val system: ActorSystem = ActorSystem("actors", actorConfig)
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher

  //implicit val logger = LoggerFactory.getLogger(ExchConfig.LOGGER)
  /*lazy*/ implicit val logger: LoggingAdapter = Logging(system, classOf[ExchangeApiApp])
  //AuthCache.logger = logger
  ExchConfig.defaultLogger = logger // need this set in an object that doesn't use DelayedInit
  ExchConfig.createRootInCache()

  // Catches rejections from routes and returns the http codes we want
  implicit def myRejectionHandler =
    RejectionHandler.newBuilder()
      .handle {
        case r: ExchangeRejection =>
          complete((r.httpCode, r.toApiResp))
      }
      .handle {
        case AuthorizationFailedRejection =>
          complete((StatusCodes.Forbidden, "You're out of your depth!"))
      }
      .handle {
        case ValidationRejection(msg, _) =>
          complete((StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, msg)))
      }
      .handle {
        case MalformedRequestContentRejection(msg, _) => // this comes from the entity() directive when parsing the request body failed
          complete((StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, msg)))
      }
      // the default rejection handler will catch the rest
      //todo: not sure when these will occur
      .handleAll[MethodRejection] { methodRejections =>
        val names = methodRejections.map(_.supported.name)
        complete((StatusCodes.MethodNotAllowed, s"method not supported: ${names mkString " or "}"))
      }
      .handleNotFound { complete((StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, "not found"))) }
      .result()

  // Custom logging of requests and responses. See https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/debugging-directives/logRequestResult.html
  val basicAuthRegex = new Regex("^Basic ?(.*)$")
  def requestResponseLogging(req: HttpRequest): RouteResult => Option[LogEntry] = {
    case RouteResult.Complete(res) =>
      // First decode the auth and get the org/id
      val optionalEncodedAuth = req.getHeader("Authorization") // this is type: com.typesafe.config.Optional[akka.http.scaladsl.model.HttpHeader]
      val encodedAuth = if (optionalEncodedAuth.isPresent) optionalEncodedAuth.get().value() else ""
      val authId = encodedAuth match {
        case basicAuthRegex(basicAuthEncoded) =>
          AuthenticationSupport.parseCreds(basicAuthEncoded).map(_.id).getOrElse("<invalid-auth>")
        case _ => "<invalid-auth>"
      }
      // Now log all the info
      Some(LogEntry(s"${req.uri.authority.host.address}:$authId ${req.method.name} ${req.uri}: ${res.status}", Logging.InfoLevel))
    case Rejected(rejections) => Some(LogEntry(s"${req.method.name} ${req.uri}: rejected with ${rejections.head}", Logging.DebugLevel))
    case _ => None
  }

  // Create all of the routes and concat together
  case class testResp(result: String)
  def testRoute = { path("test") { get { logger.debug("In /test"); complete(testResp("OK")) } } }
  val orgsRoutes = (new OrgsRoutes).routes
  //val swaggerRoutes = (new SwaggerDocService).routes
  val swaggerDocRoutes = SwaggerDocService.routes
  val swaggerUiRoutes = (new SwaggerUiService).routes

  // Note: all exceptions (code failures) will be handled by the akka-http exception handler. To override that, see https://doc.akka.io/docs/akka-http/current/routing-dsl/exception-handling.html#exception-handling
  //someday: use directive https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/misc-directives/selectPreferredLanguage.html to support a different language for each client
  lazy val routes: Route = DebuggingDirectives.logRequestResult(requestResponseLogging _) { pathPrefix("v1") { testRoute ~ orgsRoutes ~ swaggerDocRoutes ~ swaggerUiRoutes } }

  // Load the db backend. The db access info must be in config.json
  var cpds: ComboPooledDataSource = _
  cpds = new ComboPooledDataSource
  cpds.setDriverClass(ExchConfig.getString("api.db.driverClass")) //loads the jdbc driver
  cpds.setJdbcUrl(ExchConfig.getString("api.db.jdbcUrl"))
  cpds.setUser(ExchConfig.getString("api.db.user"))
  cpds.setPassword(ExchConfig.getString("api.db.password"))
  // the settings below are optional -- c3p0 can work with defaults
  cpds.setMinPoolSize(ExchConfig.getInt("api.db.minPoolSize"))
  cpds.setAcquireIncrement(ExchConfig.getInt("api.db.acquireIncrement"))
  cpds.setMaxPoolSize(ExchConfig.getInt("api.db.maxPoolSize"))
  logger.info("Created c3p0 connection pool")

  val maxConns = ExchConfig.getInt("api.db.maxPoolSize")
  val db: Database =
    if (cpds != null) {
      Database.forDataSource(
        cpds,
        Some(maxConns),
        AsyncExecutor("ExchangeExecutor", maxConns, maxConns, 1000, maxConns))
    } else null
  logger.info("Set up DB connection with maxPoolSize=" + maxConns)

  def getDb: Database = db

  system.registerOnTermination(() => db.close())

  // A description of our application. This will show up in the Swagger docs.
  //protected val applicationDescription = "The Blue Horizon Data Exchange API."

  /**
   * Before every action runs, set the content type to be in JSON format.
   * before() {
   * contentType = formats("json")
   *
   * // We have to set these ourselves because we had to disable scalatra's builtin CorsSupport because for some inexplicable reason it doesn't set Access-Control-Allow-Origin which is critical
   * //response.setHeader("Access-Control-Allow-Origin", "*")  // <- this can only be used for unauthenticated requests
   * response.setHeader("Access-Control-Allow-Origin", request.getHeader("Origin"))
   * response.setHeader("Access-Control-Allow-Credentials", "true")
   * response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
   * //response.setHeader("Access-Control-Allow-Headers", "Cookie,Host,X-Forwarded-For,Accept-Charset,If-Modified-Since,Accept-Language,X-Forwarded-Port,Connection,X-Forwarded-Proto,User-Agent,Referer,Accept-Encoding,X-Requested-With,Authorization,Accept,Content-Type,X-Requested-With")  // this is taken from what CorsSupport sets
   * response.setHeader("Access-Control-Max-Age", "1800")
   * response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,HEAD,OPTIONS,PATCH")
   * }
   */

  // Browsers sometimes do a preflight check of this before making the real rest api call
  //options("/*"){
  //  val creds = credsForAnonymous()
  //  val userOrId = if (creds.isAnonymous) "(anonymous)" else creds.id
  //  val clientIp = request.header("X-Forwarded-For").orElse(Option(request.getRemoteAddr)).get      // haproxy inserts the real client ip into the header for us
  //  logger.info("User or id "+userOrId+" from "+clientIp+" running "+request.getMethod+" "+request.getPathInfo+" with request header "+request.getHeader("Access-Control-Request-Headers"))
  //}

  // Needed as the execution context for Futures, including db.run results
  //protected implicit def executor = scala.concurrent.ExecutionContext.Implicits.global

  /* Upgrade the db if necessary
  try { ExchangeApiTables.upgradeDb(db) }
  catch {
    // Handle db problems
    case timeout: java.util.concurrent.TimeoutException => halt(HttpCode.GW_TIMEOUT, ApiResponse(ApiResponseType.GW_TIMEOUT, ExchangeMessage.translateMessage("db.timeout.upgrading", timeout.getMessage)))
    case other: Throwable => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("db.exception.upgrading", other.getMessage)))
  } */

  // Initialize authentication cache from objects in the db
  AuthCache.initAllCaches(db, includingIbmAuth = true)

  val serverBinding: Future[Http.ServerBinding] = Http().bindAndHandle(routes, ExchangeApiConstants.serviceHost, ExchangeApiConstants.servicePort)

  serverBinding.onComplete {
    case Success(bound) =>
      println(s"Server online at http://${bound.localAddress.getHostString}:${bound.localAddress.getPort}/")
    case Failure(e) =>
      Console.err.println(s"Server could not start!")
      e.printStackTrace()
      system.terminate()
  }

  Await.result(system.whenTerminated, Duration.Inf)
}

/* these have been moved to auth/Exceptions.scala:
class AccessDeniedException(var httpCode: Int, var apiResponse: String, msg: String) extends Exception(msg)
class BadInputException(var httpCode: Int, var apiResponse: String, msg: String) extends Exception(msg)
class NotFoundException(var httpCode: Int, var apiResponse: String, msg: String) extends Exception(msg)
*/

/*
import javax.servlet.ServletContext
import com.horizon.exchangeapi._
import com.mchange.v2.c3p0.ComboPooledDataSource
import org.scalatra._
//import org.scalatra.CorsSupport   // allow cross-domain requests
import org.slf4j.LoggerFactory
import slick.jdbc.PostgresProfile.api._

/** Scalatra bootstrap file.
 *
 *  Used to mount servlets or filters, and run initialization code which needs to
 *  run at application start (e.g. database configurations, create loggers), and init params.
 */
class ScalatraBootstrap extends LifeCycle {

  implicit val swagger = new ExchangeApiSwagger     // this gets implicitly used by ExchangeApiApp and ResourcesApp

  val logger = LoggerFactory.getLogger(getClass)

  // Get config file, normally in /etc/horizon/exchange/config.json
  ExchConfig.load()

  // Load the db backend. The db access info must be in config.json
  var cpds: ComboPooledDataSource = _
  cpds = new ComboPooledDataSource
  configureC3p0(cpds)
  logger.info("Created c3p0 connection pool")

  /** Initialize the main servlet.
   *
   *  Mounts the top level URLs for the REST API and swagger, and creates the db object.
   */
  override def init(context: ServletContext) {
    // val db = if (cpds != null) Database.forDataSource(cpds) else null
    val maxConns = ExchConfig.getInt("api.db.maxPoolSize")
    val db =
      if (cpds != null) {
        Database.forDataSource(
          cpds,
          Some(maxConns),
          AsyncExecutor("ExchangeExecutor", maxConns, maxConns, 1000, maxConns)
        )
      } else null

    // Disable scalatra's builtin CorsSupport because for some inexplicable reason it doesn't set Access-Control-Allow-Origin which is critical
    context.setInitParameter("org.scalatra.cors.enable", "false")

    // None of these worked - taken from http://scalatra.org/guides/2.5/web-services/cors.html
//    context.initParameters("org.scalatra.cors.allowedOrigins") = "*"
//    context.setInitParameter(CorsSupport.AllowedOriginsKey, "*")

    // This worked as a test, but is not the fix we need
//    context.setInitParameter(CorsSupport.AllowedMethodsKey, "GET,POST,PUT,DELETE,HEAD,OPTIONS")

    context.mount(new ExchangeApiApp(db), "/v1", "v1")
    context.mount(new ResourcesApp, "/api-docs", "api-docs")
    context.mount(new SwaggerUiServlet, "/api", "api")
  }

  /** Closes the db connection in destroy(). */
  private def closeDbConnection() {
    logger.info("Closing c3po connection pool")
    cpds.close()
  }

  /** Closes the db connection when the servlet ends. */
  override def destroy(context: ServletContext) {
    super.destroy(context)
    closeDbConnection()
  }

  /** Configure the slick data pool source using values from the exchange config.json file */
  def configureC3p0(cpds: ComboPooledDataSource): Unit = {
    cpds.setDriverClass(ExchConfig.getString("api.db.driverClass")) //loads the jdbc driver
    cpds.setJdbcUrl(ExchConfig.getString("api.db.jdbcUrl"))
    cpds.setUser(ExchConfig.getString("api.db.user"))
    cpds.setPassword(ExchConfig.getString("api.db.password"))
    // the settings below are optional -- c3p0 can work with defaults
    cpds.setMinPoolSize(ExchConfig.getInt("api.db.minPoolSize"))
    cpds.setAcquireIncrement(ExchConfig.getInt("api.db.acquireIncrement"))
    cpds.setMaxPoolSize(ExchConfig.getInt("api.db.maxPoolSize"))
  }
}
 */
