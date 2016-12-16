package utils

import java.io.{File, InputStream}
import java.net.{URL, URLEncoder}
import java.nio.file.Files
import java.util.jar.JarInputStream
import javax.inject.Inject

import org.webjars.WebJarAssetLocator
import play.api.{Configuration, Logger}
import play.api.libs.ws.WSResponse

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.{Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import utils.Memcache._

class MavenCentral @Inject() (config: Configuration, memcache: Memcache) {

  lazy val tempDir: File = Files.createTempDirectory("webjars").toFile

  val primaryBaseJarUrl = config.getString("webjars.jarUrl.primary").get
  val fallbackBaseJarUrl = config.getString("webjars.jarUrl.fallback").get

  def getFile(groupId: String, artifactId: String, version: String): Try[(JarInputStream, InputStream)] = {
    val tmpFile = new File(tempDir, s"$groupId-$artifactId-$version.jar")

    val tryInputStreams = if (tmpFile.exists()) {
      val tryFileInputStream = Try(Files.newInputStream(tmpFile.toPath))
      tryFileInputStream.map(inputStream => (new JarInputStream(inputStream), inputStream))
    }
    else {
      val tryFileInputStream = getFileInputStream(primaryBaseJarUrl, groupId, artifactId, version).recoverWith {
        case _ =>
          getFileInputStream(fallbackBaseJarUrl, groupId, artifactId, version)
      }

      tryFileInputStream.flatMap { fileInputStream =>
        // todo: not thread safe!
        // write to the fs
        val tryCopy = Try(Files.copy(fileInputStream, tmpFile.toPath))
        tryCopy.flatMap { _ =>
          fileInputStream.close()

          val tryTmpFileInputStream = Try(Files.newInputStream(tmpFile.toPath))

          tryTmpFileInputStream.map(tmpFileInputStream => (new JarInputStream(tmpFileInputStream), tmpFileInputStream))
        }
      }
    }

    if (tryInputStreams.isFailure) {
      tmpFile.delete()
    }

    tryInputStreams
  }

  def getFileInputStream(baseJarUrl: String, groupId: String, artifactId: String, version: String): Try[InputStream] = {
    Try {
      val url = new URL(baseJarUrl.format(groupId.replace(".", "/"), artifactId, URLEncoder.encode(version, "UTF-8"), artifactId, URLEncoder.encode(version, "UTF-8")))
      url.openConnection().getInputStream
    }
  }

  def fetchFileList(groupId: String, artifactId: String, version: String): Future[List[String]] = {
    val cacheKey = s"listfiles-$groupId-$artifactId-$version"

    memcache.connection.get[List[String]](cacheKey).flatMap { maybeFileList =>
      maybeFileList.fold(Future.failed[List[String]](new Exception("cache miss")))(Future.successful)
    } recoverWith { case e: Exception =>
      Future.fromTry {
        getFile(groupId, artifactId, version).map { case (jarInputStream, inputStream) =>
          val webJarFiles = Iterator.continually(jarInputStream.getNextJarEntry).
            takeWhile(_ != null).
            filterNot(_.isDirectory).
            map(_.getName).
            filter(_.startsWith(WebJarAssetLocator.WEBJARS_PATH_PREFIX)).
            toList
          jarInputStream.close()
          inputStream.close()
          memcache.connection.set(cacheKey, webJarFiles, Duration.Inf).onFailure {
            case e: Exception => Logger.error(s"Could not store file list in cache for $cacheKey", e)
          }
          webJarFiles
        }
      }
    }
  }

  def numFiles(groupId: String, artifactId: String, version: String): Future[Int] = {
    val cacheKey = s"numfiles-$groupId-$artifactId-$version"

    memcache.connection.get[Int](cacheKey).flatMap { maybeNumFiles =>
      maybeNumFiles.fold(Future.failed[Int](new Exception("cache miss")))(Future.successful)
    } recoverWith { case e: Exception =>
      fetchFileList(groupId, artifactId, version).map { fileList =>
        val numFiles = fileList.size
        memcache.connection.set(cacheKey, numFiles, Duration.Inf)
        numFiles
      }
    }
  }

}

case class UnexpectedResponseException(response: WSResponse) extends RuntimeException {
  override def getMessage: String = response.statusText
}
