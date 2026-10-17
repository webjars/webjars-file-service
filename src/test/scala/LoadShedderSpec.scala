import App.given
import LoadShedder.given
import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.http.*
import zio.test.*

object LoadShedderSpec extends ZIOSpecDefault:

  private val gav = MavenCentral.GroupArtifactVersion(
    MavenCentral.GroupId("org.webjars"), MavenCentral.ArtifactId("demo"), MavenCentral.Version("1.0.0"),
  )

  private val ok: UIO[Response] = ZIO.succeed(Response.ok)

  /** Defaults that never shed, so each test opts into exactly one mechanism. */
  private val relaxed = LoadShedder.defaultConfig.copy(softLimit = 10_000, hardLimit = 10_000)

  private def shedder(config: LoadShedder.Config): ZIO[Scope, Nothing, LoadShedder] =
    LoadShedder.make(config, ZIO.succeed(0L))

  private def path(s: String): Path = Path.decode(s)

  private def counts(s: LoadShedder): UIO[Map[LoadShedder.ShedReason, Long]] =
    s.snapshot.map(_.shedCounts)

  def spec = suite("LoadShedder")(
    suite("isNonRuntimePath")(
      test("runtime assets are not flagged"):
        assertTrue(
          !LoadShedder.isNonRuntimePath("/jquery.min.js"),
          !LoadShedder.isNonRuntimePath("/dist/css/bootstrap.min.css"),
          !LoadShedder.isNonRuntimePath("/fonts/fa-solid-900.woff2"),
          !LoadShedder.isNonRuntimePath("/icons/48x48/apps/kmail.png"),
          !LoadShedder.isNonRuntimePath("/src-min/ace.js"),
        )
      ,
      test("source maps, TS sources, docs and dev directories are flagged"):
        assertTrue(
          LoadShedder.isNonRuntimePath("/dist/jquery.min.js.map"),
          LoadShedder.isNonRuntimePath("/types/index.d.ts"),
          LoadShedder.isNonRuntimePath("/lib/thing.ts"),
          LoadShedder.isNonRuntimePath("/README.md"),
          LoadShedder.isNonRuntimePath("/LICENSE"),
          LoadShedder.isNonRuntimePath("/CHANGELOG"),
          LoadShedder.isNonRuntimePath("/src/index.js"),
          LoadShedder.isNonRuntimePath("/test/spec/ol/format/wfs/boundedBy.xml"),
          LoadShedder.isNonRuntimePath("/node_modules/gulp/.npmignore"),
        )
      ,
    ),
    suite("decide")(
      test("below softLimit everything is admitted"):
        val c = LoadShedder.defaultConfig
        assertTrue(
          LoadShedder.decide(c, c.softLimit - 1, LoadShedder.Priority.Low(LoadShedder.ShedReason.SweptGav)) == LoadShedder.Decision.Admit,
        )
      ,
      test("between soft and hard limits only low priority is shed"):
        val c = LoadShedder.defaultConfig
        assertTrue(
          LoadShedder.decide(c, c.softLimit, LoadShedder.Priority.High) == LoadShedder.Decision.Admit,
          LoadShedder.decide(c, c.softLimit, LoadShedder.Priority.Low(LoadShedder.ShedReason.NonRuntimePath)) ==
            LoadShedder.Decision.Shed(LoadShedder.ShedReason.NonRuntimePath),
        )
      ,
      test("at hardLimit everything is shed"):
        val c = LoadShedder.defaultConfig
        assertTrue(
          LoadShedder.decide(c, c.hardLimit, LoadShedder.Priority.High) == LoadShedder.Decision.Shed(LoadShedder.ShedReason.Overloaded),
        )
      ,
    ),
    suite("guard")(
      test("under overload, sheds non-runtime paths with 503 + Retry-After but serves runtime assets"):
        for
          s       <- shedder(relaxed.copy(softLimit = 0))
          asset   <- s.guard(gav, path("/demo.js"), cached = true)(ok)
          sourceMap <- s.guard(gav, path("/demo.js.map"), cached = true)(ok)
          c       <- counts(s)
        yield assertTrue(
          asset.status == Status.Ok,
          sourceMap.status == Status.ServiceUnavailable,
          sourceMap.rawHeader("Retry-After").contains("120"),
          c.get(LoadShedder.ShedReason.NonRuntimePath).contains(1L),
        )
      ,
      test("not overloaded: non-runtime paths are served"):
        for
          s <- shedder(relaxed)
          r <- s.guard(gav, path("/demo.js.map"), cached = true)(ok)
        yield assertTrue(r.status == Status.Ok)
      ,
      test("a GAV swept past sweepLimit distinct files is shed under overload, and resets after the window"):
        for
          s      <- shedder(relaxed.copy(softLimit = 0, sweepLimit = 2, sweepWindow = 1.minute))
          first  <- ZIO.foreach(List("/a.js", "/b.js", "/a.js"))(p => s.guard(gav, path(p), cached = true)(ok))
          third  <- s.guard(gav, path("/c.js"), cached = true)(ok)
          swept  <- s.snapshot.map(_.sweptGavs)
          _      <- TestClock.adjust(2.minutes)
          after  <- s.guard(gav, path("/d.js"), cached = true)(ok)
        yield assertTrue(
          first.forall(_.status == Status.Ok), // repeats of /a.js don't count as new files
          third.status == Status.ServiceUnavailable,
          swept == 1,
          after.status == Status.Ok,
        )
      ,
      test("cache misses are gated by the download cap: low priority sheds immediately, high priority waits then sheds"):
        for
          s       <- shedder(relaxed.copy(maxConcurrentDownloads = 1, downloadPermitWait = 5.seconds))
          started <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          holder  <- s.guard(gav, path("/held.js"), cached = false)(started.succeed(()) *> release.await *> ok).fork
          _       <- started.await
          low     <- s.guard(gav, path("/x.js.map"), cached = false)(ok)
          high    <- s.guard(gav, path("/y.js"), cached = false)(ok).fork
          // Make sure the fiber is parked on the permit before moving the clock.
          _       <- high.status.repeatUntil:
                       case _: Fiber.Status.Suspended => true
                       case _                         => false
          _       <- TestClock.adjust(6.seconds)
          highR   <- high.join
          hit     <- s.guard(gav, path("/z.js"), cached = true)(ok)
          _       <- release.succeed(())
          held    <- holder.join
          c       <- counts(s)
        yield assertTrue(
          low.status == Status.ServiceUnavailable,
          highR.status == Status.ServiceUnavailable,
          hit.status == Status.Ok, // cache hits never need a download permit
          held.status == Status.Ok,
          c.get(LoadShedder.ShedReason.DownloadsBusy).contains(2L),
        )
      ,
      test("active request count returns to zero after requests complete"):
        for
          s <- shedder(relaxed)
          _ <- s.guard(gav, path("/a.js"), cached = true)(ok)
          a <- s.snapshot.map(_.active)
        yield assertTrue(a == 0L)
      ,
    ),
  )
