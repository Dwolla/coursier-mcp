package cs.mcp

import cats.MonadThrow
import cats.effect.kernel.Concurrent
import cats.effect.kernel.Resource
import cats.syntax.all.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.protocol.Initialize.PartyInfo
import ch.linkyard.mcp.server.CallContext
import ch.linkyard.mcp.server.McpError
import ch.linkyard.mcp.server.McpServer
import ch.linkyard.mcp.server.McpServer.Client
import ch.linkyard.mcp.server.McpServer.ConnectionInfo
import ch.linkyard.mcp.server.McpServer.Session
import ch.linkyard.mcp.server.McpServer.ToolProvider
import ch.linkyard.mcp.server.ToolFunction
import cs.mcp.tools.CompleteDepTool
import cs.mcp.tools.FetchTool
import cs.mcp.tools.JavaHomeTool
import fs2.io.file.Files
import fs2.io.process.Processes
import io.circe.JsonObject

object Server:
  private val serverInfo: PartyInfo = PartyInfo(name = "cs-mcp", version = "0.1.0")

  private def notYetImplemented[F[_]: MonadThrow](name: String): ToolFunction[F] =
    ToolFunction.native[F](
      info = ToolFunction.Info(
        name = name,
        title = None,
        description = None,
        effect = ToolFunction.Effect.ReadOnly,
        isOpenWorld = true,
      ),
      argsSchema = JsonObject.empty,
      f = (_: JsonObject, _: CallContext[F]) =>
        McpError.raise(ErrorCode.InternalError, s"$name is not implemented yet").widen,
    )

  private def allTools[F[_]: {Concurrent, Processes, Files}](client: Client[F]): List[ToolFunction[F]] =
    List(
      notYetImplemented[F]("resolve"),
      FetchTool.default[F],
      CompleteDepTool.default[F],
      JavaHomeTool.default[F](client),
    )

  def apply[F[_]: {Concurrent, Processes, Files}]: McpServer[F] = new McpServer[F]:
    override def initialize(client: Client[F], info: ConnectionInfo[F]): Resource[F, Session[F]] =
      Resource.pure(new Session[F] with ToolProvider[F]:
        override val serverInfo: PartyInfo = Server.serverInfo
        override def instructions: F[Option[String]] = none.pure[F]
        override def tools: F[List[ToolFunction[F]]] = allTools[F](client).pure[F]
      )
