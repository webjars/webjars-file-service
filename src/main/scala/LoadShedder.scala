import com.jamesward.zio_mavencentral.MavenCentral.GroupArtifactVersion
import zio.*
import zio.http.*

import java.util.Locale

/**
 * Overload protection for `/files`.
 *
 * Every request reaches us through jsDelivr with identical headers, so we
 * can't tell a human from a crawler per client. Instead we rank requests by
 * what they ask for, and only shed when the dyno is actually overloaded
 * (unbounded concurrency previously exhausted Heroku's per-dyno thread limit
 * and wedged the JVM). Successful responses are `immutable` for a year, so
 * jsDelivr only asks us for files it hasn't cached yet; shedding affects
 * first fetches, not repeat page loads.
 *
 * Ranking, while load is between `softLimit` and `hardLimit`:
 *   - anything whose jar is already cached: served. Serving a cached entry
 *     costs about as much as rejecting it, and jsDelivr retries a 503 within
 *     seconds (ignoring `Retry-After`) 3-4 times, so shedding cheap requests
 *     multiplied load instead of reducing it. A 200 is cached by jsDelivr for
 *     a year and ends the retries.
 *   - cache misses (a full jar download) for runtime assets of a GAV that
 *     isn't being swept: served
 *   - cache misses for non-runtime paths (source maps, TS sources, tests,
 *     docs, ...): shed
 *   - cache misses for a GAV with more than `sweepLimit` distinct files
 *     requested inside `sweepWindow` (a real page needs a handful per
 *     webjar): shed
 * At `hardLimit` everything new is shed, as a safety valve.
 *
 * Cache misses (a full jar download, the expensive path) are additionally
 * gated by `maxConcurrentDownloads`: low-priority misses never queue for a
 * permit, high-priority misses queue for up to `downloadPermitWait`.
 */
final case class LoadShedder(
  config: LoadShedder.Config,
  active: Ref[Long],
  streamsInFlight: UIO[Long],
  sweeps: Ref[Map[GroupArtifactVersion, LoadShedder.Sweep]],
  downloads: Semaphore,
  shedCounts: Ref[Map[LoadShedder.ShedReason, Long]],
):
  import LoadShedder.*

  /** Run `work` for a `/files` request unless it should be shed. */
  def guard[R](gav: GroupArtifactVersion, file: Path, cached: Boolean)(work: ZIO[R, Nothing, Response]): ZIO[R, Nothing, Response] =
    ZIO.acquireReleaseWith(active.update(_ + 1))(_ => active.update(_ - 1)): _ =>
      for
        swept    <- recordAndCheckSwept(gav, file.encode)
        load     <- active.get.zipWith(streamsInFlight)(_ + _)
        priority  = LoadShedder.priority(file.encode, swept)
        response <- decide(config, load, priority, cached) match
          case Decision.Shed(reason) => shed(reason)
          case Decision.Admit        => if cached then work else gateDownload(priority)(work)
      yield response

  def snapshot: UIO[Snapshot] =
    for
      a <- active.get
      s <- sweeps.get
      c <- shedCounts.get
    yield Snapshot(a, s.count(_._2.exceeded), downloads, c)

  private def gateDownload[R](priority: Priority)(work: ZIO[R, Nothing, Response]): ZIO[R, Nothing, Response] =
    val wait = priority match
      case Priority.High   => config.downloadPermitWait
      case Priority.Low(_) => Duration.Zero
    ZIO.scoped[R]:
      downloads.withPermitScoped.timeout(wait).flatMap:
        case Some(_) => work
        case None    => shed(ShedReason.DownloadsBusy)

  private def shed(reason: ShedReason): UIO[Response] =
    shedCounts.update(m => m.updated(reason, m.getOrElse(reason, 0L) + 1)).as(shedResponse(config, reason))

  private def recordAndCheckSwept(gav: GroupArtifactVersion, path: String): UIO[Boolean] =
    Clock.nanoTime.flatMap: now =>
      sweeps.modify: m =>
        val current = m.get(gav).filter(s => now - s.windowStart < config.sweepWindow.toNanos)
        val next = current match
          case None                    => Sweep(now, Set(path), exceeded = false)
          case Some(s) if s.exceeded   => s
          case Some(s) =>
            val paths = s.paths + path
            // Once over the limit, drop the path set: we only need the verdict.
            if paths.size > config.sweepLimit then Sweep(s.windowStart, Set.empty, exceeded = true)
            else s.copy(paths = paths)
        (next.exceeded, m.updated(gav, next))

  private[LoadShedder] def pruneSweeps: UIO[Unit] =
    Clock.nanoTime.flatMap: now =>
      sweeps.update(_.filter((_, s) => now - s.windowStart < config.sweepWindow.toNanos))

object LoadShedder:

  final case class Config(
    softLimit: Long,
    hardLimit: Long,
    sweepLimit: Int,
    sweepWindow: Duration,
    maxConcurrentDownloads: Int,
    downloadPermitWait: Duration,
    retryAfter: Duration,
  )

  /**
   * Sized for a Standard-2X dyno, whose ~512 thread limit was hit with
   * ~700-1500 requests in flight. Healthy steady state is single digits.
   */
  val defaultConfig: Config = Config(
    softLimit              = 100,
    hardLimit              = 300,
    sweepLimit             = 30,
    sweepWindow            = 10.minutes,
    maxConcurrentDownloads = 8,
    downloadPermitWait     = 10.seconds,
    retryAfter             = 2.minutes,
  )

  enum ShedReason:
    case Overloaded, NonRuntimePath, SweptGav, DownloadsBusy

  given CanEqual[ShedReason, ShedReason] = CanEqual.derived

  enum Priority:
    case High
    case Low(reason: ShedReason)

  enum Decision:
    case Admit
    case Shed(reason: ShedReason)

  given CanEqual[Decision, Decision] = CanEqual.derived

  final case class Sweep(windowStart: Long, paths: Set[String], exceeded: Boolean)

  final case class Snapshot(active: Long, sweptGavs: Int, downloads: Semaphore, shedCounts: Map[ShedReason, Long]):
    def render: UIO[String] =
      downloads.available.map: free =>
        val counts = ShedReason.values.map(r => s"shed_${r.toString}=${shedCounts.getOrElse(r, 0L)}").mkString(" ")
        s"active_requests=$active swept_gavs=$sweptGavs download_permits_free=$free $counts"

  private val nonRuntimeDirs: Set[String] = Set(
    "src", "test", "tests", "spec", "specs", "__tests__", "demo", "demos",
    "example", "examples", "sample", "samples", "doc", "docs", "node_modules",
  )

  private val nonRuntimeExtensions: Seq[String] = Seq(
    ".map", ".ts", ".tsx", ".mts", ".cts", ".md", ".markdown",
  )

  private val nonRuntimeFilePrefixes: Seq[String] = Seq(
    "readme", "changelog", "changes", "history", "license", "licence", "contributing", "authors",
  )

  /** True for files a browser doesn't fetch when rendering a page (`.d.ts` is covered by `.ts`). */
  def isNonRuntimePath(path: String): Boolean =
    val segments = path.toLowerCase(Locale.ROOT).nn.split('/').toList.filter(_.nonEmpty)
    segments.lastOption.exists: fileName =>
      segments.init.exists(nonRuntimeDirs.contains) ||
        nonRuntimeExtensions.exists(fileName.endsWith) ||
        nonRuntimeFilePrefixes.exists(fileName.startsWith)

  def priority(path: String, swept: Boolean): Priority =
    if swept then Priority.Low(ShedReason.SweptGav)
    else if isNonRuntimePath(path) then Priority.Low(ShedReason.NonRuntimePath)
    else Priority.High

  def decide(config: Config, load: Long, priority: Priority, cached: Boolean): Decision =
    priority match
      case _ if load >= config.hardLimit                                => Decision.Shed(ShedReason.Overloaded)
      case Priority.Low(reason) if !cached && load >= config.softLimit => Decision.Shed(reason)
      case _                                                            => Decision.Admit

  def shedResponse(config: Config, reason: ShedReason): Response =
    Response(
      Status.ServiceUnavailable,
      Headers(
        Header.Custom("Retry-After", config.retryAfter.toSeconds.toString),
        Header.CacheControl.NoStore,
      ),
      Body.fromString(s"Temporarily overloaded ($reason), please retry later"),
    )

  def make(config: Config, streamsInFlight: UIO[Long]): ZIO[Scope, Nothing, LoadShedder] =
    for
      active    <- Ref.make(0L)
      sweeps    <- Ref.make(Map.empty[GroupArtifactVersion, Sweep])
      downloads <- Semaphore.make(config.maxConcurrentDownloads.toLong)
      counts    <- Ref.make(Map.empty[ShedReason, Long])
      shedder    = LoadShedder(config, active, streamsInFlight, sweeps, downloads, counts)
      _         <- shedder.pruneSweeps.repeat(Schedule.spaced(1.minute)).forkScoped
    yield shedder

  def layer(config: Config, streamsInFlight: UIO[Long]): ZLayer[Any, Nothing, LoadShedder] =
    ZLayer.scoped(make(config, streamsInFlight))
