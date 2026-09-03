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
  - Read-only, no confirmation: `resolve`, `fetch`, `complete`, `java-home`.
  - Mutating/code-executing, **require explicit confirmation via MCP
    elicitation before executing**: `launch`, `install`, `setup`. Tool
    handler calls `Client[F].elicit()` showing the exact argv that will run;
    only shells out on explicit confirmation. This is enforced server-side,
    not left to client UI hints (`readOnlyHint`/`destructiveHint` annotations
    are set too, but are non-binding on the client, so don't rely on them
    alone).
- **Transport:** stdio only for v1. No HTTP transport yet (YAGNI).

## Open unknowns — verify, don't assume

- `scala-effect-mcp`'s actual `crossScalaVersions`. README's example jar
  path shows `scala-3.8.1`; I could not confirm 2.13/2.12 cross-publishing
  before handoff. Check Maven Central / Scaladex for the real published
  artifact coordinates before locking `build.sbt`'s Scala version. If
  Scala 3-only, that overrides the usual "prefer 2.13 for apps" default.
- Confirmed: `cs fetch --json-output-file <path>` produces structured JSON
  (not just line-oriented text) — good, use it instead of parsing stdout.
  NOT confirmed whether `cs resolve` supports the same flag; check when
  building that tool. If it doesn't, `fetch` with a resolve-only flag
  combination may be a better structured-output source than `resolve`
  itself — worth checking coursier's docs before assuming plain-text
  parsing is required.

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
      CompleteTool.scala
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

1. `CsProcessSpec`: `CsProcess.run(List("--version"))` returns exit code 0
   and stdout containing a version string, using the real `cs` binary
   (integration-tagged; skip if `cs` isn't on PATH).
2. Protocol-layer test: `Server` responds to `tools/list` with the four
   read-only tools registered — no subprocess involved.
3. `ResolveTool` (or `FetchTool`, whichever gets a confirmed JSON flag
   first): unit test against a fake `CsProcess`, then one real integration
   test.
4. Repeat for the remaining read-only tools.
5. `LaunchTool`: unit test that `elicit()` is called before any process
   runs, and that a declined confirmation never invokes `CsProcess`.

## Not in scope for v1

- HTTP transport.
- `channel`, `list`, `update`, `uninstall`, `search` commands — not
  discussed yet; ask before adding.
