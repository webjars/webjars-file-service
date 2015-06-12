package utils

import play.api.libs.json.{Json, JsValue}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Try

object Slam extends App {

  val hostname = Try(args(0)).getOrElse("localhost:9000")
  val numRequests = Try(args(1).toInt).getOrElse(100)

  val task = StandaloneWS.withWs { ws =>

    ws.url("http://www.webjars.org/all").get().flatMap { response =>

      val webJars = response.json.as[Seq[JsValue]].flatMap { webJarJson =>
        val groupId = (webJarJson \ "groupId").as[String]
        val artifactId = (webJarJson \ "artifactId").as[String]
        val versions = (webJarJson \ "versions").as[Seq[JsValue]].map(_.\("number").as[String])

        versions.map((groupId, artifactId, _))
      }

      val listFilesFutures = webJars.take(numRequests).map { case (groupId, artifactId, version) =>
        ws.url(s"http://$hostname/listfiles/$groupId/$artifactId/$version").get().map(_.json).recover {
          case _ => Json.arr()
        }
      }

      val results = Future.sequence(listFilesFutures)

      results.foreach(s => println(s"Fetched ${s.size} WebJars"))

      results
    }

  }

  Await.result(task, 10.minutes)

}
