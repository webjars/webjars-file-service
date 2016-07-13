package utils

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{DeflaterOutputStream, InflaterInputStream}
import javax.inject.Inject

import com.google.inject.AbstractModule
import play.api.libs.json.Json
import play.api.{Configuration => PlayConfig}
import shade.memcached.{AuthConfiguration, Codec, Configuration, Memcached}

import scala.io.Source
import scala.xml.{Elem, XML}

class MemcacheModule extends AbstractModule {

  override def configure() = {
    bind(classOf[Memcache]).to(classOf[MemcacheImpl])
  }

}

trait Memcache {
  val connection: Memcached
}

class MemcacheImpl @Inject() (playConfig: PlayConfig) extends Memcache {

  lazy val connection = {
    val maybeUsernamePassword = for {
      username <- playConfig.getString("memcached.username")
      password <- playConfig.getString("memcached.password")
    } yield (username, password)

    val authConfig = maybeUsernamePassword.map {
      case (username, password) =>
        AuthConfiguration(username, password)
    }

    val config = Configuration(playConfig.getString("memcached.servers").get, authConfig)

    Memcached(config)(scala.concurrent.ExecutionContext.global)
  }

}

object Memcache {

  // from: http://stackoverflow.com/questions/15079332/round-tripping-through-deflater-in-scala-fails

  def compress(bytes: Array[Byte]): Array[Byte] = {
    val deflater = new java.util.zip.Deflater
    val baos = new ByteArrayOutputStream
    val dos = new DeflaterOutputStream(baos, deflater)
    dos.write(bytes)
    dos.finish()
    dos.close()
    baos.close()
    deflater.end()
    baos.toByteArray
  }

  def decompress(bytes: Array[Byte]): Array[Byte] = {
    val inflater = new java.util.zip.Inflater()
    val bytesIn = new ByteArrayInputStream(bytes)
    val in = new InflaterInputStream(bytesIn, inflater)
    val out = Source.fromInputStream(in).map(_.toByte).toArray
    in.close()
    bytesIn.close()
    inflater.end()
    out
  }

  implicit object StringsCodec extends Codec[List[String]] {
    def serialize(fileList: List[String]): Array[Byte] = compress(Json.toJson(fileList).toString().getBytes)
    def deserialize(data: Array[Byte]): List[String] = Json.parse(decompress(data)).as[List[String]]
  }

  implicit object ElemCode extends Codec[Elem] {
    def serialize(elem: Elem): Array[Byte] = compress(elem.toString().getBytes)
    def deserialize(data: Array[Byte]): Elem = XML.loadString(new String(decompress(data)))
  }

}
