# cs-mcp

An [MCP](https://modelcontextprotocol.io/) server that exposes
[coursier](https://get-coursier.io/)'s `cs` CLI as tools, so an MCP client
(e.g. Claude Code) can resolve, fetch, and auto-complete JVM dependency
coordinates on your behalf.

## Requirements

- The [`cs` launcher](https://get-coursier.io/docs/cli-installation) on
  `PATH`. Every tool shells out to it; nothing here works without it.
- JDK 17+ and sbt, for building — Scala 3.9 (this project's compiler) requires JDK 17 or newer.

## Tools

All tools shell out to the real `cs` binary — there's no reimplementation of
coursier's resolution logic. `resolve` and `complete-dep` are pure reads;
`fetch` and `java-home` write to coursier's local cache but never
destroy or overwrite unrelated state, and none of the four requires
confirmation in the common case.

| Tool | `cs` command | Args | Notes |
|---|---|---|---|
| `resolve` | `cs resolve` | `dependencies: List[String]`, `scalaVersion: Option[String]` | Returns the transitive dependency list as structured `organization`/`name`/`version`/`configuration` records. |
| `fetch` | `cs fetch --json-output-file` | `dependencies: List[String]` | Downloads JARs into coursier's local cache and returns their local file paths plus any version-conflict resolutions. Paths are only meaningful because this server runs on the same machine as its client (stdio transport). For pom/BOM-packaged coordinates (no JAR to download), the `file` field is omitted rather than present-with-`null`. |
| `complete-dep` | `cs complete-dep` | `prefix: String`, `scalaVersion: Option[String]` | Auto-completes a partial Maven/sbt-style coordinate. |
| `java-home` | `cs java-home` | `jvm: Option[String]` | Writes to coursier's local cache only when a JVM install is actually needed: probes with `--mode offline` first, so an already-installed/cached JVM never prompts. If that probe fails, it asks the client to confirm via MCP elicitation before running the real command, since `cs java-home` can otherwise silently download a JDK. |

`launch`, `install`, and `setup` are mutating/code-executing and, per the
plan, will require the same elicit-before-running confirmation as
`java-home`'s download path once implemented.

## Building and testing

```bash
sbt test
```

Integration tests (tagged `integration`) shell out to the real `cs` binary
and self-skip via a JUnit assumption if `cs` isn't found on `PATH` — no
separate flag needed to exclude them in an environment without `cs`.

## Running it locally against Claude Code

Build a standalone launcher with `cs bootstrap` and register it as a local
MCP server:

```bash
sbt publishLocal
cs bootstrap "com.dwolla:cs-mcp_3:0.1.0-SNAPSHOT" -o bin/cs-mcp -f
claude mcp add cs-mcp -- /absolute/path/to/cs-mcp/bin/cs-mcp
```

`bin/` is gitignored — it's a generated build artifact. Re-run the two build
commands after any code change; `claude mcp add` only needs to run once,
since Claude Code re-execs the same path each session.

```bash
claude mcp list        # should show cs-mcp as connected
claude mcp get cs-mcp  # inspect the registered command
```

The command above must use an absolute path to `bin/cs-mcp`, since Claude
Code spawns it from whatever directory the current session happens to be
in, not necessarily this repo. By default that registration is local to
this machine; add `--scope project` to check it into `.mcp.json` for the
whole team instead, or `--scope user` to make it available in every project
regardless of working directory.

## Distribution

Not yet published. The intended install path once this is on Maven Central
is:

```bash
cs bootstrap com.dwolla:cs-mcp_3:<version> --standalone -o cs-mcp
```

`--standalone` embeds every dependency jar so the launcher runs with no
network or cache dependency at all — appropriate here since this is
something users download once and keep, rather than the ephemeral/CI use
case the smaller default launcher (which downloads deps on first run) is
meant for. No CI-published launcher binaries are planned for v1: unlike
coursier's own releases, which build launchers so people who don't have
`cs` yet can get it, everything here already requires `cs` to be useful, so
a documented `cs bootstrap` command is proportionate.

## License

MIT (see `build.sbt`).
