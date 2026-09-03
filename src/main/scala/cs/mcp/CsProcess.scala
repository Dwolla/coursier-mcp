package cs.mcp

import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import fs2.io.process.{ProcessBuilder, Processes}
import fs2.text

final case class CsResult(exitCode: Int, stdout: String, stderr: String)

object CsProcess:
  def run[F[_]: Concurrent: Processes](args: List[String]): F[CsResult] =
    ProcessBuilder("cs", args).spawn.use { process =>
      (
        process.stdout.through(text.utf8.decode).compile.string,
        process.stderr.through(text.utf8.decode).compile.string,
        process.exitValue,
      ).mapN((stdout, stderr, exitCode) => CsResult(exitCode, stdout, stderr))
    }
