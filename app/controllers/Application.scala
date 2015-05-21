package controllers

import java.io.{FileNotFoundException, BufferedInputStream}

import org.joda.time.DateTimeZone
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import play.api.libs.json.Json
import utils.MavenCentral
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import play.api._
import play.api.libs.MimeTypes
import play.api.mvc._
import play.api.Play.current

import utils.MavenCentral.UnexpectedResponseException

import scala.util.{Failure, Success}

object Application extends Controller {

  def file(groupId: String, artifactId: String, webJarVersion: String, file: String) = CorsAction {
    Action.async { request =>
      val pathPrefix = s"META-INF/resources/webjars/$artifactId/"

      Future.fromTry {
        MavenCentral.getFile(groupId, artifactId, webJarVersion).map { case (jarInputStream, inputStream) =>
          Stream.continually(jarInputStream.getNextJarEntry).takeWhile(_ != null).find { jarEntry =>
            // this allows for sloppyness where the webJarVersion and path differ
            // todo: eventually be more strict but since this has been allowed many WebJars do not have version and path consistency
            jarEntry.getName.startsWith(pathPrefix) && jarEntry.getName.endsWith(s"/$file")
          }.fold {
            jarInputStream.close()
            inputStream.close()
            NotFound(s"Found WebJar ($groupId : $artifactId : $webJarVersion) but could not find: $pathPrefix$webJarVersion/$file")
          } { jarEntry =>
            val bis = new BufferedInputStream(jarInputStream)
            val bArray = Stream.continually(bis.read).takeWhile(_ != -1).map(_.toByte).toArray
            bis.close()
            jarInputStream.close()
            inputStream.close()

            //// From Play's Assets controller
            val contentType = MimeTypes.forFileName(file).map(m => m + addCharsetIfNeeded(m)).getOrElse(BINARY)
            ////

            Ok(bArray).as(contentType).withHeaders(
              CACHE_CONTROL -> "max-age=290304000, public",
              DATE -> df.print((new java.util.Date).getTime),
              LAST_MODIFIED -> df.print(jarEntry.getLastModifiedTime.toMillis)
            )
          }
        }
      } recover {
        case nf: FileNotFoundException =>
          NotFound(s"WebJar Not Found $groupId : $artifactId : $webJarVersion")
        case ure: UnexpectedResponseException =>
          Status(ure.response.status)(s"Problems retrieving WebJar ($groupId : $artifactId : $webJarVersion) - ${ure.response.statusText}")
        case e: Exception =>
          InternalServerError(s"Could not find WebJar ($groupId : $artifactId : $webJarVersion)\n${e.getMessage}")
      }
    }
  }

  def fileOptions(file: String) = CorsAction {
    Action { request =>
      Ok.withHeaders(ACCESS_CONTROL_ALLOW_HEADERS -> Seq(CONTENT_TYPE).mkString(","))
    }
  }

  def listFiles(groupId: String, artifactId: String, version: String) = CorsAction {
    Action {
      MavenCentral.fetchFileList(groupId, artifactId, version) match {
        case Success(fileList) =>
          Ok(Json.toJson(fileList))
        case Failure(e: FileNotFoundException) =>
          NotFound(s"WebJar Not Found $groupId : $artifactId : $version")
        case Failure(ure: UnexpectedResponseException) =>
          Status(ure.response.status)(s"Problems retrieving WebJar ($groupId : $artifactId : $version) - ${ure.response.statusText}")
        case Failure(e) =>
          InternalServerError(e.getMessage)
      }
    }
  }

  case class CorsAction[A](action: Action[A]) extends Action[A] {

    def apply(request: Request[A]): Future[Result] = {
      action(request).map(result => result.withHeaders(ACCESS_CONTROL_ALLOW_ORIGIN -> "*"))
    }

    lazy val parser = action.parser
  }

  def corsPreflight(path: String) = Action {
    Ok.withHeaders(
      ACCESS_CONTROL_ALLOW_ORIGIN -> "*",
      ACCESS_CONTROL_ALLOW_METHODS -> "GET"
    )
  }


  //// From Play's Asset controller

  private val timeZoneCode = "GMT"

  //Dateformatter is immutable and threadsafe
  private val df: DateTimeFormatter =
    DateTimeFormat.forPattern("EEE, dd MMM yyyy HH:mm:ss '" + timeZoneCode + "'").withLocale(java.util.Locale.ENGLISH).withZone(DateTimeZone.forID(timeZoneCode))

  //Dateformatter is immutable and threadsafe
  private val dfp: DateTimeFormatter =
    DateTimeFormat.forPattern("EEE, dd MMM yyyy HH:mm:ss").withLocale(java.util.Locale.ENGLISH).withZone(DateTimeZone.forID(timeZoneCode))

  private lazy val defaultCharSet = Play.configuration.getString("default.charset").getOrElse("utf-8")

  private def addCharsetIfNeeded(mimeType: String): String =
    if (MimeTypes.isText(mimeType))
      "; charset=" + defaultCharSet
    else ""

  ////

}