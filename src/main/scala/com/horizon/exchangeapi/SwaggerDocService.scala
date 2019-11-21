package com.horizon.exchangeapi

//import akka.actor.ActorSystem
//import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives
import com.github.swagger.akka.SwaggerHttpService
import com.github.swagger.akka.model.Info
import io.swagger.v3.oas.models.ExternalDocumentation

/*Swagger references:
  - Swagger with akka-http: https://github.com/swagger-akka-http/swagger-akka-http
  - Swagger annotations: https://github.com/swagger-api/swagger-core/wiki/Swagger-2.X---Annotations
 */

//class SwaggerDocService /* (system: ActorSystem) */ extends SwaggerHttpService {
object SwaggerDocService extends SwaggerHttpService {
  //override implicit val actorSystem: ActorSystem = system
  //override implicit val materializer: ActorMaterializer = ActorMaterializer()
  override def apiClasses = Set(classOf[OrgsRoutes])
  override def host = s"${ExchangeApiConstants.serviceHost}:${ExchangeApiConstants.servicePort}" //the url of your api, not swagger's json endpoint
  override def apiDocsPath = "api-docs" //where you want the swagger-json endpoint exposed
  override def info = Info(version = "1.0") //provides license and other description details
  override def externalDocs = Some(new ExternalDocumentation().description("Open-horizon ExchangeAPI").url("https://github.com/open-horizon/exchange-api"))
  //override val securitySchemeDefinitions = Map("basicAuth" -> new BasicAuthDefinition())
  override def unwantedDefinitions = Seq("Function1", "Function1RequestContextFutureRouteResult")
}

/* Defines a route (that can be used in a browser) to return the swagger.json file that is built by SwaggerDocService.
  - Swagger UI static files come from: https://github.com/swagger-api/swagger-ui/dist
  - The Makefile target sync-swagger-ui puts those files under src/main/resources/swagger and modifies the url value in index.html to be /v1/api-docs/swagger.json
  - Configuration of the UI display: https://github.com/swagger-api/swagger-ui/blob/master/docs/usage/configuration.md
  - Maybe explore using https://github.com/pragmatico/swagger-ui-akka-http to run the swagger ui
 */
class SwaggerUiService extends Directives {
  val routes = path("swagger") { getFromResource("swagger/index.html") } ~ getFromResourceDirectory("swagger")
}
