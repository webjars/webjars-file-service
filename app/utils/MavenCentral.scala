package utils

import java.io.{File, InputStream}
import java.net.{URL, URLEncoder}
import java.nio.file.Files
import java.util.jar.JarInputStream

import javax.inject.Inject
import org.webjars.WebJarAssetLocator
import play.api.Configuration
import play.api.cache.AsyncCacheApi

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Random, Success, Try}

class MavenCentral @Inject() (config: Configuration, cache: AsyncCacheApi) {

  lazy val tempDir: File = Files.createTempDirectory("webjars").toFile

  val baseJarUrl = config.get[String]("webjars.jarUrl")

  def getFile(groupId: String, artifactId: String, version: String): Try[File] = {
    val jarFile = new File(tempDir, s"$groupId-$artifactId-$version.jar")

    if (jarFile.exists()) {
      Success(jarFile)
    }
    else {
      val tryFileInputStream = getFileInputStream(baseJarUrl, groupId, artifactId, version)

      val trySave = tryFileInputStream.flatMap { fileInputStream =>
        val randomString = Random.alphanumeric.take(8).mkString
        val tmpFile = new File(tempDir, s"$groupId-$artifactId-$version.jar-tmp-$randomString")

        // write to the fs
        val tryCopy = Try(Files.copy(fileInputStream, tmpFile.toPath))
        val tryMove = tryCopy.flatMap { _ =>
          fileInputStream.close()
          Try(Files.move(tmpFile.toPath, jarFile.toPath)).map(_.toFile)
        }

        tryMove
      }

      if (trySave.isFailure) {
        jarFile.delete()
      }

      trySave
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
      Future.fromTry {
        getFile(groupId, artifactId, version).flatMap { jarFile =>

          val tryTmpFileInputStream = Try(Files.newInputStream(jarFile.toPath))

          tryTmpFileInputStream.map { tmpFileInputStream =>
            val jarInputStream = new JarInputStream(tmpFileInputStream)

            val webJarFiles = Iterator.continually(jarInputStream.getNextJarEntry).
              takeWhile(_ != null).
              filterNot(_.isDirectory).
              map(_.getName).
              filter(_.startsWith(WebJarAssetLocator.WEBJARS_PATH_PREFIX)).
              toList

            jarInputStream.close()
            tmpFileInputStream.close()

            webJarFiles
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
