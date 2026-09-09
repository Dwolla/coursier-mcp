package cs.mcp.tools

import cats.MonadThrow
import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.server.ToolFunction
import ch.linkyard.mcp.server.ToolFunction.ToolError
import com.melvinlow.json.schema.generic.auto.given
import cs.mcp.CsProcess
import cs.mcp.CsResult
import fs2.io.process.Processes
import io.circe.Decoder
import io.circe.Encoder

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

  def apply[F[_]: MonadThrow](runCs: List[String] => F[CsResult]): ToolFunction[F] =
    ToolFunction.structured[F, ResolveArgs, ResolveResult](info, (args, _) => resolve[F](runCs, args))

  def default[F[_]: {Concurrent, Processes}]: ToolFunction[F] = apply[F](CsProcess.run[F](_))

  private def resolve[F[_]: MonadThrow](runCs: List[String] => F[CsResult], args: ResolveArgs): F[ResolveResult] =
    for
      result <-
        runCs("resolve" :: optionalFlag("--scala-version", args.scalaVersion) ::: args.dependencies)
      _ <- failIfNonZero[F]("resolve", result)
      dependencies <- result.stdout.linesIterator.filter(_.nonEmpty).toList.traverse(parseLine[F])
    yield ResolveResult(dependencies)

  private def parseLine[F[_]: MonadThrow](line: String): F[ResolvedDependency] =
    line.split(":", -1) match
      case Array(organization, name, version, configuration) =>
        ResolvedDependency(organization, name, version, configuration).pure[F]
      case _ =>
        ToolError(List(Content.Text(s"Unexpected cs resolve output line: '$line'"))).raiseError[F, ResolvedDependency]
