"""server.py — MCP server for ChatGPT  (JADX edition)

Part of: jadx-mcp-chatgpt
Author:  Akram Elbahar
License: MIT

Run:
    pip install -r requirements.txt
    python server.py
Tunnel:
    cloudflared tunnel --url http://localhost:8001
ChatGPT connector URL:
    https://YOUR-URL.trycloudflare.com/mcp     (no trailing slash, no /sse)
"""
import os, httpx, uvicorn
from fastmcp import FastMCP

JADX_URL = os.environ.get("JADX_BRIDGE_URL", "http://127.0.0.1:8766")
mcp = FastMCP("jadx-mcp")


def _call(method: str, **params):
    r = httpx.post(JADX_URL, json={"id": 1, "method": method, "params": params}, timeout=180.0)
    data = r.json()
    if "error" in data: raise RuntimeError(data["error"])
    return data["result"]


# ---- ChatGPT canonical discovery pair ----

@mcp.tool()
def search(query: str) -> list[dict]:
    """Search classes, methods, and source code. Returns [{id, title, snippet}].
    `id` is a class fullname you can pass to fetch."""
    out = []
    for name in _call("search_class_by_name", name=query):
        out.append({"id": name, "title": name, "snippet": "class"})
    for m in _call("search_method_by_name", name=query):
        out.append({"id": m["class"], "title": f"{m['class']}#{m['method']}", "snippet": "method"})
    for h in _call("search_in_code", query=query, limit=20):
        out.append({"id": h["class"], "title": h["class"], "snippet": h["snippet"]})
    return out[:50]

@mcp.tool()
def fetch(id: str) -> dict:
    """Full context for a class: source + methods + fields + xrefs."""
    return _call("summarize_class", class_name=id)


# ---- Listing / metadata ----

@mcp.tool()
def get_metadata() -> dict:
    """JADX version, total class count, resource count."""
    return _call("get_metadata")

@mcp.tool()
def list_all_classes(filter: str = "", limit: int = 1000) -> list[dict]:
    """List all decompiled classes, optional substring filter on full name."""
    return _call("list_all_classes", filter=filter, limit=limit)

@mcp.tool()
def list_methods(class_name: str) -> list[dict]:
    """List methods of a class (name, signature, return_type)."""
    return _call("list_methods", class_name=class_name)

@mcp.tool()
def list_fields(class_name: str) -> list[dict]:
    """List fields of a class (name, type)."""
    return _call("list_fields", class_name=class_name)


# ---- Source ----

@mcp.tool()
def get_class_source(class_name: str) -> str:
    """Full Java source of a class."""
    return _call("get_class_source", class_name=class_name)

@mcp.tool()
def get_class_smali(class_name: str) -> str:
    """Smali for a class — use this to ground-truth the decompiler output."""
    return _call("get_class_smali", class_name=class_name)

@mcp.tool()
def get_method_code(class_name: str, method_name: str) -> str:
    """Decompiled code of a single method (extracted from class source)."""
    return _call("get_method_code", class_name=class_name, method_name=method_name)


# ---- Search ----

@mcp.tool()
def search_class_by_name(name: str) -> list[str]:
    """Find classes whose full name contains `name` (case-insensitive)."""
    return _call("search_class_by_name", name=name)

@mcp.tool()
def search_method_by_name(name: str) -> list[dict]:
    """Find methods by name across all classes. Returns [{class, method}]."""
    return _call("search_method_by_name", name=name)

@mcp.tool()
def search_in_code(query: str, limit: int = 50) -> list[dict]:
    """Full-text search across all decompiled sources."""
    return _call("search_in_code", query=query, limit=limit)


# ---- Cross-references ----

@mcp.tool()
def xrefs_to_method(class_name: str, method_name: str) -> list[dict]:
    """Find all uses of a method (callers + references)."""
    return _call("xrefs_to_method", class_name=class_name, method_name=method_name)

@mcp.tool()
def xrefs_to_class(class_name: str) -> list[dict]:
    """Find all uses of a class (instantiations, type references, subclassing)."""
    return _call("xrefs_to_class", class_name=class_name)

@mcp.tool()
def xrefs_to_field(class_name: str, field_name: str) -> list[dict]:
    """Find all reads/writes of a field."""
    return _call("xrefs_to_field", class_name=class_name, field_name=field_name)


# ---- Mutation (GUI-synced when JADX-GUI is running) ----

@mcp.tool()
def rename_method(class_name: str, method_name: str, new_name: str) -> bool:
    """Rename a method. Updates JADX-GUI live."""
    return _call("rename_method", class_name=class_name, method_name=method_name, new_name=new_name)

@mcp.tool()
def rename_class(class_name: str, new_name: str) -> bool:
    """Rename a class. Updates JADX-GUI live."""
    return _call("rename_class", class_name=class_name, new_name=new_name)


# ---- Resources ----

@mcp.tool()
def list_resources(filter: str = "", limit: int = 200) -> list[dict]:
    """List APK resources (layouts, strings.xml, raw assets, etc)."""
    return _call("list_resources", filter=filter, limit=limit)

@mcp.tool()
def get_androidmanifest() -> str:
    """Decoded AndroidManifest.xml."""
    return _call("get_androidmanifest")


# ---- Macro ----

@mcp.tool()
def summarize_class(class_name: str) -> dict:
    """One-shot context for a class: source + methods + fields + xrefs.
    Prefer this over calling get_class_source + list_methods + xrefs_to_class
    separately — it's one network round-trip instead of three."""
    return _call("summarize_class", class_name=class_name)


if __name__ == "__main__":
    # FastMCP 3.x — Streamable HTTP, mounted at /mcp.
    # ChatGPT Developer Mode requires this transport, not the older SSE one.
    try:
        app = mcp.http_app(path="/mcp")
    except AttributeError:
        app = mcp.streamable_http_app()
    uvicorn.run(app, host="0.0.0.0", port=8001)
