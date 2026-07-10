package leng.hook;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import fi.iki.elonen.NanoHTTPD;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.LinkedList;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public class WxLoginHook implements IXposedHookLoadPackage {
    private static final long CALLBACK_TIMEOUT_MS = 15000;
    private static final String DEFAULT_AUTO_APP_ID = "wxaa3a999db5d744c6";
    private static final String TAG = "WX-OwnLogin-Xposed";
    private LoginHttpServer httpServer;
    private boolean isLoginInFlight = false;
    String jsonString = "{\"8.0.49\":{\"j1\":\"u70.k1\",\"c\":\"o60.c\"},\"8.0.62\":{\"j1\":\"of0.j1\",\"c\":\"he0.c\"},\"8.0.70\":{\"j1\":\"yj0.j1\",\"c\":\"ti0.c\"},\"8.0.71\":{\"j1\":\"tk0.j1\",\"c\":\"oj0.c\"},\"8.0.72\":{\"j1\":\"dl0.k1\",\"c\":\"yj0.c\"},\"8.0.74\":{\"j1\":\"gm0.j1\",\"c\":\"bl0.c\"}}";
    private String j1 = "of0.j1";
    private String c = "he0.c";
    private String versionName = "000";

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (!loadPackageParam.packageName.equals("com.tencent.mm")) {
            return;
        }
        try {
            Class<?> cls = Class.forName("android.app.Application");
            Object[] objArr = new Object[2];
            try {
                objArr[0] = Class.forName("android.content.Context");
                objArr[1] = new XC_MethodHook(this) { // from class: leng.hook.WxLoginHook.100000000
                    private final WxLoginHook this$0;

                    {
                        this.this$0 = this;
                    }

                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                        super.afterHookedMethod(methodHookParam);
                        Context context = (Context) methodHookParam.args[0];
                        ClassLoader classLoader = context.getClassLoader();
                        PackageInfo packageInfo = context.getPackageManager().getPackageInfo("com.tencent.mm", 0);
                        this.this$0.versionName = packageInfo.versionName;
                        XposedBridge.log(new StringBuffer().append("当前版本:").append(this.this$0.versionName).toString());
                        try {
                            JSONObject jSONObject = new JSONObject(this.this$0.jsonString).getJSONObject(this.this$0.versionName);
                            this.this$0.j1 = jSONObject.getString("j1");
                            this.this$0.c = jSONObject.getString("c");
                            XposedBridge.log(new StringBuffer().append(new StringBuffer().append(new StringBuffer().append("已读取").append(this.this$0.versionName).toString()).append("配置:").toString()).append(jSONObject).toString());
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        try {
                            this.this$0.httpServer = new LoginHttpServer(this.this$0, 8088, classLoader);
                            this.this$0.httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                            XposedBridge.log(new StringBuffer().append("WX-OwnLogin-Xposed").append(" HTTP 服务启动成功: http://设备IP:8088/login").toString());
                        } catch (IOException e2) {
                            XposedBridge.log(new StringBuffer().append(new StringBuffer().append("WX-OwnLogin-Xposed").append(" HTTP 服务启动失败: ").toString()).append(e2.getMessage()).toString());
                            e2.printStackTrace();
                        }
                    }
                };
                XposedHelpers.findAndHookMethod(cls, "attach", objArr);
            } catch (ClassNotFoundException e) {
                throw new NoClassDefFoundError(e.getMessage());
            }
        } catch (ClassNotFoundException e2) {
            throw new NoClassDefFoundError(e2.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Multi-variable type inference failed */
    public String doLogin(final String str, ClassLoader classLoader) {
        if (this.isLoginInFlight) {
            return "{\"err\":-100,\"msg\":\"登录请求正在处理中\"}";
        }
        this.isLoginInFlight = true;
        final String[] strArr = {null};
        try {
            Class<?> clsFindClass = XposedHelpers.findClass("com.tencent.mm.plugin.appbrand.jsapi.auth.JsApiLogin$LoginTask", classLoader);
            Class clsFindClass2 = XposedHelpers.findClass("com.tencent.mm.plugin.appbrand.jsapi.auth.h2", classLoader);
            Class clsFindClass3 = XposedHelpers.findClass("com.tencent.mm.plugin.appbrand.jsapi.auth.l2", classLoader);
            Class<?> clsFindClass4 = XposedHelpers.findClass(this.c, classLoader);
            Class clsFindClass5 = XposedHelpers.findClass(this.j1, classLoader);
            XposedBridge.log(new StringBuffer().append(new StringBuffer().append("WX-OwnLogin-Xposed").append("发起登录请求: appId=").toString()).append(str).toString());
            final Object objNewInstance = XposedHelpers.newInstance(clsFindClass, new Object[0]);
            setField(objNewInstance, "o", "login");
            setField(objNewInstance, "p", str);
            setField(objNewInstance, "s", new Integer(1));
            setField(objNewInstance, "v", "");
            setField(objNewInstance, "t", new Integer(0));
            setField(objNewInstance, "u", new Integer(0));
            setField(objNewInstance, "A", new Integer(1271));
            XposedHelpers.callMethod(XposedHelpers.callStaticMethod(clsFindClass5, "d", new Object[0]), "g", new Object[]{findHe0cConstructor(clsFindClass4).newInstance(str, new LinkedList(), new Integer(1), "", "", new Integer(0), new Integer(1271), XposedHelpers.newInstance(clsFindClass3, new Object[]{objNewInstance, clsFindClass2.getConstructor(clsFindClass).newInstance(objNewInstance)}))});
            final long jCurrentTimeMillis = System.currentTimeMillis();
            final Handler handler = new Handler(Looper.getMainLooper());
            final Object obj = new Object();
            Runnable runnable = new Runnable(this) { // from class: leng.hook.WxLoginHook.100000001
                private final WxLoginHook this$0;

                {
                    this.this$0 = this;
                }

                @Override // java.lang.Runnable
                public void run() {
                    String str2;
                    String str3;
                    synchronized (obj) {
                        try {
                            str2 = (String) this.this$0.getField(objNewInstance, "r");
                            str3 = (String) this.this$0.getField(objNewInstance, "q");
                        } catch (Throwable th) {
                            XposedBridge.log(new StringBuffer().append(new StringBuffer().append("WX-OwnLogin-Xposed").append(" 轮询异常: ").toString()).append(th.getMessage()).toString());
                            handler.postDelayed(this, 200);
                        }
                        if (str2 == null) {
                            if (System.currentTimeMillis() - jCurrentTimeMillis <= 15000) {
                                handler.postDelayed(this, 200);
                                return;
                            } else {
                                strArr[0] = "{\"err\":-210,\"msg\":\"登录请求超时\"}";
                                obj.notify();
                                return;
                            }
                        }
                        String strClassifyCode = this.this$0.classifyCode(str3);
                        String[] strArr2 = strArr;
                        Object[] objArr = new Object[5];
                        objArr[0] = str;
                        objArr[1] = str2;
                        objArr[2] = str3;
                        objArr[3] = strClassifyCode;
                        objArr[4] = new Integer(str3 == null ? 0 : str3.length());
                        strArr2[0] = String.format("{\"err\":0,\"msg\":\"success\",\"appId\":\"%s\",\"status\":\"%s\",\"code\":\"%s\",\"codeType\":\"%s\",\"codeLength\":%d}", objArr);
                        obj.notify();
                    }
                }
            };
            synchronized (obj) {
                handler.post(runnable);
                obj.wait(16000L);
            }
            if (strArr[0] == null) {
                strArr[0] = "{\"err\":-210,\"msg\":\"登录请求超时\"}";
            }
        } finally {
            try {
            } catch (Throwable th) {
            }
        }
        this.isLoginInFlight = false;
        return strArr[0];
    }

    private void setField(Object obj, String str, Object obj2) throws Throwable {
        Field declaredField = obj.getClass().getDeclaredField(str);
        declaredField.setAccessible(true);
        declaredField.set(obj, obj2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public Object getField(Object obj, String str) throws Throwable {
        Field declaredField = obj.getClass().getDeclaredField(str);
        declaredField.setAccessible(true);
        return declaredField.get(obj);
    }

    /* JADX WARN: Removed duplicated region for block: B:23:0x0043  */
    /* JADX WARN: Removed duplicated region for block: B:43:0x0079  */
    /* JADX WARN: Removed duplicated region for block: B:53:0x0097  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private java.lang.reflect.Constructor<?> findHe0cConstructor(java.lang.Class<?> r17) {
        /*
            Method dump skipped, instruction units count: 216
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: leng.hook.WxLoginHook.findHe0cConstructor(java.lang.Class):java.lang.reflect.Constructor");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String classifyCode(String str) {
        if (str == null || str.isEmpty()) {
            return "invalid";
        }
        return str.matches("^[0-9a-fA-F]+$") ? "hex" : str.matches("^[A-Za-z0-9+\\/=]+$") ? "base64" : str.matches("^[A-Za-z0-9_\\-]+$") ? "base64url" : str.matches("^[A-Za-z0-9]+$") ? "alnum" : "other";
    }

    class LoginHttpServer extends NanoHTTPD {
        private final ClassLoader classLoader;
        private final WxLoginHook this$0;

        public LoginHttpServer(WxLoginHook wxLoginHook, int i, ClassLoader classLoader) {
            super(i);
            this.this$0 = wxLoginHook;
            this.classLoader = classLoader;
        }

        @Override // fi.iki.elonen.NanoHTTPD
        public NanoHTTPD.Response serve(NanoHTTPD.IHTTPSession iHTTPSession) {
            if (!iHTTPSession.getMethod().equals(NanoHTTPD.Method.GET)) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "application/json", "{\"err\":-404,\"msg\":\"接口不存在\"}");
            }
            if (!iHTTPSession.getUri().equals("/login")) {
                try {
                    JSONObject jSONObject = new JSONObject(this.this$0.jsonString);
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
                    sb.append("</style>");
                    sb.append("</head>");
                    sb.append("<body>");
                    sb.append("<div class='container'>");
                    sb.append("<h1>📦 wxcode </h1>");
                    sb.append("<h2>📖 使用教程</h2>");
                    sb.append("<p><strong>GET 访问接口：</strong> <code>/login?appId=应用id</code></p>");
                    sb.append("<p><strong>返回结果：</strong> 返回对应的 code 值</p>");
                    sb.append("<p><a href='/login?appId=wxaa3a999db5d744c6'>👉 点击这里测试接口</a></p>");
                    sb.append("<h2>📋 适配版本列表</h2>");
                    sb.append("<table>");
                    sb.append("<thead>");
                    sb.append("<tr>");
                    sb.append("<th>版本号</th>");
                    sb.append("<th>j1 参数</th>");
                    sb.append("<th>c 参数</th>");
                    sb.append("</tr>");
                    sb.append("</thead>");
                    sb.append("<tbody>");
                    Iterator<String> itKeys = jSONObject.keys();
                    while (itKeys.hasNext()) {
                        String next = itKeys.next();
                        JSONObject jSONObject2 = jSONObject.getJSONObject(next);
                        String string = jSONObject2.getString("j1");
                        String string2 = jSONObject2.getString("c");
                        sb.append("<tr>");
                        sb.append("<td class='version'>").append(next).append("</td>");
                        sb.append("<td class='code'>").append(string).append("</td>");
                        sb.append("<td class='code'>").append(string2).append("</td>");
                        sb.append("</tr>");
                    }
                    sb.append("</tbody>");
                    sb.append("</table>");
                    sb.append("<div class='footer'>");
                    sb.append("© 2026 wxcode 插件 | 服务器时间：").append(LocalDateTime.now()).append("");
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
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", this.this$0.doLogin(iHTTPSession.getParms().getOrDefault("appId", "wxaa3a999db5d744c6"), this.classLoader));
        }
    }
}
