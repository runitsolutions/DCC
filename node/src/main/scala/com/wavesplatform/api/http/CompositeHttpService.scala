package com.wavesplatform.api.http

import java.util.concurrent.{LinkedBlockingQueue, RejectedExecutionException, ThreadPoolExecutor, TimeUnit}

import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LoggingMagnet}
import com.wavesplatform.settings.RestAPISettings
import com.wavesplatform.utils.ScorexLogging
import io.netty.util.concurrent.DefaultThreadFactory

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}

case class CompositeHttpService(routes: Seq[ApiRoute], settings: RestAPISettings) extends ScorexLogging {
  // Only affects extractScheduler { implicit sc => ... } routes
  private[this] val corePoolSize = (Runtime.getRuntime.availableProcessors() * 2).min(4)
  val scheduler: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(
    new ThreadPoolExecutor(
      corePoolSize,
      corePoolSize,
      60,
      TimeUnit.SECONDS,
      new LinkedBlockingQueue[Runnable],
      new DefaultThreadFactory("rest-heavy-request-processor", true), { (r: Runnable, executor: ThreadPoolExecutor) =>
        log.error(s"$r has been rejected from $executor")
        throw new RejectedExecutionException
      }
    ),
    log.error("Error in REST API", _)
  )

  private val redirectToSwagger = redirect("/api-docs/index.html", StatusCodes.PermanentRedirect)
  private val swaggerRoute: Route =
    (pathEndOrSingleSlash | path("swagger"))(redirectToSwagger) ~
      pathPrefix("api-docs") {
        pathEndOrSingleSlash(redirectToSwagger) ~
          path("swagger.json")(complete(patchedSwaggerJson)) ~
          getFromResourceDirectory("swagger-ui")
      }

  val compositeRoute: Route = withExecutionContext(scheduler)(extendRoute(routes.map(_.route).reduce(_ ~ _))) ~ swaggerRoute ~ complete(
    StatusCodes.NotFound
  )

  val loggingCompositeRoute: Route = Route.seal(DebuggingDirectives.logRequestResult(LoggingMagnet(_ => logRequestResponse))(compositeRoute))

  private def logRequestResponse(req: HttpRequest)(res: RouteResult): Unit = res match {
    case Complete(resp) =>
      val msg = s"HTTP ${resp.status.value} from ${req.method.value} ${req.uri}"
      if (resp.status == StatusCodes.OK) log.info(msg) else log.warn(msg)
    case _ =>
  }

  private val corsAllowedHeaders = (if (settings.apiKeyDifferentHost) List("api_key", "X-API-Key") else List.empty[String]) ++
    Seq("Authorization", "Content-Type", "X-Requested-With", "Timestamp", "Signature")

  private def corsAllowAll = if (settings.cors) respondWithHeader(`Access-Control-Allow-Origin`.*) else pass

  private def extendRoute(base: Route): Route = handleAllExceptions {
    if (settings.cors) { ctx =>
      val extendedRoute = corsAllowAll(base) ~ options {
        respondWithDefaultHeaders(
          `Access-Control-Allow-Credentials`(true),
          `Access-Control-Allow-Headers`(corsAllowedHeaders),
          `Access-Control-Allow-Methods`(OPTIONS, POST, PUT, GET, DELETE)
        )(corsAllowAll(complete(StatusCodes.OK)))
      }

      extendedRoute(ctx)
    } else base
  }

  private[this] lazy val patchedSwaggerJson = {
    import com.wavesplatform.Version
    import com.wavesplatform.account.AddressScheme
    import play.api.libs.json.{JsObject, Json}

    def chainIdString: String =
      if (Character.isAlphabetic(AddressScheme.current.chainId)) AddressScheme.current.chainId.toChar.toString
      else s"#${AddressScheme.current.chainId}"

    val json = Json.parse(getClass.getClassLoader.getResourceAsStream("swagger-ui/swagger.json")).as[JsObject]
    val patchedInfo = (json \ "info").as[JsObject] ++ Json.obj(
      "version" -> Version.VersionString,
      "title"   -> s"Decentralchain Full Node ($chainIdString)"
    )
    json ++ Json.obj("info" -> patchedInfo)
  }
}
