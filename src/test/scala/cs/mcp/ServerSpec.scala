package cs.mcp

import cats.effect.IO
import cats.effect.kernel.Resource
import ch.linkyard.mcp.jsonrpc2.Authentication
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.Initialize.ClientCapabilities
import ch.linkyard.mcp.protocol.Initialize.PartyInfo
import ch.linkyard.mcp.server.LowlevelMcpServer
import ch.linkyard.mcp.server.LowlevelMcpServer.Communication
import ch.linkyard.mcp.server.McpError
import ch.linkyard.mcp.server.McpServer
import io.circe.Decoder
import munit.CatsEffectSuite

class ServerSpec extends CatsEffectSuite:

  private val noopComms: Communication[IO] = new Communication[IO]:
    override def request(request: ServerRequest)(using Decoder[request.Response])
      : IO[Either[McpError, request.Response]] =
      IO.raiseError(new RuntimeException(s"unexpected server->client request: $request"))
    override def notify(notification: ServerNotification): IO[Unit] = IO.unit

  private val connectionInfo = JsonRpcConnection.Info.Stdio(Map.empty)

  private def running: Resource[IO, LowlevelMcpServer[IO]] =
    Server[IO].lowlevelFactory(connectionInfo)(noopComms)

  test("tools/list returns the four read-only tools") {
    running.use { lowlevel =>
      val initialize = Initialize(
        capabilities = ClientCapabilities(None, None, None, None),
        clientInfo = PartyInfo("test-client", "0.0.0"),
      )
      for
        _ <- lowlevel.handleRequest(initialize, RequestId.IdNumber(1), Authentication.Anonymous)
        _ <- lowlevel.handleNotification(Initialized(), Authentication.Anonymous)
        response <- lowlevel.handleRequest(Tool.ListTools(None), RequestId.IdNumber(2), Authentication.Anonymous)
      yield response match
        case Tool.ListTools.Response(tools, _, _) =>
          assertEquals(tools.map(_.name).toSet, Set("resolve", "fetch", "complete-dep", "java-home"))
        case other => fail(s"expected Tool.ListTools.Response, got $other")
    }
  }
