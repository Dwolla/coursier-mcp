# cs MCP Server — Plan

MCP server exposing coursier's `cs` CLI as tools, in Scala. This doc is the
decision record from design discussion — read this before re-deriving any of
these choices.

## Decisions made (don't relitigate without new evidence)

- **MCP library:** `linkyard/scala-effect-mcp` (fs2 + cats-effect, MIT).
  stdio transport module only for v1 (`ch.linkyard.mcp:jsonrpc2-stdio` +
  `ch.linkyard.mcp:mcp-server`). Chosen over `indoorvivants/mcp` (viable
  fallback, more minimal/sync), `windymelt/mcp-scala` (Scala.js-only, ALPHA),
  `fast-mcp-scala` (ZIO, off-stack). Risk to flag: young library, 13 GitHub
  stars — expect to file upstream issues.
- **Execution model: shell out to the `cs` binary for everything**, via
  `fs2.io.process`. Considered using `io.get-coursier:coursier` /
  `coursier-cache` / `coursier-cats-interop` directly for `resolve`/`fetch`/
  `complete` (real typed APIs exist: `coursier.Resolve()`, `coursier.Fetch()`,
  `coursier.Complete()`, with a `FileCache[cats.effect.IO]` via
  cats-interop). Rejected because `launch`/`install`/`setup`/`java-home`
  have no embeddable library equivalent, so that path means running two
  independent coursier implementations side by side (config drift risk,
  violates one-source-of-truth). Uniform subprocess wrapping stays correct
  automatically as the `cs` binary itself is updated.
- **Tool surface:** curated typed tool per command, not one generic
  "run cs" tool.
  - Read-only, no confirmation: `resolve`, `fetch`, `complete-dep`, `java-home`.
    (Confirmed via `cs complete-dep org.typelevel:cats-effect` — the command
    is `complete-dep`, not `complete`; it's hidden from `cs --help`/
    `--help-full`'s top-level command listing but works directly.)
  - Mutating/code-executing, **require explicit confirmation via MCP
    elicitation before executing**: `launch`, `install`, `setup`. Tool
    handler calls `Client[F].elicit()` showing the exact argv that will run;
    only shells out on explicit confirmation. This is enforced server-side,
    not left to client UI hints (`readOnlyHint`/`destructiveHint` annotations
    are set too, but are non-binding on the client, so don't rely on them
    alone).
- **Transport:** stdio only for v1. No HTTP transport yet (YAGNI).

## Verified facts (confirmed against installed `cs` 2.1.13 and Maven Central)

- `scala-effect-mcp` is **Scala 3-only**, published at version `0.3.5`.
  `cs resolve "ch.linkyard.mcp:mcp-server_3:latest.release"` succeeds;
  `_2.13` and `_2.12` both 404 on Maven Central. This overrides the usual
  "prefer 2.13 for apps" default — `build.sbt` targets Scala 3 (3.8.x line,
  per the transitively-pulled `scala-library-3.8.3`).
- `cs fetch --json-output-file <path>` produces structured JSON (hidden
  flag, visible only via `cs fetch --help-full`) — use it instead of
  parsing stdout.
- `cs resolve` does **not** support `--json-output-file`, even as a hidden
  option (checked `cs resolve --help-full`). No resolve-only flag on
  `fetch` was found either (no `--resolve-only`/`--no-fetch` equivalent).
  Decide explicitly before slice 3: either `ResolveTool` does plain-text
  parsing, or `FetchTool` becomes the JSON-structured tool and `resolve`
  stays text-based.
- `cs --version` does **not** print a version string — it exits 0 but
  prints the full usage/help text. The actual version command is
  `cs version` (prints e.g. `2.1.13`). TDD slice 1 below is updated to use
  `List("version")` instead of `List("--version")`.
- The top-level `cs --help`/`--help-full` command listing does not include
  `complete-dep` (or any `complete*` command) — it's undocumented at that
  level but works fine when invoked directly, e.g.
  `cs complete-dep org.typelevel:cats-effect`.
- `cs fetch --json-output-file`'s actual JSON shape (confirmed by running
  it):
  ```json
  {
    "conflict_resolution": {"requested:coord": "resolved:coord"},
    "dependencies": [
      {"coord": "...", "file": "...", "directDependencies": [...], "dependencies": [...]}
    ],
    "version": "0.1.0"
  }
  ```
  `conflict_resolution` is only present/non-empty when a version conflict
  was resolved.
- `com.melvinlow:scala-json-schema` 0.2.0 (the `JsonSchemaEncoder` used by
  `ToolFunction.structured`) only ships instances for `String`/`Int`/
  `Long`/`Double`/`Float`/`Boolean`/`Null`/`List`/`Array`, plus
  auto-derivation for product/sum types built from those — **no `Option`
  or `Map` instance**. Tool args/result case classes must avoid `Option`
  and `Map` fields (e.g. `FetchTool` remaps `cs`'s raw
  `Map[String, String]` conflict_resolution into a `List[ConflictResolution]`
  case class). Check this again if a future tool's natural shape wants
  `Option`/`Map`.
- `io.circe`'s core `Decoder`/`Encoder.AsObject` companions natively
  support `derives Decoder, Encoder.AsObject` on Scala 3 case classes
  (confirmed via `circe-core_3` sources: `object Decoder extends
  DecoderDerivation`, `object Encoder.AsObject extends ...
  EncoderDerivation`) — no need to pull in `circe-generic`'s
  `semiauto.deriveDecoder`/`deriveEncoder`, even though circe-generic is
  on the classpath transitively via `mcp-server`.

## Project layout (single sbt module — no multi-module split needed here)

```
cs-mcp/
  build.sbt
  project/
  src/main/scala/cs/mcp/
    CsProcess.scala      // shells out to `cs`, returns exit code + stdout/stderr
    tools/
      ResolveTool.scala
      FetchTool.scala
      CompleteDepTool.scala
      JavaHomeTool.scala
      LaunchTool.scala   // elicits confirmation, then delegates to CsProcess
      InstallTool.scala
      SetupTool.scala
    Server.scala          // wires ToolProvider, stdio transport, main entrypoint
  src/test/scala/cs/mcp/
    CsProcessSpec.scala
    tools/...
```

## First TDD slices, in order

1. `CsProcessSpec`: `CsProcess.run(List("version"))` returns exit code 0
   and stdout containing a version string, using the real `cs` binary
   (integration-tagged; skip if `cs` isn't on PATH).
2. Protocol-layer test: `Server` responds to `tools/list` with the four
   read-only tools registered — no subprocess involved.
3. `FetchTool` (has the confirmed `--json-output-file` flag): unit test
   against a fake `CsProcess`, then one real integration test. DONE —
   `FetchTool.apply[F: {Concurrent, Files}](runCs: List[String] => F[CsResult])`
   takes the `cs` invocation as an injected function (fake in unit tests,
   `CsProcess.run[F]` in `FetchTool.default`), which is how `ResolveTool`
   and the rest should take their `CsProcess` dependency too, so their
   JSON/text-parsing and error-surfacing logic stays unit-testable without
   shelling out. Non-zero exit surfaces `cs`'s stderr via
   `ToolFunction.ToolError` so the client sees the real failure. Next:
   `ResolveTool`, which parses plain-text output since it has no JSON flag.
4. Repeat for the remaining read-only tools.
5. `LaunchTool`: unit test that `elicit()` is called before any process
   runs, and that a declined confirmation never invokes `CsProcess`.

## Workflow

- No GitHub repo set up yet — local git only. Work happens on branches;
  no PRs. Merge to `main` locally once Brian approves the work on a branch.

## Not in scope for v1

- HTTP transport.
- `channel`, `list`, `update`, `uninstall`, `search` commands — not
  discussed yet; ask before adding.
