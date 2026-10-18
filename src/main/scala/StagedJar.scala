import com.jamesward.zio_mavencentral.MavenCentral.{GroupArtifactVersion, JarMeta}
import zio.*
import zio.stream.ZStream

import java.io.{File, IOException}
import java.nio.file.NoSuchFileException
import java.util.zip.ZipFile

/**
 * A webjar's jar on local disk while it is uploaded to the object store.
 *
 * The file and its open `ZipFile` live until [[retire]] is called (after the
 * upload, successful or not) and every in-flight read has finished; then
 * both are closed/deleted. Reads started after retirement fail with
 * [[StagedJar.Retired]], so callers fall back to the object store.
 */
final class StagedJar private (
  val gav: GroupArtifactVersion,
  val meta: JarMeta,
  file: File,
  zip: ZipFile,
  state: Ref[StagedJar.State],
  onClose: UIO[Unit],
):
  import StagedJar.*

  private val acquire: ZIO[Scope, Retired, ZipFile] =
    ZIO.acquireRelease(
      state.modify(s => if s.retired then (false, s) else (true, s.copy(readers = s.readers + 1)))
        .flatMap(acquired => if acquired then ZIO.succeed(zip) else ZIO.fail(Retired(gav)))
    )(_ =>
      state.modify: s =>
        val next = s.copy(readers = s.readers - 1)
        (next.retired && next.readers == 0, next)
      .flatMap(close.when(_))
    )

  /** Closes the zip and deletes the file once the last reader is done. */
  val retire: UIO[Unit] =
    state.modify(s => if s.retired then (false, s) else (s.readers == 0, s.copy(retired = true)))
      .flatMap(close.when(_))
      .unit

  private def close: UIO[Unit] =
    ZIO.attemptBlockingIO(zip.close()).catchAllCause(ZIO.logWarningCause(s"staged jar close failed gav=$gav", _)) *>
      ZIO.attemptBlockingIO(file.delete()).catchAllCause(ZIO.logWarningCause(s"staged jar delete failed gav=$gav", _)) *>
      onClose

  private def withZip[A](f: ZipFile => A): IO[Retired | IOException, A] =
    ZIO.scoped(acquire.flatMap(z => ZIO.attemptBlockingIO(f(z))))

  def hasEntry(name: String): IO[Retired | IOException, Boolean] =
    withZip(_.getEntry(name) != null)

  def entryNames(predicate: String => Boolean): IO[Retired | IOException, List[String]] =
    entries(predicate).map(_.map(_.name))

  /** Matching entries with their uncompressed sizes (`-1` if the jar doesn't record it). */
  def entries(predicate: String => Boolean): IO[Retired | IOException, List[Entry]] =
    withZip: z =>
      val it = z.entries.nn
      val b  = List.newBuilder[Entry]
      while it.hasMoreElements do
        val e = it.nextElement().nn
        val name = e.getName.nn
        if predicate(name) then b += Entry(name, e.getSize)
      b.result()

  def readEntry(name: String): IO[Retired | IOException, Array[Byte]] =
    withZip: z =>
      Option(z.getEntry(name)) match
        case Some(e) =>
          val in = z.getInputStream(e).nn
          try in.readAllBytes().nn finally in.close()
        case None => throw NoSuchFileException(name)

  /** Holds a read reference for the stream's lifetime, so retirement waits for it. */
  def streamEntry(name: String): ZStream[Any, Retired | IOException, Byte] =
    ZStream.unwrapScoped:
      acquire.flatMap: z =>
        ZIO.attemptBlockingIO(Option(z.getEntry(name))).map:
          case Some(e) =>
            ZStream.fromInputStreamScoped(ZIO.fromAutoCloseable(ZIO.attemptBlockingIO(z.getInputStream(e).nn)), 64 * 1024)
          case None =>
            ZStream.fail(NoSuchFileException(name))

object StagedJar:

  final case class Entry(name: String, size: Long)

  /** The jar was retired before this read started; read from the object store instead. */
  final case class Retired(gav: GroupArtifactVersion)

  private final case class State(readers: Int, retired: Boolean)

  /** Fails with `java.util.zip.ZipException` if the file isn't a valid zip. */
  def open(gav: GroupArtifactVersion, meta: JarMeta, file: File, onClose: UIO[Unit]): IO[IOException, StagedJar] =
    for
      zip   <- ZIO.attemptBlockingIO(ZipFile(file))
      state <- Ref.make(State(readers = 0, retired = false))
    yield StagedJar(gav, meta, file, zip, state, onClose)
