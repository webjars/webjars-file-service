import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.http.{Client, MediaType}
import zio.test.*

object WebJarStoreSpec extends ZIOSpecDefault:

  private val gav = MavenCentral.gav("org.webjars", "jquery", "3.2.1")
  private val jqueryJs = s"${WebJarStore.webJarsPathPrefix}/jquery/3.2.1/jquery.js"

  private def awaitStored(store: WebJarStore): UIO[Unit] =
    store.storedCount.repeat(Schedule.recurUntil[Int](_ > 0) && Schedule.spaced(100.millis)).unit

  private def bytesOf(source: WebJarSource, entry: String): Task[Chunk[Byte]] =
    ZIO.scoped(source.open(entry).some.orElseFail(java.nio.file.NoSuchFileException(entry)).flatMap(_.runCollect))

  private def isStored(source: WebJarSource): Boolean =
    source match
      case _: WebJarSource.Stored => true
      case _: WebJarSource.Local  => false

  /** Download → serve locally → upload → served from the object store, including after a restart. */
  private def lifecycle =
    test("a fetched webjar is uploaded and then served from the object store, also by a fresh instance"):
      for
        store       <- ZIO.service[WebJarStore]
        objects     <- ZIO.service[ObjectStore]
        before      <- store.known(gav)
        local       <- store.fetch(gav)
        localBytes  <- bytesOf(local, jqueryJs)
        localNames  <- local.fileNames
        _           <- awaitStored(store)
        stored      <- store.known(gav).some
        storedBytes <- bytesOf(stored, jqueryJs)
        storedNames <- stored.fileNames
        // The staged jar is retired once stored; a source obtained before that
        // keeps working by reading from the object store.
        _           <- store.stagedCount.repeat(Schedule.recurUntil[Int](_ == 0) && Schedule.spaced(100.millis))
        afterRetire <- bytesOf(local, jqueryJs)
        missing     <- ZIO.scoped(stored.open(s"${WebJarStore.webJarsPathPrefix}/jquery/3.2.1/nope.js"))
        leftover    <- ZIO.attemptBlocking(Option(store.stagingDir.listFiles()).fold(0)(_.length))
        // A new instance (empty index, empty staging) must find the manifest.
        dir         <- WebJarStore.stagingDir
        restarted   <- WebJarStore.make(objects, dir, maxConcurrentUploads = 4)
        afterRestart <- restarted.known(gav)
      yield assertTrue(
        before.isEmpty,
        !isStored(local),
        isStored(stored),
        storedBytes == localBytes,
        storedNames == localNames,
        storedNames.contains(jqueryJs),
        afterRetire == localBytes,
        missing.isEmpty,
        leftover == 0,
        afterRestart.exists(isStored),
      )

  def spec = suite("WebJarStore")(
    suite("in-memory object store")(lifecycle)
      .provide(InMemoryObjectStore.layer, WebJarStore.layer, Client.default, MavenCentral.MavenCentralRepo.live, Scope.default),
    suite("Floci S3")(lifecycle)
      .provide(Floci.objectStore, WebJarStore.layer, Client.default, MavenCentral.MavenCentralRepo.live, Scope.default),
    suite("upload slots")(busy, sharedFilePermits, absentCache)
      .provide(Client.default, MavenCentral.MavenCentralRepo.live, Scope.default),
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)

  /** Counts reads per key and tracks how many writes are in flight at once. */
  private final case class InstrumentedObjectStore(underlying: ObjectStore, reads: Ref[Map[StoreKey, Int]], inFlight: Ref[Int], maxInFlight: Ref[Int]) extends ObjectStore:
    def get(key: StoreKey) = reads.update(m => m.updated(key, m.getOrElse(key, 0) + 1)) *> underlying.get(key)
    def write(key: StoreKey, bytes: Array[Byte], contentType: Option[MediaType]) =
      ZIO.acquireReleaseWith(inFlight.updateAndGet(_ + 1).flatMap(n => maxInFlight.update(math.max(_, n))))(_ => inFlight.update(_ - 1)): _ =>
        underlying.write(key, bytes, contentType).delay(2.millis)

  private val instrumented =
    for
      memory <- InMemoryObjectStore.make
      reads  <- Ref.make(Map.empty[StoreKey, Int])
      now    <- Ref.make(0)
      max    <- Ref.make(0)
    yield InstrumentedObjectStore(memory, reads, now, max)

  private def sharedFilePermits =
    test("file uploads across all uploading GAVs share one concurrency limit"):
      for
        objects <- instrumented
        dir     <- WebJarStore.stagingDir
        store   <- WebJarStore.make(objects, dir, maxConcurrentUploads = 4, maxConcurrentFileUploads = 3)
        _       <- store.fetch(gav) <&> store.fetch(otherGav)
        _       <- store.storedCount.repeat(Schedule.recurUntil[Int](_ == 2) && Schedule.spaced(50.millis))
        max     <- objects.maxInFlight.get
      yield assertTrue(max <= 3, max >= 2)

  private def absentCache =
    test("a GAV seen missing isn't re-read from the object store until this instance uploads it"):
      for
        objects <- instrumented
        dir     <- WebJarStore.stagingDir
        store   <- WebJarStore.make(objects, dir, maxConcurrentUploads = 2)
        misses  <- ZIO.foreach(1 to 3)(_ => store.known(gav))
        reads   <- objects.reads.get.map(_.getOrElse(WebJarStore.manifestKey(gav), 0))
        _       <- store.fetch(gav)
        _       <- awaitStored(store)
        after   <- store.known(gav)
        absent  <- store.absentCount
      yield assertTrue(misses.forall(_.isEmpty), reads == 1, after.exists(isStored), absent == 0)

  private val otherGav = MavenCentral.gav("org.webjars", "bootstrap", "5.3.3")

  /** Writes wait for `gate`, so uploads keep their slots until the test opens it. */
  private final case class GatedObjectStore(underlying: ObjectStore, gate: Promise[Nothing, Unit]) extends ObjectStore:
    def get(key: StoreKey) = underlying.get(key)
    def write(key: StoreKey, bytes: Array[Byte], contentType: Option[MediaType]) =
      gate.await *> underlying.write(key, bytes, contentType)

  private def busy =
    test("a miss is rejected only when every slot is uploading; the uploading GAV is still served"):
      for
        memory    <- InMemoryObjectStore.make
        gate      <- Promise.make[Nothing, Unit]
        dir       <- WebJarStore.stagingDir
        store     <- WebJarStore.make(GatedObjectStore(memory, gate), dir, maxConcurrentUploads = 1)
        first     <- store.fetch(gav)
        rejected  <- store.fetch(otherGav).flip
        joined    <- store.fetch(gav)
        knownMid  <- store.known(gav)
        _         <- gate.succeed(())
        _         <- awaitStored(store)
        _         <- store.uploadingCount.repeat(Schedule.recurUntil[Int](_ == 0) && Schedule.spaced(100.millis))
        afterFree <- store.fetch(otherGav)
        busyTotal <- store.busyCount
      yield assertTrue(
        !isStored(first),
        rejected match
          case UploadsBusy(g) => g == otherGav
          case _              => false
        ,
        !isStored(joined),
        knownMid.exists(!isStored(_)),
        !isStored(afterFree),
        busyTotal == 1L,
      )
