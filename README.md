# SysML v2 LSP Server

A standalone, headless LSP server for [SysML v2](https://www.omg.org/spec/SysML/2.0/) and
[KerML](https://www.omg.org/spec/KerML/1.0/), built from the
[OMG SysML v2 Pilot Implementation](https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation)
(XText-based).

## Features

The server provides standard LSP capabilities including:

- Completion
- Go to definition
- Find references
- Hover
- Document and workspace symbols
- Document highlight
- Semantic tokens
- Signature help
- Formatting

## Requirements

**Building:** JDK 21, Maven, curl

**Running:** JDK 21+ (tested up to JDK 25)

## Building

```bash
./build.sh
```

This will:
1. Clone the SysML v2 Pilot Implementation (branch specified in `versions.properties`)
2. Build it with Maven
3. Download XText IDE and LSP4J dependencies from Maven Central
4. Assemble a fat JAR at `sysml-lsp-server.jar`

## Running

```bash
java -jar sysml-lsp-server.jar
```

The server communicates over stdin/stdout using the LSP protocol.

## Versions

Dependency versions are managed in `versions.properties`:

| Property | Description |
|---|---|
| `org.omg.sysml.version` | SysML v2 Pilot Implementation branch/tag |
| `org.eclipse.xtext.version` | XText version |
| `org.eclipse.lsp4j.version` | LSP4J version |

## How It Works

The Pilot Implementation's interactive module ships a fat JAR with all runtime
dependencies except the XText IDE and LSP4J libraries.  The build script layers
those on top, merges `META-INF/services/org.eclipse.xtext.ISetup` registrations
for SysML, KerML, and KerML Expressions, and adds a custom
[`SysMLServerLauncher`](src/SysMLServerLauncher.java) that performs the EMF
EPackage registrations required in headless mode before delegating to the
standard XText `ServerLauncher`.

## Releases

The GitHub Actions [workflow](.github/workflows/release.yml) builds and publishes
`sysml-lsp-server.jar` as a GitHub release.  It triggers on:

- Push to `master` with `[release]` in the commit message
- Manual workflow dispatch

Release tags follow the format `<pilot-version>-YYYYMMDD.<short-hash>`.
## License

See the [SysML v2 Pilot Implementation](https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation)
for upstream licensing.  The custom launcher and build scripts in this repository
are provided under the same terms.
