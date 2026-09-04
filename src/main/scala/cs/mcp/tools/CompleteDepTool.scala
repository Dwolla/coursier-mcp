package cs.mcp.tools

import cats.MonadThrow
import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import ch.linkyard.mcp.server.ToolFunction
import com.melvinlow.json.schema.generic.auto.given
import cs.mcp.CsProcess
import cs.mcp.CsResult
import fs2.io.process.Processes
import io.circe.Decoder
import io.circe.Encoder

final case class CompleteDepArgs(prefix: String, scalaVersion: Option[String] = None) derives Decoder

final case class CompleteDepResult(completions: List[String]) derives Encoder.AsObject

object CompleteDepTool:
  private val info = ToolFunction.Info(
    name = "complete-dep",
    title = None,
    description = "Auto-complete Maven coordinates.".some,
    effect = ToolFunction.Effect.ReadOnly,
    isOpenWorld = true,
  )

  def apply[F[_]: MonadThrow](runCs: List[String] => F[CsResult]): ToolFunction[F] =
    ToolFunction.structured[F, CompleteDepArgs, CompleteDepResult](info, (args, _) => completeDep[F](runCs, args))

  def default[F[_]: {Concurrent, Processes}]: ToolFunction[F] = apply[F](CsProcess.run[F](_))

  private def completeDep[F[_]: MonadThrow](
    runCs: List[String] => F[CsResult],
    args: CompleteDepArgs,
  ): F[CompleteDepResult] =
    for
      result <- runCs("complete-dep" :: optionalFlag("--scala-version", args.scalaVersion) ::: List(args.prefix))
      _ <- failIfNonZero[F]("complete-dep", result)
    yield CompleteDepResult(result.stdout.linesIterator.filter(_.nonEmpty).toList)
