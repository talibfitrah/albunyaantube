package com.albunyaan.tube.perf

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * Basic load profile for playlist listings. Parameters can be overridden with
 * JVM system properties: -Dusers=200 -Dramp=60 -DbaseUrl=http://localhost:8080
 */
class ListingSweepSimulation extends Simulation {

  private val userCount: Int = Integer.getInteger("users", 200)
  private val rampSeconds: Int = Integer.getInteger("ramp", 60)
  private val requestLimit: Int = Integer.getInteger("limit", 50)
  private val category: String = System.getProperty("category", "")
  private val baseUrl: String = System.getProperty("baseUrl", "http://localhost:8080")

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")

  private val listPlaylists = scenario("playlistListing")
    .exec { session =>
      val cursor = session("cursor").asOption[String].getOrElse("")
      val queryParams = Seq(
        Some(s"limit=$requestLimit"),
        Option(cursor).filter(_.nonEmpty).map(c => s"cursor=$c"),
        Option(category).filter(_.nonEmpty).map(c => s"categoryId=$c")
      ).flatten.mkString("&")
      session.set("query", queryParams)
    }
    .exec(
      http("list_playlists")
        .get(session => s"/admin/playlists?${session("query").as[String]}")
        .check(status.is(200))
    )

  setUp(
    listPlaylists.inject(rampUsers(userCount).during(rampSeconds.seconds))
  ).protocols(httpProtocol)
}

