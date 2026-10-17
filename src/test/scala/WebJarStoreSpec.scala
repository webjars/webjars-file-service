import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.http.Client
import zio.test.*

object WebJarStoreSpec extends ZIOSpecDefault:

  private val gav = MavenCentral.gav("org.webjars", "jquery", "3.2.1")
  private val jqueryJs = s"${WebJarStore.webJarsPathPrefix}/jquery/3.2.1/jquery.js"

  private def awaitStored(store: WebJarStore): UIO[Unit] =
    store.storedCount.repeat(Schedule.recurUntil[Int](_ > 0) && Schedule.spaced(100.millis)).unit

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
        localBytes  <- local.stream(jqueryJs).runCollect
        localNames  <- local.fileNames
        _           <- awaitStored(store)
        stored      <- store.known(gav).some
        storedBytes <- stored.stream(jqueryJs).runCollect
        storedNames <- stored.fileNames
        // The staged jar is retired once stored; a source obtained before that
        // keeps working by reading from the object store.
        _           <- store.stagedCount.repeat(Schedule.recurUntil[Int](_ == 0) && Schedule.spaced(100.millis))
        afterRetire <- local.stream(jqueryJs).runCollect
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
        leftover == 0,
        afterRestart.exists(isStored),
      )

  def spec = suite("WebJarStore")(
    suite("in-memory object store")(lifecycle)
      .provide(InMemoryObjectStore.layer, WebJarStore.layer, Client.default, MavenCentral.MavenCentralRepo.live, Scope.default),
    suite("Floci S3")(lifecycle)
      .provide(Floci.objectStore, WebJarStore.layer, Client.default, MavenCentral.MavenCentralRepo.live, Scope.default),
    suite("upload slots")(busy)
      .provide(Client.default, MavenCentral.MavenCentralRepo.live, Scope.default),
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)

  private val otherGav = MavenCentral.gav("org.webjars", "bootstrap", "5.3.3")

  /** Writes wait for `gate`, so uploads keep their slots until the test opens it. */
  private final case class GatedObjectStore(underlying: ObjectStore, gate: Promise[Nothing, Unit]) extends ObjectStore:
    def size(key: StoreKey) = underlying.size(key)
    def stream(key: StoreKey) = underlying.stream(key)
    def read(key: StoreKey) = underlying.read(key)
    def write(key: StoreKey, bytes: Chunk[Byte], contentType: Option[String]) =
      gate.await *> underlying.write(key, bytes, contentType)

  private def busy =
    test("a miss is rejected only when every slot is uploading; the uploading GAV is still served"):
      for
        memory    <- Ref.make(Map.empty[StoreKey, Chunk[Byte]]).map(InMemoryObjectStore(_))
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
