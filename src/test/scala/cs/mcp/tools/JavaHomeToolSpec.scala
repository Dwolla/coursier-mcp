package cs.mcp.tools

import cats.effect.IO
import cats.effect.kernel.Ref
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.protocol.Elicitation
import ch.linkyard.mcp.protocol.Initialize.ClientCapabilities
import ch.linkyard.mcp.protocol.Initialize.PartyInfo
import ch.linkyard.mcp.protocol.LoggingLevel
import ch.linkyard.mcp.protocol.Meta
import ch.linkyard.mcp.protocol.Roots
import ch.linkyard.mcp.protocol.Sampling
import ch.linkyard.mcp.protocol.Tool.CallTool
import ch.linkyard.mcp.server.CallContext
import ch.linkyard.mcp.server.McpServer
import ch.linkyard.mcp.server.ToolFunction
import cs.mcp.CsResult
import cs.mcp.IntegrationTest
import cs.mcp.assumeIO
import cs.mcp.csOnPath
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

class JavaHomeToolSpec extends CatsEffectSuite:

  private val noopContext: CallContext[IO] = new CallContext[IO]:
    override val meta: Meta = Meta.empty
    override def reportProgress(progress: Double, total: Option[Double], message: Option[String]): IO[Unit] = IO.unit
    override def log(level: LoggingLevel, data: Json): IO[Unit] = IO.unit

  private def fakeClient(response: Elicitation.Create.Response): McpServer.Client[IO] =
    new McpServer.Client[IO]:
      override val clientInfo: PartyInfo = PartyInfo("test-client", "0.0.0")
      override val capabilities: ClientCapabilities = ClientCapabilities(None, None, None, None)
      override def ping: IO[Unit] = IO.raiseError(new RuntimeException("unexpected ping"))
      override def log(level: LoggingLevel, logger: Option[String], message: String): IO[Unit] =
        IO.raiseError(new RuntimeException("unexpected log"))
      override def log(level: LoggingLevel, logger: Option[String], data: Json): IO[Unit] =
        IO.raiseError(new RuntimeException("unexpected log"))
      override def elicit(
        message: String,
        requestedSchema: ch.linkyard.mcp.protocol.JsonSchema,
        _meta: Meta,
      ): IO[Elicitation.Create.Response] = IO.pure(response)
      override def listRoots: IO[Roots.ListRoots.Response] =
        IO.raiseError(new RuntimeException("unexpected listRoots"))
      override def sample(
        messages: List[Sampling.Message],
        maxTokens: Int,
        modelPreferences: Option[Sampling.ModelPreferences],
        systemPrompt: Option[String],
        temperature: Option[Double],
        includeContext: Option[String],
        stopSequences: Option[List[String]],
        metadata: Option[JsonObject],
        _meta: Meta,
      ): IO[Sampling.CreateMessage.Response] = IO.raiseError(new RuntimeException("unexpected sample"))

  private val refusingClient: McpServer.Client[IO] = new McpServer.Client[IO]:
    override val clientInfo: PartyInfo = PartyInfo("test-client", "0.0.0")
    override val capabilities: ClientCapabilities = ClientCapabilities(None, None, None, None)
    override def ping: IO[Unit] = IO.raiseError(new RuntimeException("unexpected ping"))
    override def log(level: LoggingLevel, logger: Option[String], message: String): IO[Unit] =
      IO.raiseError(new RuntimeException("unexpected log"))
    override def log(level: LoggingLevel, logger: Option[String], data: Json): IO[Unit] =
      IO.raiseError(new RuntimeException("unexpected log"))
    override def elicit(
      message: String,
      requestedSchema: ch.linkyard.mcp.protocol.JsonSchema,
      _meta: Meta,
    ): IO[Elicitation.Create.Response] = IO.raiseError(new RuntimeException("unexpected elicit"))
    override def listRoots: IO[Roots.ListRoots.Response] =
      IO.raiseError(new RuntimeException("unexpected listRoots"))
    override def sample(
      messages: List[Sampling.Message],
      maxTokens: Int,
      modelPreferences: Option[Sampling.ModelPreferences],
      systemPrompt: Option[String],
      temperature: Option[Double],
      includeContext: Option[String],
      stopSequences: Option[List[String]],
      metadata: Option[JsonObject],
      _meta: Meta,
    ): IO[Sampling.CreateMessage.Response] = IO.raiseError(new RuntimeException("unexpected sample"))

  test("returns the JVM home path directly when already available, without eliciting") {
    for
      calls <- Ref.of[IO, List[List[String]]](Nil)
      fakeRunCs: (List[String] => IO[CsResult]) = argv =>
        calls.update(_ :+ argv).as(CsResult(0, "/usr/lib/jvm/temurin-17\n", ""))
      tool = JavaHomeTool[IO](refusingClient, fakeRunCs)
      response <- tool.apply(JsonObject.empty, noopContext)
      recordedCalls <- calls.get
    yield
      assertEquals(recordedCalls, List(List("java-home", "--mode", "offline")))
      response match
        case CallTool.Response.Success(_, Some(structured), _) =>
          assertEquals(structured("path"), Some("/usr/lib/jvm/temurin-17".asJson))
        case other => fail(s"expected a successful structured response, got $other")
  }

  test("elicits confirmation and never downloads when the user declines") {
    for
      calls <- Ref.of[IO, List[List[String]]](Nil)
      fakeRunCs: (List[String] => IO[CsResult]) = argv =>
        calls.update(_ :+ argv).as {
          if argv.contains("offline") then CsResult(1, "", "not found locally")
          else CsResult(0, "/should/not/be/used\n", "")
        }
      tool = JavaHomeTool[IO](fakeClient(Elicitation.Create.Response(Elicitation.Action.Decline, None)), fakeRunCs)
      response <- tool.apply(JsonObject("jvm" -> "temurin:11".asJson), noopContext)
      recordedCalls <- calls.get
    yield
      assertEquals(recordedCalls, List(List("java-home", "--jvm", "temurin:11", "--mode", "offline")))
      response match
        case CallTool.Response.Error(content, _) =>
          assert(
            content.exists {
              case Content.Text(text, _, _) => text.toLowerCase.contains("declined")
              case _                        => false
            },
            s"expected a decline message in error content, got $content",
          )
        case other => fail(s"expected an error response, got $other")
  }

  test("runs the real command after explicit confirmation when the offline probe fails") {
    for
      calls <- Ref.of[IO, List[List[String]]](Nil)
      fakeRunCs: (List[String] => IO[CsResult]) = argv =>
        calls.update(_ :+ argv).as {
          if argv.contains("offline") then CsResult(1, "", "not found locally")
          else CsResult(0, "/usr/lib/jvm/temurin-11\n", "")
        }
      tool = JavaHomeTool[IO](fakeClient(Elicitation.Create.Response(Elicitation.Action.Accept, None)), fakeRunCs)
      response <- tool.apply(JsonObject("jvm" -> "temurin:11".asJson), noopContext)
      recordedCalls <- calls.get
    yield
      assertEquals(
        recordedCalls,
        List(
          List("java-home", "--jvm", "temurin:11", "--mode", "offline"),
          List("java-home", "--jvm", "temurin:11"),
        ),
      )
      response match
        case CallTool.Response.Success(_, Some(structured), _) =>
          assertEquals(structured("path"), Some("/usr/lib/jvm/temurin-11".asJson))
        case other => fail(s"expected a successful structured response, got $other")
  }

  test(
    "java-home resolves the system JVM via the offline probe, no network needed".tag(IntegrationTest)
  ) {
    assumeIO(csOnPath, "cs is not on PATH") >> {
      val tool = JavaHomeTool.default[IO](refusingClient)

      tool.apply(JsonObject.empty, noopContext).map {
        case CallTool.Response.Success(_, Some(structured), _) =>
          assert(structured("path").flatMap(_.asString).exists(_.nonEmpty), s"expected a non-empty path, got $structured")
        case other => fail(s"expected a successful structured response, got $other")
      }
    }
  }

  test("JavaHomeTool.info.effect is Additive(idempotent = true)") {
    val tool = JavaHomeTool.default[IO](refusingClient)
    assertEquals(tool.info.effect, ToolFunction.Effect.Additive(idempotent = true))
  }
