package cs.mcp.tools

import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import ch.linkyard.mcp.server.ToolFunction
import com.melvinlow.json.schema.generic.auto.given
import cs.mcp.CsProcess
import cs.mcp.CsResult
import fs2.io.file.Files
import fs2.io.process.Processes
import io.circe.Decoder
import io.circe.Encoder
import io.circe.parser.decode

final case class FetchArgs(dependencies: List[String]) derives Decoder

final case class FetchDependency(
  coord: String,
  file: Option[String],
  directDependencies: List[String],
  dependencies: List[String],
) derives Decoder,
      Encoder.AsObject

final case class ConflictResolution(requested: String, resolved: String) derives Encoder.AsObject

final case class FetchResult(
  dependencies: List[FetchDependency],
  conflictResolution: List[ConflictResolution],
) derives Encoder.AsObject

private final case class RawFetchOutput(
  dependencies: List[FetchDependency],
  conflict_resolution: Map[String, String],
) derives Decoder

object FetchTool:
  private val info = ToolFunction.Info(
    name = "fetch",
    title = None,
    description = "Transitively fetch the JARs of one or more dependencies or an application.".some,
    effect = ToolFunction.Effect.ReadOnly,
    isOpenWorld = true,
  )

  def apply[F[_]: {Concurrent, Files}](runCs: List[String] => F[CsResult]): ToolFunction[F] =
    ToolFunction.structured[F, FetchArgs, FetchResult](info, (args, _) => fetch[F](runCs, args))

  def default[F[_]: {Concurrent, Processes, Files}]: ToolFunction[F] = apply[F](CsProcess.run[F](_))

  private def fetch[F[_]: {Concurrent, Files}](runCs: List[String] => F[CsResult], args: FetchArgs): F[FetchResult] =
    Files[F].tempFile.use { path =>
      for
        result <- runCs("fetch" :: "--json-output-file" :: path.toString :: args.dependencies)
        _ <- failIfNonZero[F]("fetch", result)
        json <- Files[F].readUtf8(path).compile.string
        raw <- decode[RawFetchOutput](json).liftTo[F]
      yield FetchResult(
        dependencies = raw.dependencies,
        conflictResolution = raw.conflict_resolution.toList.map { case (requested, resolved) =>
          ConflictResolution(requested, resolved)
        },
      )
    }
