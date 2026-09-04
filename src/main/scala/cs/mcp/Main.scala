package cs.mcp

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import ch.linkyard.mcp.jsonrpc2.transport.StdioJsonRpcConnection
import ch.linkyard.mcp.server.McpServer.*

object Main extends IOApp:
  override def run(args: List[String]): IO[ExitCode] =
    val connection = StdioJsonRpcConnection.create[IO]
    Server[IO].start(connection, e => IO(e.printStackTrace()))
      .use(_ => IO.never)
      .as(ExitCode.Success)
