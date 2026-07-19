package xiaojw.hook;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.Iterator;

import de.robv.android.xposed.XposedBridge;
import fi.iki.elonen.NanoHTTPD;

/**
 * HTTP 服务，处理微信小程序的登录请求和配置管理。
 * 从 WxLoginHook 中抽离为独立 top-level 类，通过 ServerContext 与 WxLoginHook 通信。
 */
public class LoginHttpServer extends NanoHTTPD {

    private final ServerContext ctx;
    private final ClassLoader classLoader;

    public LoginHttpServer(ServerContext ctx, int port, ClassLoader cl) {
        super(port);
        this.ctx = ctx;
        this.classLoader = cl;
    }

    @Override
    public NanoHTTPD.Response serve(NanoHTTPD.IHTTPSession iHTTPSession) {
        if (!iHTTPSession.getMethod().equals(NanoHTTPD.Method.GET)) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "application/json", "{\"err\":-404,\"msg\":\"接口不存在\"}");
        }

        // 仅对 /login 等关键接口做请求级自愈(在对应分支内调用),普通查询接口(首页/whoami/instances/config)
        // 不阻塞等待,避免每个请求都被拖最多 1.5s 影响首页加载。

        String uri = iHTTPSession.getUri();

        // /whoami 接口：返回当前实例信息（JSON格式）
        if (uri.equals("/whoami")) {
            try {
                JSONObject info = new JSONObject();
                info.put("packageName", ctx.currentPackageName);
                info.put("userId", ctx.currentUserId);
                info.put("port", ctx.httpPort);
                info.put("version", ctx.versionName);
                info.put("j1", ctx.j1);
                info.put("c", ctx.c);
                info.put("a1", ServerContext.COMMON_PACKAGE + "." + ctx.a1);
                info.put("a7", ServerContext.COMMON_PACKAGE + "." + ctx.a7);
                info.put("j1StaticMethod", ctx.j1StaticMethod);
                info.put("j1InstanceMethod", ctx.j1InstanceMethod);
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", info.toString());
            } catch (Exception e) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", "{\"err\":-500,\"msg\":\"" + e.getMessage() + "\"}");
            }
        }

        // /register 接口：接收其他分身实例的注册通知，汇聚到内存实例表
        if (uri.equals("/register")) {
            try {
                String portStr = iHTTPSession.getParms().get("port");
                String userIdStr = iHTTPSession.getParms().get("userId");
                String ver = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        ? iHTTPSession.getParms().getOrDefault("version", "")
                        : null;
                String pkg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        ? iHTTPSession.getParms().getOrDefault("packageName", "com.tencent.mm")
                        : null;
                if (portStr != null && userIdStr != null) {
                    ctx.methods.registerInstanceInMemory(pkg, Integer.parseInt(userIdStr), Integer.parseInt(portStr), ver);
                    JSONObject r = new JSONObject();
                    r.put("success", true);
                    r.put("registeredPort", portStr);
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", r.toString());
                }
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", "{\"err\":-1,\"msg\":\"missing port/userId\"}");
            } catch (Exception e) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", "{\"err\":-500,\"msg\":\"" + e.getMessage() + "\"}");
            }
        }

        // /instances 接口：返回所有已启动的实例（主端口汇总内存表 / 其他实例向主端口拉取）
        if (uri.equals("/instances")) {
            try {
                JSONArray instances;
                if (ctx.httpPort == ServerContext.MASTER_PORT) {
                    instances = ctx.methods.getMemoryInstances();
                } else {
                    JSONArray master = ctx.methods.fetchMasterInstances();
                    instances = master != null ? master : ctx.methods.getMemoryInstances();
                }

                JSONObject portMap = new JSONObject();
                for (int i = 0; i < instances.length(); i++) {
                    JSONObject item = instances.getJSONObject(i);
                    String key = item.getString("packageName") + "_User" + item.getInt("userId");
                    portMap.put(key, item.getInt("port"));
                }

                JSONObject result = new JSONObject();
                result.put("instances", instances);
                result.put("portMap", portMap);
                result.put("current", ctx.currentPackageName);
                result.put("currentUserId", ctx.currentUserId);
                result.put("currentPort", ctx.httpPort);
                result.put("count", instances.length());
                result.put("role", ctx.httpPort == ServerContext.MASTER_PORT ? "master" : "slave");
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", result.toString());
            } catch (Exception e) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", "{\"err\":-500,\"msg\":\"" + e.getMessage() + "\"}");
            }
        }

        // /config 接口：查看/切换保活模式
        if (uri.equals("/config")) {
            String mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    ? iHTTPSession.getParms().getOrDefault("mode", "")
                    : "";
            if ("performance".equals(mode) || "power_saver".equals(mode)) {
                ctx.methods.switchPowerMode("power_saver".equals(mode));
            }
            try {
                JSONObject cfg = new JSONObject();
                cfg.put("mode", KeepAliveService.powerSaverMode ? "power_saver" : "performance");
                cfg.put("modeDesc", KeepAliveService.powerSaverMode ? "省电模式(AlarmManager定时唤醒)" : "性能模式(常驻WakeLock)");
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", cfg.toString());
            } catch (Exception e) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", "{\"err\":-500,\"msg\":\"" + e.getMessage() + "\"}");
            }
        }

        // /config_raw 接口：返回格式化后的版本配置 JSON 文本（供在线编辑使用）
        if (uri.equals("/config_raw")) {
            try {
                String prettyJson = new JSONObject(ctx.jsonString).toString(2);
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", prettyJson);
            } catch (Exception e) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", ctx.jsonString);
            }
        }

        // /save_config 接口：新增或更新版本配置（持久化保存，类似保活模式的保存方式）
        if (uri.equals("/save_config")) {
            try {
                String action = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        ? iHTTPSession.getParms().getOrDefault("action", "")
                        : "";
                if ("save".equals(action)) {
                    String configStr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? iHTTPSession.getParms().getOrDefault("config", "")
                            : "";
                    if (configStr != null && !configStr.isEmpty()) {
                        ctx.methods.saveConfig(configStr);
                        JSONObject r = new JSONObject();
                        r.put("success", true);
                        r.put("msg", "配置已保存");
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", r.toString());
                    }
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", "{\"err\":-1,\"msg\":\"缺少config参数\"}");
                } else if ("add_version".equals(action)) {
                    String version = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? iHTTPSession.getParms().getOrDefault("version", "") : "";
                    String j1Val = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? iHTTPSession.getParms().getOrDefault("j1", "") : "";
                    String cVal = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? iHTTPSession.getParms().getOrDefault("c", "") : "";
                    String a1Val = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? iHTTPSession.getParms().getOrDefault("a1", "") : "";
                    String a7Val = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? iHTTPSession.getParms().getOrDefault("a7", "") : "";
                    String j1Static = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? iHTTPSession.getParms().getOrDefault("j1_static_method", "") : "";
                    String j1Instance = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? iHTTPSession.getParms().getOrDefault("j1_instance_method", "") : "";
                    if (version != null && !version.isEmpty() && j1Val != null && !j1Val.isEmpty() && cVal != null && !cVal.isEmpty()) {
                        JSONObject rootCfg = new JSONObject(ctx.jsonString);
                        // 先判断是否已存在，再 put，避免 put 后 has 永远返回 true
                        boolean isNew = !rootCfg.has(version);
                        JSONObject verObj = new JSONObject();
                        verObj.put("j1", j1Val);
                        verObj.put("c", cVal);
                        if (a1Val != null && !a1Val.isEmpty()) verObj.put("a1", a1Val);
                        if (a7Val != null && !a7Val.isEmpty()) verObj.put("a7", a7Val);
                        if (j1Static != null && !j1Static.isEmpty()) verObj.put("j1_static_method", j1Static);
                        if (j1Instance != null && !j1Instance.isEmpty()) verObj.put("j1_instance_method", j1Instance);
                        rootCfg.put(version, verObj);
                        ctx.methods.saveConfig(rootCfg.toString());
                        JSONObject r = new JSONObject();
                        r.put("success", true);
                        r.put("msg", "版本 " + version + " 配置已" + (isNew ? "新增" : "更新"));
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", r.toString());
                    }
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", "{\"err\":-1,\"msg\":\"缺少必要参数(version/j1/c)\"}");
                } else if ("delete_version".equals(action)) {
                    String version = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? iHTTPSession.getParms().getOrDefault("version", "") : "";
                    if (version != null && !version.isEmpty()) {
                        JSONObject rootCfg = new JSONObject(ctx.jsonString);
                        if (rootCfg.has(version)) {
                            rootCfg.remove(version);
                            ctx.methods.saveConfig(rootCfg.toString());
                            JSONObject r = new JSONObject();
                            r.put("success", true);
                            r.put("msg", "版本 " + version + " 配置已删除");
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", r.toString());
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", "{\"err\":-1,\"msg\":\"版本 " + version + " 不存在\"}");
                    }
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", "{\"err\":-1,\"msg\":\"缺少version参数\"}");
                } else if ("reset".equals(action)) {
                    ctx.methods.resetConfig();
                    JSONObject r = new JSONObject();
                    r.put("success", true);
                    r.put("msg", "配置已重置为默认值（重启生效）");
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", r.toString());
                }
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", "{\"err\":-1,\"msg\":\"未知操作\"}");
            } catch (Exception e) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", "{\"err\":-500,\"msg\":\"" + e.getMessage() + "\"}");
            }
        }

        // /login 接口：执行登录（后台唤醒逻辑已由 doLogin 内部按需处理）
        if (uri.equals("/login")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
                        ctx.methods.doLogin(iHTTPSession.getParms().getOrDefault("appId", ServerContext.DEFAULT_AUTO_APP_ID), classLoader));
            }
        }

        // 首页HTML页面
        try {
            JSONObject jSONObject = new JSONObject(ctx.jsonString);
            StringBuilder sb = new StringBuilder();
            sb.append("<!DOCTYPE html>");
            sb.append("<html lang='zh-CN'>");
            sb.append("<head>");
            sb.append("<meta charset='UTF-8'>");
            sb.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
            sb.append("<title>wxcode版本信息</title>");
            sb.append("<style>");
            sb.append("body {");
            sb.append("  font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;");
            sb.append("  line-height: 1.6;");
            sb.append("  margin: 0;");
            sb.append("  padding: 20px;");
            sb.append("  background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);");
            sb.append("  min-height: 100vh;");
            sb.append("}");
            sb.append(".container {");
            sb.append("  max-width: 1000px;");
            sb.append("  margin: 0 auto;");
            sb.append("  background-color: white;");
            sb.append("  border-radius: 15px;");
            sb.append("  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.1);");
            sb.append("  padding: 30px;");
            sb.append("}");
            sb.append("h1 {");
            sb.append("  color: #2c3e50;");
            sb.append("  text-align: center;");
            sb.append("  margin-bottom: 30px;");
            sb.append("  font-size: 2.5em;");
            sb.append("  border-bottom: 3px solid #3498db;");
            sb.append("  padding-bottom: 10px;");
            sb.append("}");
            sb.append("h2 {");
            sb.append("  color: #34495e;");
            sb.append("  margin-top: 30px;");
            sb.append("  margin-bottom: 15px;");
            sb.append("  font-size: 1.5em;");
            sb.append("  display: flex;");
            sb.append("  align-items: center;");
            sb.append("}");
            sb.append("h2:before {");
            sb.append("  content: '';");
            sb.append("  width: 4px;");
            sb.append("  height: 24px;");
            sb.append("  background: #3498db;");
            sb.append("  margin-right: 10px;");
            sb.append("  border-radius: 2px;");
            sb.append("}");
            sb.append("p {");
            sb.append("  background: #f8f9fa;");
            sb.append("  padding: 12px 15px;");
            sb.append("  border-radius: 8px;");
            sb.append("  margin: 10px 0;");
            sb.append("  border-left: 4px solid #3498db;");
            sb.append("}");
            sb.append("a {");
            sb.append("  color: #3498db;");
            sb.append("  text-decoration: none;");
            sb.append("  font-weight: bold;");
            sb.append("  transition: all 0.3s ease;");
            sb.append("}");
            sb.append("a:hover {");
            sb.append("  color: #2980b9;");
            sb.append("  text-decoration: underline;");
            sb.append("}");
            sb.append("table {");
            sb.append("  width: 100%;");
            sb.append("  border-collapse: collapse;");
            sb.append("  margin-top: 20px;");
            sb.append("  box-shadow: 0 5px 15px rgba(0, 0, 0, 0.05);");
            sb.append("  border-radius: 10px;");
            sb.append("  overflow: hidden;");
            sb.append("}");
            sb.append(".version-scroll {");
            sb.append("  overflow-x: auto;");
            sb.append("  border-radius: 10px;");
            sb.append("}");
            sb.append(".version-scroll table th:first-child,");
            sb.append(".version-scroll table td:first-child {");
            sb.append("  position: sticky;");
            sb.append("  left: 0;");
            sb.append("  z-index: 1;");
            sb.append("  box-shadow: 2px 0 0 rgba(0, 0, 0, 0.08);");
            sb.append("}");
            sb.append(".version-scroll table th:first-child {");
            sb.append("  background: linear-gradient(to right, #3498db, #2c3e50);");
            sb.append("}");
            sb.append(".version-scroll table td:first-child {");
            sb.append("  background: #ffffff;");
            sb.append("}");
            sb.append(".version-scroll table tr:nth-child(even) td:first-child {");
            sb.append("  background: #f8f9fa;");
            sb.append("}");
            sb.append("th {");
            sb.append("  background: linear-gradient(to right, #3498db, #2c3e50);");
            sb.append("  color: white;");
            sb.append("  padding: 15px;");
            sb.append("  text-align: left;");
            sb.append("  font-weight: 600;");
            sb.append("  letter-spacing: 0.5px;");
            sb.append("}");
            sb.append("td {");
            sb.append("  padding: 12px 15px;");
            sb.append("  border-bottom: 1px solid #eee;");
            sb.append("}");
            sb.append("tr:nth-child(even) {");
            sb.append("  background-color: #f8f9fa;");
            sb.append("}");
            sb.append("tr:hover {");
            sb.append("  background-color: #e8f4fc;");
            sb.append("  transition: background-color 0.2s ease;");
            sb.append("}");
            sb.append(".version {");
            sb.append("  font-weight: bold;");
            sb.append("  color: #2c3e50;");
            sb.append("}");
            sb.append(".code {");
            sb.append("  font-family: 'Courier New', monospace;");
            sb.append("  background: #f1f1f1;");
            sb.append("  padding: 3px 6px;");
            sb.append("  border-radius: 4px;");
            sb.append("  color: #e74c3c;");
            sb.append("}");
            sb.append(".footer {");
            sb.append("  text-align: center;");
            sb.append("  margin-top: 30px;");
            sb.append("  color: #7f8c8d;");
            sb.append("  font-size: 0.9em;");
            sb.append("}");
            sb.append(".instance-list {");
            sb.append("  margin-top: 15px;");
            sb.append("}");
            sb.append(".instance-item {");
            sb.append("  background: #f8f9fa;");
            sb.append("  padding: 10px 15px;");
            sb.append("  border-radius: 8px;");
            sb.append("  margin: 8px 0;");
            sb.append("  border-left: 4px solid #3498db;");
            sb.append("  display: flex;");
            sb.append("  justify-content: space-between;");
            sb.append("  align-items: center;");
            sb.append("}");
            sb.append(".instance-item.current {");
            sb.append("  border-left-color: #27ae60;");
            sb.append("  background: #e8f8f5;");
            sb.append("}");
            sb.append(".instance-item .port {");
            sb.append("  font-weight: bold;");
            sb.append("  color: #3498db;");
            sb.append("  font-size: 1.2em;");
            sb.append("}");
            sb.append(".instance-item.current .port {");
            sb.append("  color: #27ae60;");
            sb.append("}");

            sb.append("</style>");
            sb.append("</head>");
            sb.append("<body>");
            sb.append("<div class='container'>");
            sb.append("<h1>📦 wxcode </h1>");

            // 动态实例列表区域（通过 JavaScript 加载）
            sb.append("<h2>📋 所有已启动实例</h2>");
            sb.append("<div id='instance-list' class='instance-list'>");
            sb.append("<p style='color:#666;'>正在加载实例列表...</p>");
            sb.append("</div>");
            sb.append("<script>");
            sb.append("fetch('/instances').then(r => r.json()).then(data => {");
            sb.append("  const list = document.getElementById('instance-list');");
            sb.append("  if (data.count === 0) {");
            sb.append("    list.innerHTML = '<p style=\"color:#e74c3c;\">⚠️ 实例列表为空，可能原因：</p><ul><li>微信刚启动，实例尚未注册</li><li>主端口(8088)未启动，分身实例无法上报</li></ul><p>请刷新页面重试，或检查日志。</p>';");
            sb.append("    return;");
            sb.append("  }");
            sb.append("  let html = '<p>共检测到 <strong>' + data.count + '</strong> 个已启动的微信实例：</p>';");
            sb.append("  data.instances.forEach(inst => {");
            sb.append("    const isCurrent = inst.packageName === data.current && inst.userId === data.currentUserId;");
            sb.append("    html += '<div class=\"instance-item' + (isCurrent ? ' current' : '') + '\">';");
            sb.append("    html += '<span><code>' + inst.packageName + '</code> (User ' + inst.userId + ') | 版本 ' + inst.version + '</span>';");
            sb.append("    html += '<span class=\"port\">端口 ' + inst.port + '</span>';");
            sb.append("    html += '</div>';");
            sb.append("  });");

            sb.append("  list.innerHTML = html;");
            sb.append("}).catch(e => {");
            sb.append("  document.getElementById('instance-list').innerHTML = '<p style=\"color:#e74c3c;\">❌ 加载失败: ' + e + '</p><p>请检查 HTTP 服务是否正常运行。</p>';");
            sb.append("});");
            sb.append("</script>");

            sb.append("<h2>📦 当前实例信息</h2>");
            sb.append("<p><strong>包名：</strong> <code>").append(ctx.currentPackageName).append("</code></p>");
            sb.append("<p><strong>User ID：</strong> <code>").append(String.valueOf(ctx.currentUserId)).append("</code> <small style='color:#666;'>（用于区分系统级分身）</small></p>");
            sb.append("<p><strong>HTTP端口：</strong> <code>").append(String.valueOf(ctx.httpPort)).append("</code></p>");
            sb.append("<p><strong>微信版本：</strong> <code>").append(ctx.versionName).append("</code></p>");

            sb.append("<h2>📖 API接口说明</h2>");
            sb.append("<table>");
            sb.append("<thead><tr><th>接口</th><th>说明</th><th>示例</th></tr></thead>");
            sb.append("<tbody>");
            sb.append("<tr><td><code>/whoami</code></td><td>返回当前实例信息(JSON)</td><td><a href='/whoami'>点击查看</a></td></tr>");
            sb.append("<tr><td><code>/instances</code></td><td>返回端口映射表(JSON)</td><td><a href='/instances'>点击查看</a></td></tr>");
            sb.append("<tr><td><code>/login</code></td><td>执行登录获取code</td><td><a href='/login?appId=wxaa3a999db5d744c6'>点击测试</a></td></tr>");
            sb.append("</tbody></table>");
            sb.append("<h2>⚡ 保活模式切换</h2>");
            sb.append("<p>当前模式：<strong id='currentMode'>加载中...</strong></p>");
            sb.append("<div style='display:flex;gap:12px;flex-wrap:wrap;margin-bottom:16px'>");
            sb.append("<button onclick='switchMode(\"performance\")' style='padding:10px 20px;font-size:14px;cursor:pointer;background:#4CAF50;color:#fff;border:none;border-radius:6px'>⚡ 性能模式（即时响应，耗电3-9%/天）</button>");
            sb.append("<button onclick='switchMode(\"power_saver\")' style='padding:10px 20px;font-size:14px;cursor:pointer;background:#2196F3;color:#fff;border:none;border-radius:6px'>🔋 省电模式（定时唤醒，耗电0.1%/天，响应延迟2-9分钟）</button>");
            sb.append("</div>");
            sb.append("<div style='background:#fff8e1;border-left:4px solid #ff9800;padding:12px 15px;border-radius:8px;margin:12px 0;font-size:0.92em;color:#5d4037'>");
            sb.append("<strong>⚠️ 重要提示：</strong><br>");
            sb.append("<strong>性能模式</strong>需在系统设置中将微信设为<strong>“后台无限制/允许后台活动”</strong>才能即时响应，否则系统仍会限制网络，导致请求卡顿。<br>");
            sb.append("<span style='color:#666;font-size:0.9em'>设置路径参考：</span><br>");
            sb.append("<span style='color:#666;font-size:0.9em'>• MIUI/澎湃OS：设置 → 应用设置 → 微信 → 省电策略 → 无限制（并开启自启动）</span><br>");
            sb.append("<span style='color:#666;font-size:0.9em'>• HarmonyOS/EMUI：设置 → 应用 → 微信 → 耗电详情 → 启动管理 → 关闭自动管理，全部手动允许</span><br>");
            sb.append("<span style='color:#666;font-size:0.9em'>• ColorOS/OriginOS：设置 → 电池 → 微信 → 后台保持运行/允许后台活动（并开启自启动）</span><br>");
            sb.append("<span style='display:block;margin-top:6px'><strong>省电模式</strong>通过 AlarmManager 定时唤醒，对后台限制更鲁棒，但响应有 2-9 分钟延迟。</span>");
            sb.append("</div>");
            sb.append("<script>");
            sb.append("fetch('/config').then(r=>r.json()).then(d=>{document.getElementById('currentMode').textContent=d.modeDesc;});");
            sb.append("function switchMode(m){fetch('/config?mode='+m).then(r=>r.json()).then(d=>{alert('已切换：'+d.modeDesc);location.reload();});}");
            sb.append("</script>");
            sb.append("<h2>💡 分身端口映射</h2>");
            sb.append("<p>端口计算公式：<code>基础端口(8088) + User ID偏移</code></p>");
            sb.append("<p><code>User ID &gt; 100 时: 8200 + (User ID % 100)</code></p>");
            sb.append("<table>");
            sb.append("<thead><tr><th>类型</th><th>标识</th><th>端口</th></tr></thead>");
            sb.append("<tbody>");
            sb.append("<tr><td>主用户微信</td><td><code>com.tencent.mm (User 0)</code></td><td>8088</td></tr>");
            sb.append("<tr><td>系统分身(OPPO/vivo等)</td><td><code>com.tencent.mm (User 10)</code></td><td>8098</td></tr>");
            sb.append("<tr><td>系统分身(小米等)</td><td><code>com.tencent.mm (User 999)</code></td><td>8299</td></tr>");
            sb.append("</tbody></table>");
            sb.append("<p>💡 提示：系统分身包名均为 <code>com.tencent.mm</code>。所有分身启动后向主端口(用户0的<code>8088</code>)发送注册通知，由主端口汇总所有端口；访问任意实例的 <a href='/instances'>/instances</a> 即可看到全部（主端口<code>role=master</code>，其余<code>slave</code>）。跨用户共享依赖<code>127.0.0.1</code>互通（多数系统分身满足）。</p>");

            // 显示公共配置
            JSONObject commonConfig = jSONObject.optJSONObject("common");
            if (commonConfig != null) {
                sb.append("<h2>⚙️ 公共配置</h2>");
                sb.append("<table>");
                sb.append("<thead><tr><th>配置项</th><th>值</th></tr></thead>");
                sb.append("<tbody>");
                sb.append("<tr><td><code>j1_static_method</code></td><td class='code'>").append(commonConfig.optString("j1_static_method", "-")).append("</td></tr>");
                sb.append("<tr><td><code>j1_instance_method</code></td><td class='code'>").append(commonConfig.optString("j1_instance_method", "-")).append("</td></tr>");
                sb.append("<tr><td><code>COMMON_PACKAGE</code></td><td class='code'>").append(ServerContext.COMMON_PACKAGE).append("</td></tr>");
                sb.append("</tbody></table>");
                sb.append("<p style='color:#666;font-size:0.9em;'>💡 公共配置为所有版本提供默认值，版本配置可覆盖公共配置</p>");
            }

            sb.append("<h2>📋 适配版本列表</h2>");
            sb.append("<div class='version-scroll'>");
            sb.append("<table>");
            sb.append("<thead>");
            sb.append("<tr>");
            sb.append("<th>版本号</th>");
            sb.append("<th>j1 参数</th>");
            sb.append("<th>c 参数</th>");
            sb.append("<th>a1 (h2→1参)</th>");
            sb.append("<th>a7 (l2→2参)</th>");
            sb.append("<th>覆盖配置</th>");
            sb.append("</tr>");
            sb.append("</thead>");
            // 计算所有 a1/a7 的公共前缀，展示时省略只保留尾部不同的类名段
            java.util.List<String> classNames = new java.util.ArrayList<>();
            Iterator<String> preIt = jSONObject.keys();
            while (preIt.hasNext()) {
                String key = preIt.next();
                if ("common".equals(key)) continue; // 跳过公共配置
                JSONObject o = jSONObject.getJSONObject(key);
                String a = o.optString("a1", "");
                String b = o.optString("a7", "");
                if (a.contains(".")) classNames.add(a);
                if (b.contains(".")) classNames.add(b);
            }
            final String aPrefix = ctx.methods.longestCommonPrefix(classNames);
            sb.append("<tbody>");
            Iterator<String> itKeys = jSONObject.keys();
            while (itKeys.hasNext()) {
                String next = itKeys.next();
                if ("common".equals(next)) continue; // 跳过公共配置
                JSONObject jSONObject2 = jSONObject.getJSONObject(next);
                String string = jSONObject2.getString("j1");
                String string2 = jSONObject2.getString("c");
                String a1Full = jSONObject2.optString("a1", "-");
                String a7Full = jSONObject2.optString("a7", "-");
                String stringA1 = ctx.methods.shortenClass(a1Full, aPrefix);
                String stringA7 = ctx.methods.shortenClass(a7Full, aPrefix);

                // 检查覆盖的配置
                StringBuilder overrideConfig = new StringBuilder();
                if (jSONObject2.has("j1_static_method")) {
                    overrideConfig.append("j1_static_method=").append(jSONObject2.getString("j1_static_method"));
                }
                if (jSONObject2.has("j1_instance_method")) {
                    if (overrideConfig.length() > 0) overrideConfig.append("<br>");
                    overrideConfig.append("j1_instance_method=").append(jSONObject2.getString("j1_instance_method"));
                }
                String overrideDisplay = overrideConfig.length() > 0 ? overrideConfig.toString() : "<span style='color:#999'>-</span>";

                sb.append("<tr>");
                sb.append("<td class='version'>").append(next).append("</td>");
                sb.append("<td class='code'>").append(string).append("</td>");
                sb.append("<td class='code'>").append(string2).append("</td>");
                sb.append("<td class='code' title='").append(a1Full).append("'>").append(stringA1).append("</td>");
                sb.append("<td class='code' title='").append(a7Full).append("'>").append(stringA7).append("</td>");
                sb.append("<td class='code'>").append(overrideDisplay).append("</td>");
                sb.append("</tr>");
            }
            sb.append("</tbody>");
            sb.append("</table>");
            sb.append("</div>");
            sb.append("<h2>⚙️ 版本配置管理</h2>");
            sb.append("<div style='background:#fff3e0;border-left:4px solid #ff9800;padding:12px 15px;border-radius:8px;margin:12px 0;font-size:0.92em;color:#5d4037'>");
            sb.append("<strong>💡 说明：</strong><br>");
            sb.append("您可以在此新增/修改微信版本的适配配置，保存后将持久化保存，类似保活模式的保存方式。<br>");
            sb.append("每次保存会覆盖整个配置，新增版本前请先复制下方现有配置到文本框修改再保存。<br>");
            sb.append("修改保存后<b>需重启微信</b>新配置才会生效。");
            sb.append("</div>");
            sb.append("<div style='background:#e8f4fd;border-left:4px solid #2196F3;padding:10px 15px;border-radius:8px;margin:12px 0;font-size:0.92em;color:#1565c0'>");
            sb.append("<strong>💡 操作无响应？</strong> 点击上方按钮后如果浏览器一直无响应，请切换到微信前台再切回来（唤醒后台进程），然后重试即可。");
            sb.append("</div>");
            sb.append("<div style='background:#f0f4ff;border-left:4px solid #7c4dff;padding:12px 15px;border-radius:8px;margin:12px 0;font-size:0.92em;color:#4a148c'>");
            sb.append("<strong>🤖 Hook 配置自动生成工具</strong><br>");
            sb.append("不知道新版微信的 j1/c/a1/a7 参数？试试这个自动分析工具：");
            sb.append("<a href='https://github.com/xiaojinwen/wxcode-hook-config' target='_blank' style='display:inline-block;margin-top:6px;padding:6px 14px;background:#7c4dff;color:#fff;text-decoration:none;border-radius:6px;font-weight:bold'>🔗 前往 wxcode-hook-config →</a>");
            sb.append("</div>");
            sb.append("<h3>📝 编辑完整配置（JSON 格式）</h3>");
            sb.append("<textarea id='configEditor' style='width:100%;height:250px;font-family:monospace;font-size:13px;padding:10px;border:1px solid #ddd;border-radius:8px;box-sizing:border-box' readonly></textarea>");
            sb.append("<div style='display:flex;gap:8px;margin-top:8px;flex-wrap:wrap'>");
            sb.append("<button onclick='loadConfigToEditor()' style='padding:8px 16px;font-size:13px;cursor:pointer;background:#3498db;color:#fff;border:none;border-radius:6px'>📖 加载当前配置</button>");
            sb.append("<button onclick='saveFullConfig()' style='padding:8px 16px;font-size:13px;cursor:pointer;background:#27ae60;color:#fff;border:none;border-radius:6px'>💾 保存完整配置</button>");
            sb.append("<button onclick='resetConfig()' style='padding:8px 16px;font-size:13px;cursor:pointer;background:#e74c3c;color:#fff;border:none;border-radius:6px'>🔄 重置为默认</button>");
            sb.append("</div>");
            sb.append("<h3>➕ 快速新增单个版本</h3>");
            sb.append("<div style='display:grid;grid-template-columns:1fr 1fr;gap:8px;max-width:600px'>");
            sb.append("<input id='fv_version' placeholder='版本号 (如 8.0.80)' style='padding:8px;border:1px solid #ddd;border-radius:6px;box-sizing:border-box;width:100%'>");
            sb.append("<input id='fv_j1' placeholder='j1 类名 (如 hm0.j1)' style='padding:8px;border:1px solid #ddd;border-radius:6px;box-sizing:border-box;width:100%'>");
            sb.append("<input id='fv_c' placeholder='c 类名 (如 cl0.c)' style='padding:8px;border:1px solid #ddd;border-radius:6px;box-sizing:border-box;width:100%'>");
            sb.append("<input id='fv_a1' placeholder='a1 类名 (可选)' style='padding:8px;border:1px solid #ddd;border-radius:6px;box-sizing:border-box;width:100%'>");
            sb.append("<input id='fv_a7' placeholder='a7 类名 (可选)' style='padding:8px;border:1px solid #ddd;border-radius:6px;box-sizing:border-box;width:100%'>");
            sb.append("<input id='fv_j1_static' placeholder='j1_static_method (可选)' style='padding:8px;border:1px solid #ddd;border-radius:6px;box-sizing:border-box;width:100%'>");
            sb.append("<input id='fv_j1_instance' placeholder='j1_instance_method (可选)' style='padding:8px;border:1px solid #ddd;border-radius:6px;box-sizing:border-box;width:100%'>");
            sb.append("</div>");
            sb.append("<div style='display:flex;gap:8px;margin-top:8px;flex-wrap:wrap'>");
            sb.append("<button onclick='addVersion()' style='padding:8px 16px;font-size:13px;cursor:pointer;background:#9b59b6;color:#fff;border:none;border-radius:6px'>➕ 新增/更新版本</button>");
            sb.append("</div>");
            sb.append("<h3 style='margin-top:20px'>🗑️ 删除版本配置</h3>");
            sb.append("<div style='display:flex;gap:8px;align-items:center;flex-wrap:wrap'>");
            sb.append("<input id='del_version' placeholder='要删除的版本号' style='padding:8px;border:1px solid #ddd;border-radius:6px;width:200px;box-sizing:border-box'>");
            sb.append("<button onclick='deleteVersion()' style='padding:8px 16px;font-size:13px;cursor:pointer;background:#e74c3c;color:#fff;border:none;border-radius:6px'>🗑️ 删除</button>");
            sb.append("</div>");
            sb.append("<script>");
            sb.append("async function loadConfigToEditor(){");
            sb.append("  try{const r=await fetch('/config_raw');const t=await r.text();document.getElementById('configEditor').value=t;document.getElementById('configEditor').readOnly=false;}catch(e){alert('加载失败:'+e);}");
            sb.append("}");
            sb.append("async function saveFullConfig(){");
            sb.append("  const c=document.getElementById('configEditor').value;if(!c){alert('配置内容不能为空');return;}");
            sb.append("  try{const r=await fetch('/save_config?action=save&config='+encodeURIComponent(c));const d=await r.json();alert(d.msg);if(d.success)location.reload();}catch(e){alert('保存失败:'+e);}");
            sb.append("}");
            sb.append("async function addVersion(){");
            sb.append("  const v=document.getElementById('fv_version').value;const j=document.getElementById('fv_j1').value;const c=document.getElementById('fv_c').value;");
            sb.append("  const a1=document.getElementById('fv_a1').value;const a7=document.getElementById('fv_a7').value;");
            sb.append("  const js=document.getElementById('fv_j1_static').value;const ji=document.getElementById('fv_j1_instance').value;");
            sb.append("  if(!v||!j||!c){alert('版本号、j1、c 为必填项');return;}");
            sb.append("  let url='/save_config?action=add_version&version='+encodeURIComponent(v)+'&j1='+encodeURIComponent(j)+'&c='+encodeURIComponent(c);");
            sb.append("  if(a1)url+='&a1='+encodeURIComponent(a1);if(a7)url+='&a7='+encodeURIComponent(a7);if(js)url+='&j1_static_method='+encodeURIComponent(js);if(ji)url+='&j1_instance_method='+encodeURIComponent(ji);");
            sb.append("  try{const r=await fetch(url);const d=await r.json();alert(d.msg);if(d.success)location.reload();}catch(e){alert('操作失败:'+e);}");
            sb.append("}");
            sb.append("async function deleteVersion(){");
            sb.append("  const v=document.getElementById('del_version').value;if(!v){alert('请输入要删除的版本号');return;}");
            sb.append("  if(!confirm('确定删除版本 '+v+' 的配置吗？'))return;");
            sb.append("  try{const r=await fetch('/save_config?action=delete_version&version='+encodeURIComponent(v));const d=await r.json();alert(d.msg);if(d.success)location.reload();}catch(e){alert('操作失败:'+e);}");
            sb.append("}");
            sb.append("async function resetConfig(){");
            sb.append("  if(!confirm('确定重置为内置默认配置？此操作不可撤销！'))return;");
            sb.append("  try{const r=await fetch('/save_config?action=reset');const d=await r.json();alert(d.msg);location.reload();}catch(e){alert('操作失败:'+e);}");
            sb.append("}");
            sb.append("</script>");
            sb.append("<div class='footer'>");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                sb.append("© 2026 wxcode 插件 | 服务器时间：").append(LocalDateTime.now()).append("");
            }
            sb.append("</div>");
            sb.append("</div>");
            sb.append("</body>");
            sb.append("</html>");
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_HTML, sb.toString());
        } catch (Exception e) {
            e.printStackTrace();
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "服务器内部错误");
        }
    }
}
