package cs.mcp

import cats.effect.kernel.Async
import cats.syntax.all.*
import fs2.io.process.{ProcessBuilder, Processes}
import fs2.text

final case class CsResult(exitCode: Int, stdout: String, stderr: String)

object CsProcess:
  def run[F[_]: Async](args: List[String]): F[CsResult] =
    ProcessBuilder("cs", args).spawn(using Processes.forAsync[F]).use { process =>
      (
        process.stdout.through(text.utf8.decode).compile.string,
        process.stderr.through(text.utf8.decode).compile.string,
        process.exitValue,
      ).mapN((stdout, stderr, exitCode) => CsResult(exitCode, stdout, stderr))
    }
