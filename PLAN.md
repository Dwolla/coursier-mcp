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
  - Read-only, no confirmation: `resolve`, `fetch`, `complete-dep`.
    (Confirmed via `cs complete-dep org.typelevel:cats-effect` — the command
    is `complete-dep`, not `complete`; it's hidden from `cs --help`/
    `--help-full`'s top-level command listing but works directly.)
  - Mutating/code-executing, **require explicit confirmation via MCP
    elicitation before executing**: `launch`, `install`, `setup`, and
    **`java-home` when it would need to download a JVM** (see verified
    facts below — `java-home` moved out of the always-read-only group
    after finding this out empirically). Tool handler calls
    `Client[F].elicit()` showing the exact argv that will run; only
    shells out on explicit confirmation. This is enforced server-side,
    not left to client UI hints (`readOnlyHint`/`destructiveHint` annotations
    are set too, but are non-binding on the client, so don't rely on them
    alone).
- **Transport:** stdio only for v1. No HTTP transport yet (YAGNI).
  `Main` wires `Server[IO]` to `StdioJsonRpcConnection.create[IO]`
  (newline-delimited JSON-RPC, one message per line) — confirmed this
  matches the actual MCP spec's stdio transport
  (https://modelcontextprotocol.io/specification/2026-07-28/basic/transports),
  not just this library's own choice.
- **`fetch`/`resolve` return local filesystem paths, not file content —
  this depends on stdio's same-machine deployment model.** `cs fetch`
  downloads JARs into coursier's local cache and `FetchTool` returns the
  `file` paths into that cache, not the JAR bytes. That's only meaningful
  because stdio MCP servers run as a child process of the client on the
  client's own machine, so server and client share a filesystem — the
  returned paths are directly usable by whoever asked. This assumption
  breaks for a remote/HTTP transport: a remote client can't see the
  server's local cache, so paths alone would be useless. If HTTP
  transport is ever added, `fetch` (and any other tool returning local
  paths) needs to change to embed actual content (`Content.Blob`,
  base64-encoded) instead — which is awkward for JARs, since transitive
  dependency sets can be tens of MB. Revisit this explicitly before
  adding HTTP transport; don't just re-expose the same tool shapes over
  a new transport.

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
- `cs complete-dep`'s `-e`/`--scala-version` flag is not just documentation
  filler — confirmed by reading coursier's own source
  (`coursier.core.Repository.Complete.parse`, in the `coursier` module,
  *not* `coursier-core`) and verifying against the real binary. It's
  required to resolve the sbt-style `org::name` / `org:::name` shorthand
  (double/triple colon = "cross-built artifact, fill in the Scala
  suffix for me"): without `--scala-version`, `org::name` silently
  matches nothing (empty completions, exit 0), and `org::name:`
  (asking for version completions through the shorthand) throws
  `MalformedInput` — exit 1, Java stack trace on stderr, which the
  existing non-zero-exit-surfaces-stderr handling already covers fine.
  With `--scala-version` set, `org::name` completions come back with
  the Scala suffix already stripped (e.g. `cats-effect-kernel`, not
  `cats-effect-kernel_3`).
- `com.melvinlow:scala-json-schema`'s missing `Option` support (noted
  above) needed an actual workaround once `complete-dep` gained an
  optional `scalaVersion` arg: a top-level `private given
  JsonSchemaEncoder[Option[T]]` in `CompleteDepTool.scala` that just
  delegates to `T`'s schema. This is safe because melvinlow's product
  encoder never populates JSON Schema's `required` array regardless, so
  an `Option` field and a plain field already rendered identically
  anyway — the instance only unblocks derivation, it doesn't change
  output. A top-level `private` definition in Scala 3 is
  **package-private, not file-private** — `ResolveTool.scala` (same
  `cs.mcp.tools` package) resolves it automatically without its own
  copy or an import. Don't redeclare it per file; if a tool outside
  `cs.mcp.tools` ever needs it, that's when to move it somewhere
  broader.
- **`cs java-home` is not purely read-only — it can silently download and
  install a full JDK.** Its own `--help` says so ("Install the requested
  JVM if it is not already installed"), and I confirmed it directly: `cs
  java-home --jvm temurin:1.11` started pulling a ~185MB tarball with no
  prompt (killed it partway through; had to clean up the leftover
  `.part`/`.lock` files it left in the real coursier cache). This
  contradicted the plan's original "read-only, no confirmation" grouping
  for `java-home` — moved it to the confirmation-required group (see
  "Tool surface" above). Found a safe way to avoid confirming on every
  call, though: `cs java-home --mode offline [--jvm ...]` fails fast
  (`ArtifactError$NotFound`, exit 1, no network touched) if a download
  would be required, and succeeds immediately with the path if the JVM
  is already resolvable (already cached, or picked up via system
  `JAVA_HOME`/PATH detection). `JavaHomeTool` always tries the offline
  probe first and only elicits confirmation (showing the real argv that
  would run) when that probe fails — so the common case (system JVM
  already present) never prompts, but nothing downloads without explicit
  confirmation.
- `cs resolve`'s plain-text output is one `organization:name:version:configuration`
  line per resolved dependency (e.g.
  `org.scala-lang:scala-library:2.13.14:default`), confirmed by running
  it, including with a forced version conflict (`-V`) — the line always
  has exactly 4 colon-delimited parts, since none of those segments can
  contain a raw colon. Non-zero exit puts the resolution error on
  stderr (e.g. `Resolution error: Error downloading ...`), same shape
  as `fetch`/`complete-dep`'s failure modes.
- `cs resolve`'s `--scala-version` flag affects the same sbt-style `::`
  shorthand as `complete-dep`'s (confirmed: `cs resolve
  org.typelevel::cats-core:2.13.0` resolves against a different
  `scala-library`/`scala3-library_3` version with `--scala-version 3`
  than without it) — so `ResolveTool` got the same optional
  `scalaVersion` arg as `CompleteDepTool`.
- **`fs2.io.process.Process[F]`'s `stdin`/`stdout` each close their
  underlying OS stream once their fs2 `Stream` terminates** (fs2-io's
  `writeOutputStreamCancelable`/`readInputStreamCancelable` default
  `closeAfterUse = true`, and the process wrapper doesn't override it).
  Found this while writing `MainSpec`: compiling `.stdin`/`.stdout` more
  than once (e.g. one `.compile.drain` per outgoing message) closes the
  pipe after the first message, and the second write throws
  `IOException: Stream closed`. The whole conversation on each
  direction must be one continuous `Stream` compiled exactly once —
  use a `Deferred` (or similar) to gate later messages on earlier
  responses within that single Stream, and run the write-side and
  read-side streams concurrently (`parTupled`), rather than making
  separate `.compile` calls per message in either direction.
- **sbt's `Test / fork` defaults to `false`**, meaning tests run inside
  sbt's own JVM — `sys.props("java.class.path")` there is just
  `sbt-launch.jar`, not the project's real runtime classpath. A test
  that spawns `java -cp <that> cs.mcp.Main` as a subprocess (like
  `MainSpec`) gets a `ClassNotFoundException`-killed child process in
  ~0.3s, which surfaces confusingly as `IOException: Stream closed`
  when the test then tries to write to that already-dead process's
  stdin — not an obvious error pointing at the classpath. Fixed with
  `Test / fork := true` in `build.sbt`, which gives the forked test JVM
  the real full runtime classpath.
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
      JavaHomeTool.scala // offline-probes first, elicits confirmation only if a download is needed
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
4. Repeat for the remaining read-only tools. DONE for `complete-dep` and
   `resolve` (both including their `--scala-version` arg) — all four
   read-only tools now have real implementations, no stubs left in
   `Server.scala`.
5. `JavaHomeTool`: DONE, ahead of `LaunchTool`/`InstallTool`/`SetupTool`
   since it turned out to need the same
   probe-then-elicit-then-confirm pattern (see "Tool surface" and
   verified facts above) — `elicit()` is only called when the offline
   probe fails, and a declined confirmation never invokes the real
   (downloading) command. Establishes the pattern
   `LaunchTool`/`InstallTool`/`SetupTool` should follow: tools needing
   `Client[F]` are constructed inside `Server.apply`'s `initialize`
   (where `client` is in scope), not in the plain capability-typeclass
   constructor list.
6. `LaunchTool`/`InstallTool`/`SetupTool`: same elicit-before-mutate
   pattern as `JavaHomeTool`, always confirming (no offline-probe
   equivalent expected here — check each command's actual semantics
   before assuming).

## Workflow

- No GitHub repo set up yet — local git only. Work happens on branches;
  no PRs. Merge to `main` locally once Brian approves the work on a branch.

## Not in scope for v1

- HTTP transport. See the path-vs-content caveat under "Decisions made"
  above — this isn't just a transport swap, it changes `fetch`'s output
  shape.
- `channel`, `list`, `update`, `uninstall`, `search` commands — not
  discussed yet; ask before adding.
