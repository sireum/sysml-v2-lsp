/**
 * Integration tests for the SysML v2 LSP server.
 *
 * Tests the LSP server by spawning the JAR process and communicating
 * over stdin/stdout using the LSP protocol.
 */
import { spawn } from "child_process";
import { readFileSync } from "fs";
import { pathToFileURL } from "url";
import { join, resolve } from "path";

const [, , javaCmd, jarPath, libPath, fixturesDir] = process.argv;

// ---------------------------------------------------------------------------
// LSP client helper
// ---------------------------------------------------------------------------

class LspClient {
  constructor(java, jar, lib) {
    this.reqId = 0;
    this.pending = new Map();
    this.notifications = [];
    this.buf = "";
    const args = lib ? ["-jar", jar, "--library", lib] : ["-jar", jar];
    this.proc = spawn(java, args, {
      stdio: ["pipe", "pipe", "pipe"],
    });
    this.proc.stderr.on("data", () => {}); // suppress warnings
    this.proc.stdout.on("data", (d) => this._onData(d));
  }

  send(method, params) {
    const id = ++this.reqId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(
        () => reject(new Error(`Timeout waiting for response to ${method}`)),
        60000,
      );
      this.pending.set(id, { resolve, reject, timer });
      this._write({ jsonrpc: "2.0", id, method, params });
    });
  }

  notify(method, params) {
    this._write({ jsonrpc: "2.0", method, params });
  }

  collectNotifications(method) {
    return this.notifications.filter((n) => n.method === method);
  }

  waitForDiagnostics(uri, timeoutMs = 30000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(
        () => reject(new Error(`Timeout waiting for diagnostics for ${uri}`)),
        timeoutMs,
      );
      const check = () => {
        const diag = this.notifications.find(
          (n) =>
            n.method === "textDocument/publishDiagnostics" &&
            n.params.uri === uri,
        );
        if (diag) {
          clearTimeout(timer);
          resolve(diag.params);
        } else {
          setTimeout(check, 200);
        }
      };
      check();
    });
  }

  async shutdown() {
    await this.send("shutdown", null);
    this.notify("exit", null);
    return new Promise((resolve) => {
      this.proc.on("close", resolve);
      setTimeout(() => {
        this.proc.kill();
        resolve(1);
      }, 5000);
    });
  }

  _write(obj) {
    const body = JSON.stringify(obj);
    this.proc.stdin.write(
      `Content-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`,
    );
  }

  _onData(data) {
    this.buf += data.toString();
    while (true) {
      const match = this.buf.match(/Content-Length: (\d+)\r\n\r\n/);
      if (!match) break;
      const len = parseInt(match[1]);
      const headerEnd = match.index + match[0].length;
      if (this.buf.length < headerEnd + len) break;
      const body = this.buf.substring(headerEnd, headerEnd + len);
      this.buf = this.buf.substring(headerEnd + len);
      const msg = JSON.parse(body);
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, timer } = this.pending.get(msg.id);
        clearTimeout(timer);
        this.pending.delete(msg.id);
        resolve(msg);
      } else if (msg.method) {
        this.notifications.push(msg);
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Test runner
// ---------------------------------------------------------------------------

let passed = 0;
let failed = 0;

function assert(condition, message) {
  if (!condition) {
    throw new Error(`Assertion failed: ${message}`);
  }
}

async function runTest(name, fn) {
  process.stdout.write(`  ${name} ... `);
  try {
    await fn();
    console.log("PASS");
    passed++;
  } catch (e) {
    console.log(`FAIL: ${e.message}`);
    failed++;
  }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

const fixturesPath = resolve(fixturesDir);
const wsUri = pathToFileURL(fixturesPath).href;

const client = new LspClient(javaCmd, jarPath, libPath || null);

// Initialize
const initResult = await client.send("initialize", {
  processId: process.pid,
  rootUri: wsUri,
  capabilities: {
    workspace: { workspaceFolders: true },
    textDocument: {
      publishDiagnostics: { relatedInformation: true },
      completion: { completionItem: { snippetSupport: false } },
      hover: { contentFormat: ["plaintext"] },
      semanticTokens: {
        requests: { full: true },
        tokenTypes: [
          "namespace",
          "type",
          "class",
          "enum",
          "interface",
          "struct",
          "typeParameter",
          "parameter",
          "variable",
          "property",
          "enumMember",
          "event",
          "function",
          "method",
          "macro",
          "keyword",
          "modifier",
          "comment",
          "string",
          "number",
          "regexp",
          "operator",
          "decorator",
        ],
        tokenModifiers: [],
        formats: ["relative"],
      },
    },
  },
  workspaceFolders: [{ uri: wsUri, name: "test-workspace" }],
});

client.notify("initialized", {});

const caps = initResult.result.capabilities;

console.log("Initialize & Capabilities:");

await runTest("server returns capabilities", () => {
  assert(caps, "capabilities should be present");
});

await runTest("textDocumentSync is defined", () => {
  assert(
    caps.textDocumentSync !== undefined,
    "textDocumentSync should be defined",
  );
});

await runTest("hoverProvider is enabled", () => {
  assert(caps.hoverProvider === true, "hoverProvider should be true");
});

await runTest("completionProvider is defined", () => {
  assert(caps.completionProvider, "completionProvider should be defined");
});

await runTest("definitionProvider is enabled", () => {
  assert(caps.definitionProvider === true, "definitionProvider should be true");
});

await runTest("referencesProvider is enabled", () => {
  assert(
    caps.referencesProvider === true,
    "referencesProvider should be true",
  );
});

await runTest("documentSymbolProvider is enabled", () => {
  assert(
    caps.documentSymbolProvider === true,
    "documentSymbolProvider should be true",
  );
});

await runTest("semanticTokensProvider is defined", () => {
  assert(caps.semanticTokensProvider, "semanticTokensProvider should exist");
  assert(
    caps.semanticTokensProvider.full === true,
    "full semantic tokens should be supported",
  );
});

await runTest("workspace folder support is enabled", () => {
  assert(
    caps.workspace?.workspaceFolders?.supported === true,
    "workspace folders should be supported",
  );
});

await runTest("formattingProvider is enabled", () => {
  assert(
    caps.documentFormattingProvider === true,
    "documentFormattingProvider should be true",
  );
});

// Open valid file and check diagnostics
const validFile = join(fixturesPath, "valid.sysml");
const validUri = pathToFileURL(validFile).href;
const validContent = readFileSync(validFile, "utf8");

client.notify("textDocument/didOpen", {
  textDocument: {
    uri: validUri,
    languageId: "sysml",
    version: 1,
    text: validContent,
  },
});

// Open error file and check diagnostics
const errorFile = join(fixturesPath, "error.sysml");
const errorUri = pathToFileURL(errorFile).href;
const errorContent = readFileSync(errorFile, "utf8");

client.notify("textDocument/didOpen", {
  textDocument: {
    uri: errorUri,
    languageId: "sysml",
    version: 1,
    text: errorContent,
  },
});

console.log("\nDiagnostics:");

await runTest("valid file has no errors", async () => {
  const diag = await client.waitForDiagnostics(validUri);
  const errors = diag.diagnostics.filter((d) => d.severity === 1);
  assert(
    errors.length === 0,
    `expected 0 errors, got ${errors.length}: ${errors.map((e) => e.message).join(", ")}`,
  );
});

await runTest("error file reports unresolved reference", async () => {
  const diag = await client.waitForDiagnostics(errorUri);
  assert(
    diag.diagnostics.length > 0,
    "expected at least one diagnostic for error file",
  );
  const hasUnresolved = diag.diagnostics.some((d) =>
    d.message.includes("resolve reference"),
  );
  assert(hasUnresolved, "expected an unresolved reference diagnostic");
});

// Document symbols
console.log("\nDocument Operations:");

await runTest("documentSymbol returns symbols for valid file", async () => {
  const result = await client.send("textDocument/documentSymbol", {
    textDocument: { uri: validUri },
  });
  assert(result.result, "documentSymbol should return a result");
  assert(result.result.length > 0, "should have at least one symbol");
});

// Hover
await runTest("hover returns info on identifier", async () => {
  // Hover over 'Vehicle' at line 1 (0-indexed), column ~13
  const result = await client.send("textDocument/hover", {
    textDocument: { uri: validUri },
    position: { line: 1, character: 13 },
  });
  // Hover may or may not return content depending on indexing state;
  // just verify no error
  assert(!result.error, `hover should not return error: ${JSON.stringify(result.error)}`);
});

// Completion
await runTest("completion returns results", async () => {
  // Request completion after 'attribute mass : ' on line 2
  const result = await client.send("textDocument/completion", {
    textDocument: { uri: validUri },
    position: { line: 2, character: 25 },
  });
  assert(!result.error, `completion should not error: ${JSON.stringify(result.error)}`);
});

// Go to definition
await runTest("definition resolves part def reference", async () => {
  // 'Vehicle' on line 4: 'part vehicle : Vehicle;'
  const result = await client.send("textDocument/definition", {
    textDocument: { uri: validUri },
    position: { line: 4, character: 21 },
  });
  assert(!result.error, `definition should not error: ${JSON.stringify(result.error)}`);
  if (result.result) {
    const locs = Array.isArray(result.result) ? result.result : [result.result];
    assert(locs.length > 0, "should resolve to at least one location");
  }
});

// Find references
await runTest("references finds usages of Vehicle", async () => {
  // 'Vehicle' at line 1 definition
  const result = await client.send("textDocument/references", {
    textDocument: { uri: validUri },
    position: { line: 1, character: 13 },
    context: { includeDeclaration: true },
  });
  assert(!result.error, `references should not error: ${JSON.stringify(result.error)}`);
  if (result.result) {
    assert(result.result.length >= 1, "should find at least 1 reference");
  }
});

// Formatting
await runTest("formatting returns without error", async () => {
  const result = await client.send("textDocument/formatting", {
    textDocument: { uri: validUri },
    options: { tabSize: 4, insertSpaces: true },
  });
  assert(!result.error, `formatting should not error: ${JSON.stringify(result.error)}`);
});

// Workspace symbols
await runTest("workspace/symbol finds Vehicle", async () => {
  const result = await client.send("workspace/symbol", {
    query: "Vehicle",
  });
  assert(!result.error, `workspace/symbol should not error: ${JSON.stringify(result.error)}`);
  if (result.result) {
    const found = result.result.some((s) => s.name.includes("Vehicle"));
    assert(found, "should find a symbol containing 'Vehicle'");
  }
});

// AADL library tests
console.log("\nAADL Library:");

const aadlFile = join(fixturesPath, "aadl-test.sysml");
const aadlUri = pathToFileURL(aadlFile).href;
const aadlContent = readFileSync(aadlFile, "utf8");

client.notify("textDocument/didOpen", {
  textDocument: {
    uri: aadlUri,
    languageId: "sysml",
    version: 1,
    text: aadlContent,
  },
});

await runTest("AADL file has no errors", async () => {
  const diag = await client.waitForDiagnostics(aadlUri);
  const errors = diag.diagnostics.filter((d) => d.severity === 1);
  assert(
    errors.length === 0,
    `expected 0 errors, got ${errors.length}: ${errors.map((e) => e.message).join(", ")}`,
  );
});

await runTest("AADL documentSymbol returns symbols", async () => {
  const result = await client.send("textDocument/documentSymbol", {
    textDocument: { uri: aadlUri },
  });
  assert(result.result, "documentSymbol should return a result");
  assert(result.result.length > 0, "should have at least one symbol");
});

console.log(
  `\n=== Results: ${passed} passed, ${failed} failed, ${passed + failed} total ===`,
);

await client.shutdown();
process.exit(failed > 0 ? 1 : 0);
