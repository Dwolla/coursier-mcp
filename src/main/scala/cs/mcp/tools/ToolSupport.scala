package cs.mcp.tools

import cats.MonadThrow
import cats.syntax.all.*
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.server.ToolFunction.ToolError
import com.melvinlow.json.schema.JsonSchemaEncoder
import cs.mcp.CsResult
import io.circe.Json

// scala-json-schema 0.2.0 has no Option instance (see PLAN.md); an optional
// field renders the same as its underlying type, since melvinlow's product
// encoder never populates JSON Schema's "required" array anyway.
private[tools] given optionJsonSchemaEncoder[T](using enc: JsonSchemaEncoder[T]): JsonSchemaEncoder[Option[T]] with
  def schema: Json = enc.schema

private[tools] def optionalFlag(name: String, value: Option[String]): List[String] =
  value.toList.flatMap(v => List(name, v))

private[tools] def failIfNonZero[F[_]: MonadThrow](command: String, result: CsResult): F[Unit] =
  if result.exitCode == 0 then ().pure[F]
  else
    ToolError(List(Content.Text(s"cs $command failed (exit ${result.exitCode}): ${result.stderr}")))
      .raiseError[F, Unit]
