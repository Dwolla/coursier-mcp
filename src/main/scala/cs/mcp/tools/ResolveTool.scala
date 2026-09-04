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

final case class ResolveArgs(dependencies: List[String], scalaVersion: Option[String] = None) derives Decoder

final case class ResolvedDependency(
  organization: String,
  name: String,
  version: String,
  configuration: String,
) derives Encoder.AsObject

final case class ResolveResult(dependencies: List[ResolvedDependency]) derives Encoder.AsObject

object ResolveTool:
  private val info = ToolFunction.Info(
    name = "resolve",
    title = None,
    description =
      "Resolve and print the transitive dependencies of one or more dependencies or an application.".some,
    effect = ToolFunction.Effect.ReadOnly,
    isOpenWorld = true,
  )

  def apply[F[_]: Concurrent](runCs: List[String] => F[CsResult]): ToolFunction[F] =
    ToolFunction.structured[F, ResolveArgs, ResolveResult](info, (args, _) => resolve[F](runCs, args))

  def default[F[_]: {Concurrent, Processes}]: ToolFunction[F] = apply[F](CsProcess.run[F])

  private def resolve[F[_]: Concurrent](runCs: List[String] => F[CsResult], args: ResolveArgs): F[ResolveResult] =
    val scalaVersionFlag = args.scalaVersion.toList.flatMap(version => List("--scala-version", version))
    for
      result <- runCs("resolve" :: scalaVersionFlag ::: args.dependencies)
      _ <- failIfNonZero[F](result)
      dependencies <- result.stdout.linesIterator.filter(_.nonEmpty).toList.traverse(parseLine[F])
    yield ResolveResult(dependencies)

  private def parseLine[F[_]: Concurrent](line: String): F[ResolvedDependency] =
    line.split(":", -1) match
      case Array(organization, name, version, configuration) =>
        ResolvedDependency(organization, name, version, configuration).pure[F]
      case _ =>
        ToolError(List(Content.Text(s"Unexpected cs resolve output line: '$line'"))).raiseError[F, ResolvedDependency]

  private def failIfNonZero[F[_]: Concurrent](result: CsResult): F[Unit] =
    if result.exitCode == 0 then ().pure[F]
    else
      ToolError(List(Content.Text(s"cs resolve failed (exit ${result.exitCode}): ${result.stderr}")))
        .raiseError[F, Unit]
