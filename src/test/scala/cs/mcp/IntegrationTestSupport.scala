package cs.mcp

import cats.effect.*
import cats.effect.std.*
import ch.linkyard.mcp.protocol.LoggingLevel
import ch.linkyard.mcp.protocol.Meta
import ch.linkyard.mcp.server.CallContext
import fs2.io.file.*
import io.circe.Json
import mouse.all.*
import munit.Assertions.munitPrint
import munit.internal.console.StackTraces
import org.junit.AssumptionViolatedException

object IntegrationTest extends munit.Tag("integration")

def csOnPath: IO[Boolean] =
  Env[IO].get("PATH")
    .liftOptionT
    .filterF { path =>
      fs2.Stream.emits(path.split(java.io.File.pathSeparatorChar))
        .map(Path(_) / "cs")
        .evalMap(Files[IO].isExecutable)
        .exists(identity)
        .compile
        .lastOrError
    }
    .isDefined

def assumeIO(cond: IO[Boolean],
             clue: => Any = "assumption failed"): IO[Unit] =
  cond.flatMap {
    IO.raiseUnless(_) {
      StackTraces.dropInside(new AssumptionViolatedException(munitPrint(clue)))
    }
  }

val noopContext: CallContext[IO] = new CallContext[IO]:
  override val meta: Meta = Meta.empty
  override def reportProgress(progress: Double, total: Option[Double], message: Option[String]): IO[Unit] = IO.unit
  override def log(level: LoggingLevel, data: Json): IO[Unit] = IO.unit
