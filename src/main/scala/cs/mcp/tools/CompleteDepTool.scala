package cs.mcp.tools

import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.server.ToolFunction
import ch.linkyard.mcp.server.ToolFunction.ToolError
import com.melvinlow.json.schema.JsonSchemaEncoder
import com.melvinlow.json.schema.generic.auto.given
import cs.mcp.CsProcess
import cs.mcp.CsResult
import fs2.io.process.Processes
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json

// scala-json-schema 0.2.0 has no Option instance (see PLAN.md); an optional
// field renders the same as its underlying type, since melvinlow's product
// encoder never populates JSON Schema's "required" array anyway.
private given optionJsonSchemaEncoder[T](using enc: JsonSchemaEncoder[T]): JsonSchemaEncoder[Option[T]] with
  def schema: Json = enc.schema

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

  def apply[F[_]: Concurrent](runCs: List[String] => F[CsResult]): ToolFunction[F] =
    ToolFunction.structured[F, CompleteDepArgs, CompleteDepResult](info, (args, _) => completeDep[F](runCs, args))

  def default[F[_]: {Concurrent, Processes}]: ToolFunction[F] = apply[F](CsProcess.run[F])

  private def completeDep[F[_]: Concurrent](
    runCs: List[String] => F[CsResult],
    args: CompleteDepArgs,
  ): F[CompleteDepResult] =
    val scalaVersionFlag = args.scalaVersion.toList.flatMap(version => List("--scala-version", version))
    for
      result <- runCs("complete-dep" :: scalaVersionFlag ::: List(args.prefix))
      _ <- failIfNonZero[F](result)
    yield CompleteDepResult(result.stdout.linesIterator.filter(_.nonEmpty).toList)

  private def failIfNonZero[F[_]: Concurrent](result: CsResult): F[Unit] =
    if result.exitCode == 0 then ().pure[F]
    else
      ToolError(List(Content.Text(s"cs complete-dep failed (exit ${result.exitCode}): ${result.stderr}")))
        .raiseError[F, Unit]
