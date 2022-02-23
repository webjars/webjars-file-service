package controllers

import java.io.{File, FileNotFoundException}
import java.net.URL

import javax.inject.{Inject, Singleton}
import play.api._
import play.api.http.{AcceptEncoding, FileMimeTypes, HttpErrorHandler}
import play.api.libs.json.Json
import play.api.mvc._
import utils.MavenCentral

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try


@Singleton
class Application @Inject() (mavenCentral: MavenCentral, fileMimeTypes: FileMimeTypes, assetsConfiguration: AssetsConfiguration, httpErrorHandler: HttpErrorHandler) extends InjectedController with Logging {

  val webJarAssetsMetadata = new AssetsMetadata {
    private lazy val assetInfoCache = new SelfPopulatingMap[String, AssetInfo]()

    override def finder: AssetsFinder = {
      new AssetsFinder {
        override def assetsBasePath: String = ""
        override def assetsUrlPrefix: String = ""
        override def findAssetPath(basePath: String, rawPath: String): String = ""
      }
    }

    override private[controllers] def digest(path: String): Option[String] = None

    private def assetInfoFromResource(name: String): Option[AssetInfo] = {
      val file = new File(name)
      Try(new URL("jar:file:" + file.getAbsolutePath)).map { url =>
        val maybeDigest = digest(name)
        new AssetInfo(name, url, Seq.empty, maybeDigest, assetsConfiguration, fileMimeTypes)
      }.toOption
    }

    private def assetInfo(name: String): Future[Option[AssetInfo]] = {
      assetInfoCache.putIfAbsent(name)(assetInfoFromResource)
    }

    override private[controllers] def assetInfoForRequest(request: RequestHeader, name: String): Future[Option[(AssetInfo, AcceptEncoding)]] = {
      assetInfo(name).map(_.map(_ -> AcceptEncoding.forRequest(request)))
    }
  }

  val webJarAssetsBuilder = new AssetsBuilder(httpErrorHandler, webJarAssetsMetadata)

  def file(groupId: String, artifactId: String, webJarVersion: String, file: String) = CorsAction {
    Action.async { request =>
      Future.fromTry {
        mavenCentral.getFile(groupId, artifactId, webJarVersion)
      } flatMap { jarFile =>
        val pathPrefix = s"META-INF/resources/webjars/$artifactId"
        mavenCentral.fetchFileList(groupId, artifactId, webJarVersion).flatMap { webJarFiles =>
          val maybeFileWithVersion = webJarFiles.find { webJarFile =>
            webJarFile == s"$pathPrefix/$webJarVersion/$file"
          }

          val maybeFile = maybeFileWithVersion.orElse {
            webJarFiles.find { webJarFile =>
              // allows for paths where the path may not include the WebJar version
              val pathWithoutVersion = webJarFile.stripPrefix(pathPrefix + "/")
              pathWithoutVersion == file
            }
          }

          maybeFile.fold(Future.failed[Result](new FileNotFoundException(s"$file not found in WebJar"))) { webJarFile =>
            val name = jarFile.getAbsolutePath + "!/" + webJarFile
            webJarAssetsBuilder.at("", name, true)(request)
          }
        }
      } recover {
        case e: FileNotFoundException =>
          NotFound(s"WebJar Not Found $groupId : $artifactId : $webJarVersion - ${e.getMessage}")
        case e: Exception =>
          InternalServerError(s"Could not find WebJar ($groupId : $artifactId : $webJarVersion)\n${e.getMessage}")
      }
    }
  }

  def fileOptions(file: String) = CorsAction {
    Action {
      Ok.withHeaders(ACCESS_CONTROL_ALLOW_HEADERS -> Seq(CONTENT_TYPE).mkString(","))
    }
  }

  def listFiles(groupId: String, artifactId: String, version: String) = CorsAction {
    Action.async {
      mavenCentral.fetchFileList(groupId, artifactId, version).map { fileList =>
        Ok(Json.toJson(fileList))
      } recover {
        case e: FileNotFoundException =>
          NotFound(s"WebJar Not Found $groupId : $artifactId : $version - ${e.getMessage}")
        case e: Exception =>
          InternalServerError(e.getMessage)
      }
    }
  }

  def numFiles(groupId: String, artifactId: String, version: String) = CorsAction {
    Action.async {
      mavenCentral.numFiles(groupId, artifactId, version).map { numFiles =>
        Ok(numFiles.toString)
      } recover {
        case e: FileNotFoundException =>
          NotFound(s"WebJar Not Found $groupId : $artifactId : $version - ${e.getMessage}")
        case e: Exception =>
          logger.error(s"Error getting numFiles for $groupId $artifactId $version", e)
          InternalServerError(e.getMessage)
      }
    }
  }

  def corsPreflight(path: String) = Action {
    Ok.withHeaders(
      ACCESS_CONTROL_ALLOW_ORIGIN -> "*",
      ACCESS_CONTROL_ALLOW_METHODS -> "GET"
    )
  }

  case class CorsAction[A](action: Action[A]) extends Action[A] {

    def apply(request: Request[A]): Future[Result] = {
      action(request).map(result => result.withHeaders(ACCESS_CONTROL_ALLOW_ORIGIN -> "*"))
    }

    lazy val parser = action.parser

    override def executionContext: ExecutionContext = action.executionContext
  }

}
