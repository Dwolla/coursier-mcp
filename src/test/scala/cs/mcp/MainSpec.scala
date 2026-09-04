package cs.mcp

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.syntax.all.*
import fs2.Stream
import fs2.io.process.ProcessBuilder
import fs2.text
import munit.CatsEffectSuite

class MainSpec extends CatsEffectSuite:

  private def javaExecutable: String = sys.props("java.home") + "/bin/java"

  test(
    "a real client handshake (initialize, initialized, tools/list) over stdio returns the four tools"
      .tag(IntegrationTest)
  ) {
    val classpath = sys.props("java.class.path")
    val initializeRequest =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test-client","version":"0.0.0"}}}"""
    val initializedNotification = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
    val toolsListRequest = """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""

    ProcessBuilder(javaExecutable, List("-cp", classpath, "cs.mcp.Main")).spawn[IO].use { process =>
      for
        // process.stdin/stdout each close their underlying stream once their fs2 Stream
        // terminates, so the whole conversation on each direction must be one continuous
        // Stream evaluated exactly once -- not one `.compile` call per message.
        initializeReceived <- Deferred[IO, Unit]
        outgoing: Stream[IO, String] =
          Stream.emit(initializeRequest) ++
            Stream.eval(initializeReceived.get).drain ++
            Stream.emits(List(initializedNotification, toolsListRequest))
        write = outgoing.map(_ + "\n").through(text.utf8.encode).through(process.stdin).compile.drain
        incoming = process.stdout.through(text.utf8.decode).through(text.lines).filter(_.nonEmpty)
        read = incoming.take(2).zipWithIndex.evalTap { case (_, index) =>
          initializeReceived.complete(()).void.whenA(index == 0)
        }.map(_._1).compile.toList
        result <- (write, read).parTupled
        (_, responses) = result
      yield responses match
        case List(initializeResponse, toolsListResponse) =>
          assert(
            initializeResponse.contains("\"id\":1") && initializeResponse.contains("cs-mcp"),
            s"expected an initialize response naming the server, got: $initializeResponse",
          )
          for name <- List("resolve", "fetch", "complete-dep", "java-home")
          do
            assert(
              toolsListResponse.contains(s"\"$name\""),
              s"expected tool '$name' in tools/list response, got: $toolsListResponse",
            )
        case other => fail(s"expected exactly two response lines, got: $other")
    }
  }
