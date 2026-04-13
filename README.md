# JADX MCP for ChatGPT

Drive **JADX-GUI** from **ChatGPT** through the Model Context Protocol.
Decompile classes, walk Java↔Smali, find string xrefs, rename methods —
all on your live JADX session.

> **Author:** Akram Elbahar
> **License:** MIT
> **Companion project:** [ida-mcp-chatgpt](../ida-mcp-chatgpt) — same architecture for IDA Pro, runs alongside this one on different ports.

## Architecture

```
ChatGPT (Developer Mode)
   │  HTTPS + Streamable HTTP
   ▼
mcp_server/server.py            ← FastMCP 3.x server, port 8001, exposed via cloudflared
   │  HTTP JSON-RPC (localhost)
   ▼
JADX-GUI + jadx-mcp-bridge.jar  ← Java plugin, port 8766
```

JADX bridge runs on **8766** (vs IDA's 8765), MCP server on **8001** (vs IDA's 8000), so the two projects coexist without conflict.

## 20 tools exposed

**Discovery:** `search`, `fetch` (ChatGPT canonical pair)
**Listing:** `get_metadata`, `list_all_classes`, `list_methods`, `list_fields`
**Source:** `get_class_source`, `get_class_smali`, `get_method_code`
**Search:** `search_class_by_name`, `search_method_by_name`, `search_in_code`
**Xrefs:** `xrefs_to_method`, `xrefs_to_class`, `xrefs_to_field`
**Mutation:** `rename_method`, `rename_class`
**Resources:** `list_resources`, `get_androidmanifest`
**Macro:** `summarize_class` (source + methods + fields + xrefs in one round-trip)

## Setup — start to finish

### 1. Build the plugin

```bash
cd plugin
./gradlew shadowJar       # Windows: gradlew.bat shadowJar
```

If you don't have Gradle installed locally, generate a wrapper first:

```bash
gradle wrapper             # one-time, needs gradle on PATH
./gradlew shadowJar
```

Output: `plugin/build/libs/jadx-mcp-bridge-1.0.0.jar`

### 2. Install in JADX-GUI

Drop the jar in:
- Linux/macOS: `~/.jadx/plugins/`
- Windows: `%USERPROFILE%\.jadx\plugins\`

Or use **Plugins → Install plugin → from file** in JADX-GUI. **Restart JADX-GUI**.

Open any APK. In JADX-GUI's log (`View → Log`), look for:
```
[jadx-mcp-bridge] listening on http://127.0.0.1:8766
```

If you don't see it, go to **Preferences → Plugins**, make sure "JADX MCP Bridge" is enabled, and restart.

**Sanity check from PowerShell:**
```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8766 -Method Post -Body '{"id":1,"method":"get_metadata"}' -ContentType "application/json"
```
You should get JADX version + class count.

### 3. Run the MCP server

```bash
cd mcp_server
pip install -r requirements.txt
python server.py
```

You should see uvicorn running on port 8001, with **no** `transport 'sse'` line.

### 4. Tunnel it

```bash
cloudflared tunnel --url http://localhost:8001
```

Copy the `https://*.trycloudflare.com` URL.

### 5. Add the connector in ChatGPT

- Settings → Connectors → enable **Developer mode**
- **Add custom connector**
- Name: `JADX MCP`
- URL: `https://YOUR-URL.trycloudflare.com/mcp`  ← `/mcp`, no trailing slash
- Auth: No Auth
- Check the warning, click Create

### 6. Use it

New chat → enable the **JADX MCP** connector → try:

> Get the AndroidManifest. Find the main activity class and summarize it.
> Then list any classes with "crypto" or "auth" in the name.

## Prompt recipes

**Triage:**
> Get metadata. List classes filtered by "MainActivity". Summarize the result.

**Java↔Smali pivot:**
> Get the source of `com.app.LoginActivity.checkPassword`. Then get the Smali for the same class so I can compare.

**String xref hunt:**
> Search code for "api_key". For each hit, show me the class name and a snippet.

**Rename and propagate:**
> Rename method `com.app.a.b#c` to `decryptPayload`. Then list xrefs to it.

**Crypto hunting:**
> Search methods by name for "encrypt", "decrypt", "hmac", "sign". For each, show me which class.

## Known issues / fixes

**Build fails: `jadx-core:1.5.3 not found`**
JADX may have moved Maven coordinates. Try `io.github.skylot:jadx-core:1.5.3` or check the latest version on Maven Central. The build file uses `compileOnly` so the version only needs to expose the right APIs at compile time.

**Port 8766 already in use**
Edit `MaCaJadxMcpBridge.java`, change `PORT`, rebuild, and update `JADX_BRIDGE_URL` in `server.py` (or set the env var).

**Plugin loads but bridge line never appears**
Check JADX log for stack traces. The most common cause is a JADX API change between versions — `JavaNode.getUseIn()`, `MethodInfo.setAlias()`, and `ClassInfo.changeShortName()` have all shifted across releases. If you see a `NoSuchMethodError`, paste it in a chat and we'll patch.

**Renames don't show up in the GUI**
The plugin uses the JADX-core rename API which updates the model but doesn't always refresh open tabs. Close and reopen the class in JADX-GUI to see the new name. To fix this properly, the plugin would need to call `mainWindow.getTabsController().refreshTabs()` — left as a follow-up.

## Running both IDA and JADX MCPs side by side

You can have both connectors active in the same ChatGPT chat. Ports never collide:

| Service       | IDA project | JADX project |
|---------------|-------------|--------------|
| Bridge (local)| 8765        | 8766         |
| MCP server    | 8000        | 8001         |
| Tunnel        | tunnel A    | tunnel B     |
| ChatGPT name  | IDA MCP     | JADX MCP     |

This is the same workflow you already use with the Claude side: JADX for Java, IDA for native libs, both available in the same chat.

## Credit

Built by **Akram Elbahar**. If this saves you time during a reverse-engineering session, a star or a mention is appreciated.
