/*
 * MaCaJadxMcpBridge.java
 * Part of: jadx-mcp-chatgpt
 * Author:  Akram Elbahar
 * License: MIT
 *
 * JADX plugin that exposes the decompiler over a localhost HTTP JSON-RPC
 * server, so a remote MCP server (mcp_server/server.py) can call it on
 * behalf of ChatGPT.
 *
 * Listens on 127.0.0.1:8766 (different from the IDA bridge on 8765).
 */
package ma.akram.jadxmcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.ResourceFile;
import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.api.plugins.JadxPluginInfoBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MaCaJadxMcpBridge implements JadxPlugin {

    public static final String PLUGIN_ID = "ma-akram-jadx-mcp-bridge";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8766;

    private JadxPluginContext ctx;
    private JadxDecompiler dec;
    private HttpServer server;
    private final Gson gson = new Gson();

    @Override
    public JadxPluginInfo getPluginInfo() {
        return JadxPluginInfoBuilder.pluginId(PLUGIN_ID)
                .name("JADX MCP Bridge")
                .description("HTTP bridge for MCP clients (Claude/ChatGPT) by Akram Elbahar")
                .homepage("https://github.com/akramelbahar/jadx-mcp-chatgpt")
                .build();
    }

    @Override
    public void init(JadxPluginContext context) {
        this.ctx = context;
        this.dec = context.getDecompiler();
        try {
            server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
            server.createContext("/", this::handle);
            server.setExecutor(null);
            server.start();
            System.out.println("[jadx-mcp-bridge] listening on http://" + HOST + ":" + PORT);
        } catch (IOException e) {
            System.err.println("[jadx-mcp-bridge] failed to start: " + e.getMessage());
        }
    }

    @Override
    public void unload() {
        if (server != null) {
            server.stop(0);
            System.out.println("[jadx-mcp-bridge] stopped");
        }
    }

    // ---------- HTTP handler: { "id":1, "method":"tool_name", "params":{...} } ----------

    private void handle(HttpExchange ex) throws IOException {
        byte[] body;
        int code = 200;
        try {
            byte[] reqBytes = ex.getRequestBody().readAllBytes();
            JsonObject req = gson.fromJson(new String(reqBytes, StandardCharsets.UTF_8), JsonObject.class);
            String method = req.get("method").getAsString();
            JsonObject params = req.has("params") ? req.getAsJsonObject("params") : new JsonObject();
            Object result = dispatch(method, params);
            JsonObject resp = new JsonObject();
            if (req.has("id")) resp.add("id", req.get("id"));
            resp.add("result", gson.toJsonTree(result));
            body = gson.toJson(resp).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            code = 500;
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            body = gson.toJson(err).getBytes(StandardCharsets.UTF_8);
        }
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    // ---------- Tool dispatch ----------

    private Object dispatch(String method, JsonObject p) throws Exception {
        switch (method) {
            case "get_metadata":         return getMetadata();
            case "list_all_classes":     return listAllClasses(strOpt(p, "filter", ""), intOpt(p, "limit", 1000));
            case "list_methods":         return listMethods(str(p, "class_name"));
            case "list_fields":          return listFields(str(p, "class_name"));
            case "get_class_source":     return getClassSource(str(p, "class_name"));
            case "get_class_smali":      return getClassSmali(str(p, "class_name"));
            case "get_method_code":      return getMethodCode(str(p, "class_name"), str(p, "method_name"));
            case "search_class_by_name": return searchClassByName(str(p, "name"));
            case "search_method_by_name":return searchMethodByName(str(p, "name"));
            case "search_in_code":       return searchInCode(str(p, "query"), intOpt(p, "limit", 50));
            case "xrefs_to_method":      return xrefsToMethod(str(p, "class_name"), str(p, "method_name"));
            case "xrefs_to_class":       return xrefsToClass(str(p, "class_name"));
            case "xrefs_to_field":       return xrefsToField(str(p, "class_name"), str(p, "field_name"));
            case "rename_method":        return renameMethod(str(p, "class_name"), str(p, "method_name"), str(p, "new_name"));
            case "rename_class":         return renameClass(str(p, "class_name"), str(p, "new_name"));
            case "list_resources":       return listResources(strOpt(p, "filter", ""), intOpt(p, "limit", 200));
            case "get_androidmanifest":  return getResource("AndroidManifest.xml");
            case "summarize_class":      return summarizeClass(str(p, "class_name"));
            default: throw new IllegalArgumentException("unknown tool '" + method + "'");
        }
    }

    // ---------- Tool implementations ----------

    private Map<String, Object> getMetadata() {
        Map<String, Object> m = new HashMap<>();
        m.put("jadx_version", JadxDecompiler.getVersion());
        m.put("class_count", dec.getClasses().size());
        m.put("resource_count", dec.getResources().size());
        return m;
    }

    private List<Map<String, Object>> listAllClasses(String filter, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        String f = filter.toLowerCase();
        for (JavaClass c : dec.getClasses()) {
            String name = c.getFullName();
            if (!f.isEmpty() && !name.toLowerCase().contains(f)) continue;
            Map<String, Object> e = new HashMap<>();
            e.put("name", name);
            e.put("methods", c.getMethods().size());
            e.put("fields", c.getFields().size());
            out.add(e);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private List<Map<String, Object>> listMethods(String className) {
        JavaClass c = findClass(className);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JavaMethod m : c.getMethods()) {
            Map<String, Object> e = new HashMap<>();
            e.put("name", m.getName());
            e.put("signature", m.getMethodNode().getMethodInfo().getShortId());
            e.put("return_type", m.getReturnType().toString());
            out.add(e);
        }
        return out;
    }

    private List<Map<String, Object>> listFields(String className) {
        JavaClass c = findClass(className);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JavaField fi : c.getFields()) {
            Map<String, Object> e = new HashMap<>();
            e.put("name", fi.getName());
            e.put("type", fi.getType().toString());
            out.add(e);
        }
        return out;
    }

    private String getClassSource(String className) {
        return findClass(className).getCode();
    }

    private String getClassSmali(String className) {
        return findClass(className).getSmali();
    }

    private String getMethodCode(String className, String methodName) {
        JavaClass c = findClass(className);
        StringBuilder sb = new StringBuilder();
        for (JavaMethod m : c.getMethods()) {
            if (m.getName().equals(methodName) || m.getMethodNode().getMethodInfo().getShortId().equals(methodName)) {
                String code = c.getCode();
                int line = m.getDecompiledLine();
                if (line > 0) {
                    String[] lines = code.split("\n");
                    int start = Math.max(0, line - 1);
                    int end = Math.min(lines.length, line + 80);
                    for (int i = start; i < end; i++) sb.append(lines[i]).append("\n");
                }
                return sb.length() > 0 ? sb.toString() : ("// (could not locate line for " + methodName + ")");
            }
        }
        throw new RuntimeException("method '" + methodName + "' not found in " + className);
    }

    private List<String> searchClassByName(String name) {
        List<String> out = new ArrayList<>();
        String n = name.toLowerCase();
        for (JavaClass c : dec.getClasses()) {
            if (c.getFullName().toLowerCase().contains(n)) out.add(c.getFullName());
            if (out.size() >= 100) break;
        }
        return out;
    }

    private List<Map<String, String>> searchMethodByName(String name) {
        List<Map<String, String>> out = new ArrayList<>();
        String n = name.toLowerCase();
        for (JavaClass c : dec.getClasses()) {
            for (JavaMethod m : c.getMethods()) {
                if (m.getName().toLowerCase().contains(n)) {
                    Map<String, String> e = new HashMap<>();
                    e.put("class", c.getFullName());
                    e.put("method", m.getName());
                    out.add(e);
                    if (out.size() >= 100) return out;
                }
            }
        }
        return out;
    }

    private List<Map<String, String>> searchInCode(String query, int limit) {
        List<Map<String, String>> out = new ArrayList<>();
        for (JavaClass c : dec.getClasses()) {
            String code = c.getCode();
            if (code.contains(query)) {
                int idx = code.indexOf(query);
                int s = Math.max(0, idx - 60);
                int e = Math.min(code.length(), idx + 60);
                Map<String, String> hit = new HashMap<>();
                hit.put("class", c.getFullName());
                hit.put("snippet", code.substring(s, e).replace("\n", " "));
                out.add(hit);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    private List<Map<String, String>> xrefsToMethod(String className, String methodName) {
        JavaClass c = findClass(className);
        for (JavaMethod m : c.getMethods()) {
            if (m.getName().equals(methodName)) return refsOf(m);
        }
        throw new RuntimeException("method not found: " + methodName);
    }

    private List<Map<String, String>> xrefsToClass(String className) {
        return refsOf(findClass(className));
    }

    private List<Map<String, String>> xrefsToField(String className, String fieldName) {
        JavaClass c = findClass(className);
        for (JavaField fi : c.getFields()) {
            if (fi.getName().equals(fieldName)) return refsOf(fi);
        }
        throw new RuntimeException("field not found: " + fieldName);
    }

    private List<Map<String, String>> refsOf(JavaNode node) {
        List<Map<String, String>> out = new ArrayList<>();
        for (JavaNode use : node.getUseIn()) {
            Map<String, String> e = new HashMap<>();
            e.put("class", use.getTopParentClass().getFullName());
            e.put("name", use.getName());
            out.add(e);
        }
        return out;
    }

    private boolean renameMethod(String className, String methodName, String newName) {
        JavaClass c = findClass(className);
        for (JavaMethod m : c.getMethods()) {
            if (m.getName().equals(methodName)) {
                if (ctx.getGuiContext() != null) {
                    // GUI-synced rename via the GUI context if available
                    try {
                        ctx.getGuiContext().getMainFrame();
                    } catch (Exception ignored) {}
                }
                m.getMethodNode().getMethodInfo().setAlias(newName);
                return true;
            }
        }
        return false;
    }

    private boolean renameClass(String className, String newName) {
        JavaClass c = findClass(className);
        c.getClassNode().getClassInfo().changeShortName(newName);
        return true;
    }

    private List<Map<String, String>> listResources(String filter, int limit) {
        List<Map<String, String>> out = new ArrayList<>();
        String f = filter.toLowerCase();
        for (ResourceFile r : dec.getResources()) {
            if (!f.isEmpty() && !r.getOriginalName().toLowerCase().contains(f)) continue;
            Map<String, String> e = new HashMap<>();
            e.put("name", r.getOriginalName());
            e.put("type", r.getType().toString());
            out.add(e);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private String getResource(String name) {
        for (ResourceFile r : dec.getResources()) {
            if (r.getOriginalName().endsWith(name)) {
                try {
                    return r.loadContent().getText().getCodeStr();
                } catch (Exception e) { return "// failed: " + e.getMessage(); }
            }
        }
        throw new RuntimeException("resource not found: " + name);
    }

    private Map<String, Object> summarizeClass(String className) {
        JavaClass c = findClass(className);
        Map<String, Object> out = new HashMap<>();
        out.put("name", c.getFullName());
        out.put("source", c.getCode());
        out.put("methods", listMethods(className));
        out.put("fields", listFields(className));
        out.put("xrefs", refsOf(c));
        return out;
    }

    // ---------- helpers ----------

    private JavaClass findClass(String name) {
        for (JavaClass c : dec.getClasses()) {
            if (c.getFullName().equals(name) || c.getName().equals(name)) return c;
        }
        for (JavaClass c : dec.getClasses()) {
            if (c.getFullName().endsWith("." + name)) return c;
        }
        throw new RuntimeException("class not found: " + name);
    }

    private static String str(JsonObject p, String k) {
        if (!p.has(k)) throw new IllegalArgumentException("missing param: " + k);
        return p.get(k).getAsString();
    }
    private static String strOpt(JsonObject p, String k, String d) {
        return p.has(k) ? p.get(k).getAsString() : d;
    }
    private static int intOpt(JsonObject p, String k, int d) {
        return p.has(k) ? p.get(k).getAsInt() : d;
    }
}
