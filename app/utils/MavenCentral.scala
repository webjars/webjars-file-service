package utils

import java.io.{File, InputStream}
import java.net.{URL, URLEncoder}
import java.nio.file.Files
import java.util.jar.JarInputStream
import javax.inject.Inject
import org.webjars.WebJarAssetLocator
import play.api.Configuration
import play.api.cache.AsyncCacheApi

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Random, Success, Try, Using}

class MavenCentral @Inject() (config: Configuration, cache: AsyncCacheApi) {

  lazy val tempDir: File = Files.createTempDirectory("webjars").toFile

  private val ongoingDownloads = new ConcurrentHashMap[(String, String, String), Future[File]]()

  val baseJarUrl = config.get[String]("webjars.jarUrl")

  def getFile(groupId: String, artifactId: String, version: String): Future[File] = {
    val jarFile = new File(tempDir, s"$groupId-$artifactId-$version.jar")
    if (jarFile.exists()) {
      Future.successful(jarFile)
    }
    else {
      Option(ongoingDownloads.get((groupId, artifactId, version))).getOrElse {
        val tryFileInputStream = getFileInputStream(baseJarUrl, groupId, artifactId, version)

        val f = Future.fromTry {
          tryFileInputStream.flatMap { fileInputStream =>
            val trySave = Using(fileInputStream) { inputStream =>
              val randomString = Random.alphanumeric.take(8).mkString
              val tmpFile = new File(tempDir, s"$groupId-$artifactId-$version.jar-tmp-$randomString")
              Files.copy(inputStream, tmpFile.toPath)
              tmpFile
            } flatMap { f =>
              if (jarFile.exists()) {
                Success(jarFile)
              }
              else {
                Try(Files.move(f.toPath, jarFile.toPath)).map(_.toFile)
              }
            }

            if (trySave.isFailure) {
              jarFile.delete()
            }

            trySave
          }
        }

        f.onComplete { _ =>
          ongoingDownloads.remove((groupId, artifactId, version))
        }

        f
      }
    }
  }

  def getFileInputStream(baseJarUrl: String, groupId: String, artifactId: String, version: String): Try[InputStream] = {
    Try {
      val url = new URL(baseJarUrl.format(groupId.replace(".", "/"), artifactId, URLEncoder.encode(version, "UTF-8"), artifactId, URLEncoder.encode(version, "UTF-8")))
      url.openConnection().getInputStream
    }
  }

  def fetchFileList(groupId: String, artifactId: String, version: String): Future[List[String]] = {
    val cacheKey = s"listfiles-$groupId-$artifactId-$version"

    cache.getOrElseUpdate(cacheKey) {
      getFile(groupId, artifactId, version).flatMap { jarFile =>
        val tryTmpFileInputStream = Try(Files.newInputStream(jarFile.toPath))

        Future.fromTry {
          tryTmpFileInputStream.flatMap { tmpFileInputStream =>
            Using(new JarInputStream(tmpFileInputStream)) { jarInputStream =>
              Iterator.continually(jarInputStream.getNextJarEntry).
                takeWhile(_ != null).
                filterNot(_.isDirectory).
                map(_.getName).
                filter(_.startsWith(WebJarAssetLocator.WEBJARS_PATH_PREFIX)).
                toList
            }
          }
        }
      }
    }
  }

  def numFiles(groupId: String, artifactId: String, version: String): Future[Int] = {
    val cacheKey = s"numfiles-$groupId-$artifactId-$version"

    cache.getOrElseUpdate(cacheKey) {
      fetchFileList(groupId, artifactId, version).map(_.size)
    }
  }

}
