package cs.mcp.tools

import cats.MonadThrow
import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.protocol.Elicitation
import ch.linkyard.mcp.server.McpServer
import ch.linkyard.mcp.server.McpServer.ElicitationField
import ch.linkyard.mcp.server.ToolFunction
import ch.linkyard.mcp.server.ToolFunction.ToolError
import com.melvinlow.json.schema.generic.auto.given
import cs.mcp.CsProcess
import cs.mcp.CsResult
import fs2.io.process.Processes
import io.circe.Decoder
import io.circe.Encoder

final case class JavaHomeArgs(jvm: Option[String] = None) derives Decoder

final case class JavaHomeResult(path: String) derives Encoder.AsObject

object JavaHomeTool:
  private val info = ToolFunction.Info(
    name = "java-home",
    title = None,
    description = "Print the home directory of a particular JVM.".some,
    effect = ToolFunction.Effect.ReadOnly,
    isOpenWorld = true,
  )

  def apply[F[_]: MonadThrow](client: McpServer.Client[F], runCs: List[String] => F[CsResult]): ToolFunction[F] =
    ToolFunction.structured[F, JavaHomeArgs, JavaHomeResult](info, (args, _) => javaHome[F](client, runCs, args))

  def default[F[_]: {Concurrent, Processes}](client: McpServer.Client[F]): ToolFunction[F] =
    apply[F](client, CsProcess.run[F](_))

  private def javaHome[F[_]: MonadThrow](
    client: McpServer.Client[F],
    runCs: List[String] => F[CsResult],
    args: JavaHomeArgs,
  ): F[JavaHomeResult] =
    val realArgv = "java-home" :: optionalFlag("--jvm", args.jvm)
    val offlineArgv = realArgv ::: List("--mode", "offline")

    for
      probe <- runCs(offlineArgv)
      result <-
        if probe.exitCode == 0 then probe.pure[F]
        else confirmAndRun[F](client, runCs, realArgv)
      _ <- failIfNonZero[F]("java-home", result)
    yield JavaHomeResult(result.stdout.trim)

  private def confirmAndRun[F[_]: MonadThrow](
    client: McpServer.Client[F],
    runCs: List[String] => F[CsResult],
    realArgv: List[String],
  ): F[CsResult] =
    for
      confirmation <- client.elicit(
        s"Running `cs ${realArgv.mkString(" ")}` may download and install a JVM. Proceed?",
        ElicitationField.YesNo("confirm", required = true),
      )
      result <-
        if confirmation.action == Elicitation.Action.Accept then runCs(realArgv)
        else
          ToolError(List(Content.Text("User declined to install the JVM")))
            .raiseError[F, CsResult]
    yield result
