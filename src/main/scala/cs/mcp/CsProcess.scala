package cs.mcp

import cats.effect.kernel.Concurrent
import cats.effect.kernel.implicits.parallelForGenSpawn
import cats.syntax.all.*
import fs2.io.process.{ProcessBuilder, Processes}
import fs2.text

final case class CsResult(exitCode: Int, stdout: String, stderr: String)

object CsProcess:
  def run[F[_]: {Concurrent, Processes}](args: List[String], command: String = "cs"): F[CsResult] =
    ProcessBuilder(command, args).spawn.use { process =>
      // parMapN, not mapN: stdout/stderr must be drained concurrently. If cs
      // fills one OS pipe's buffer while we're still fully consuming the
      // other one sequentially, cs blocks on the write and this never returns.
      (
        process.stdout.through(text.utf8.decode).compile.string,
        process.stderr.through(text.utf8.decode).compile.string,
        process.exitValue,
      ).parMapN((stdout, stderr, exitCode) => CsResult(exitCode, stdout, stderr))
    }
