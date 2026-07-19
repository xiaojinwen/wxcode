package xiaojw.hook;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.UserHandle;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import fi.iki.elonen.NanoHTTPD;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedList;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONObject;

public class WxLoginHook implements IXposedHookLoadPackage {
    private static final long CALLBACK_TIMEOUT_MS = 15000;
    private static final String TAG = "xiaojw-wxcode";
    private static final String WECHAT_PACKAGE_PREFIX = "com.tencent.mm";

    // SharedPreferences 键名集中管理（与 ServerContext 常量保持一致）
    private static final String SP_NAME = ServerContext.SP_NAME;
    private static final String SP_KEY_VERSION_CONFIG = ServerContext.SP_KEY_VERSION_CONFIG;
    private static final String SP_KEY_POWER_SAVER_MODE = ServerContext.SP_KEY_POWER_SAVER_MODE;

    private LoginHttpServer httpServer;
    // 用 CAS 保证并发登录请求串行化,避免 check-then-act 竞态
    private final java.util.concurrent.atomic.AtomicBoolean isLoginInFlight = new java.util.concurrent.atomic.AtomicBoolean(false);
    private Context appContext;
    // 多线程共享(HTTP线程/Activity主线程/Service线程),需保证可见性
    private volatile HandlerThread workerThread;
    private volatile Handler workerHandler;
    private volatile boolean isForegroundServiceRunning = false;
    private Application wechatApplication;

    // 版本配置JSON（内置默认配置）
    // a1 = 1参回调(o2)实现类，构造: (LoginTask)；a7 = 2参回调(h80/j)实现类，构造: (LoginTask, o2)
    // 8.0.76 起 h2/l2 混淆位移：h2->i2(a1)、l2->m2(a7)，构造函数签名随版本变化，故均纳入配置
    //
    // 8.0.49 配置来源：通过 jadx 反编译分析 JsApiLogin$LoginTask.java
    // - j1=u70.k1: 第185行 u70.k1.d().f(cVar)
    // - c=o60.c: 第179行 o60.c cVar = new o60.c(...)
    // - a1=b2: 第167行 b2 b2Var = new b2(this)
    // - a7=f2: 第177行 f2 f2Var = new f2(this, b2Var)
    //
    // 配置合并策略：先加载 common 公共配置，再用版本特定配置覆盖
    private String jsonString = """
        {
            "common": {"j1_static_method": "d", "j1_instance_method": "g"},
            "8.0.49": {"j1": "u70.k1", "c": "o60.c", "a1": "plugin.appbrand.jsapi.auth.b2", "a7": "plugin.appbrand.jsapi.auth.f2", "j1_instance_method": "f"},
            "8.0.62": {"j1": "of0.j1", "c": "he0.c", "a1": "plugin.appbrand.jsapi.auth.h2", "a7": "plugin.appbrand.jsapi.auth.l2"},
            "8.0.70": {"j1": "yj0.j1", "c": "ti0.c", "a1": "plugin.appbrand.jsapi.auth.h2", "a7": "plugin.appbrand.jsapi.auth.l2"},
            "8.0.71": {"j1": "tk0.j1", "c": "oj0.c", "a1": "plugin.appbrand.jsapi.auth.h2", "a7": "plugin.appbrand.jsapi.auth.l2"},
            "8.0.72": {"j1": "dl0.k1", "c": "yj0.c", "a1": "plugin.appbrand.jsapi.auth.h2", "a7": "plugin.appbrand.jsapi.auth.l2"},
            "8.0.74": {"j1": "gm0.j1", "c": "bl0.c", "a1": "plugin.appbrand.jsapi.auth.h2", "a7": "plugin.appbrand.jsapi.auth.l2"},
            "8.0.76": {"j1": "hm0.j1", "c": "cl0.c", "a1": "plugin.appbrand.jsapi.auth.i2", "a7": "plugin.appbrand.jsapi.auth.m2"}
        }""";

    private String j1 = "of0.j1";
    private String c = "he0.c";
    private String a1 = "plugin.appbrand.jsapi.auth.h2";
    private String a7 = "plugin.appbrand.jsapi.auth.l2";
    // 内置默认配置原文，永不修改——用于与用户持久化配置合并
    private String defaultConfigJson;
    private String j1StaticMethod = "d";
    private String j1InstanceMethod = "g";
    private static volatile String versionName = "000";
    private static volatile String currentPackageName = "";
    private static volatile int currentUserId = 0;
    private static volatile int httpPort = 8088;
    // 内存实例表：跨用户实例通过向主端口注册通知汇聚，无需共享文件系统即可多用户共享
    private static final java.util.Map<String, JSONObject> instanceMap = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 判断是否微信包名（系统分身与主应用包名相同）
     */
    private boolean isWeChatPackage(String packageName) {
        return WECHAT_PACKAGE_PREFIX.equals(packageName);
    }

    /**
     * 获取当前进程UserID，区分多用户/系统分身。
     * 三种 fallback 策略已提取为独立方法，提高可读性。
     */
    private int getUserId() {
        Integer uid = tryReflectionMyUserId();
        if (uid != null) return uid;
        uid = tryProcessMyUserHandle();
        if (uid != null) return uid;
        uid = tryDataDirParsing();
        if (uid != null) return uid;
        XposedBridge.log(TAG + " 无法获取UID，默认0");
        return 0;
    }

    /** 策略1: UserHandle.myUserId() 反射调用 */
    private Integer tryReflectionMyUserId() {
        try {
            Method myUserIdMethod = UserHandle.class.getDeclaredMethod("myUserId");
            myUserIdMethod.setAccessible(true);
            return (int) myUserIdMethod.invoke(null);
        } catch (Exception e) {
            XposedBridge.log(TAG + " UserHandle.myUserId 失败: " + e.getMessage());
            return null;
        }
    }

    /** 策略2: Process.myUserHandle() + mHandle Field */
    private Integer tryProcessMyUserHandle() {
        try {
            UserHandle userHandle = android.os.Process.myUserHandle();
            if (userHandle != null) {
                Field userIdField = UserHandle.class.getDeclaredField("mHandle");
                userIdField.setAccessible(true);
                int userId = userIdField.getInt(userHandle);
                XposedBridge.log(TAG + " Process.myUserHandle 获取UID: " + userId);
                return userId;
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " Process.myUserHandle 失败: " + e.getMessage());
        }
        return null;
    }

    /** 策略3: 解析 dataDir 路径 */
    private Integer tryDataDirParsing() {
        try {
            String dataDir = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dataDir = appContext.getDataDir() != null ? appContext.getDataDir().getAbsolutePath() : null;
            }
            if (dataDir != null && dataDir.contains("/data/user/")) {
                String[] parts = dataDir.split("/");
                if (parts.length >= 4 && "data".equals(parts[1]) && "user".equals(parts[2])) {
                    int userId = Integer.parseInt(parts[3]);
                    XposedBridge.log(TAG + " 数据目录解析UID: " + userId);
                    return userId;
                }
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " 目录解析UID失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 根据 User ID 计算唯一端口，系统分身包名均为 com.tencent.mm
     */
    private int calculatePort(int userId) {
        if (userId <= 0) {
            XposedBridge.log(TAG + " [" + currentPackageName + "] 端口:8088 UID:" + userId);
            return 8088;
        }
        int port = userId < 100 ? 8088 + userId : 8200 + (userId % 100);
        XposedBridge.log(TAG + " [" + currentPackageName + "] 端口:" + port + " UID:" + userId);
        return port;
    }

    /**
     * 检查端口是否已被占用（跨进程有效）
     */
    private boolean isPortInUse(int port) {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(port)) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * 将实例注册到内存实例表（跨用户共享的主要数据源，无需共享文件系统）
     */
    private static void registerInstanceInMemory(String packageName, int userId, int port, String version) {
        try {
            String key = packageName + "_" + userId;
            JSONObject item = new JSONObject();
            item.put("packageName", packageName);
            item.put("userId", userId);
            item.put("port", port);
            item.put("version", version);
            instanceMap.put(key, item);
            XposedBridge.log(TAG + " 内存注册实例: " + key + " :" + port);
        } catch (Exception e) {
            XposedBridge.log(TAG + " 内存注册失败: " + e.getMessage());
        }
    }

    /**
     * 读取内存实例表，实例注册后不会过期，跟随微信生命周期
     */
    private JSONArray getMemoryInstances() {
        JSONArray result = new JSONArray();
        for (java.util.Map.Entry<String, JSONObject> entry : instanceMap.entrySet()) {
            try {
                result.put(entry.getValue());
            } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * 非主端口实例：向主端口(8088)发送注册通知
     */
    private static void notifyMasterRegister(int port, int userId, String version) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String url = "http://127.0.0.1:" + ServerContext.MASTER_PORT + "/register?port=" + port
                        + "&userId=" + userId
                        + "&version=" + URLEncoder.encode(version, "UTF-8")
                        + "&packageName=" + URLEncoder.encode(currentPackageName, "UTF-8");
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                XposedBridge.log(TAG + " 向主端口注册完成 HTTP:" + code);
            } catch (Exception e) {
                XposedBridge.log(TAG + " 向主端口注册失败(127.0.0.1:" + ServerContext.MASTER_PORT + " 不可达?): " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }


    /**
     * 非主端口实例：从主端口拉取汇总后的实例列表
     */
    private JSONArray fetchMasterInstances() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + ServerContext.MASTER_PORT + "/instances");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            JSONObject obj = new JSONObject(sb.toString());
            return obj.getJSONArray("instances");
        } catch (Exception e) {
            XposedBridge.log(TAG + " 从主端口拉取实例失败: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 初始化全局配置、生命周期与常驻保活
     */
    private void initConfig(Context context) {
        this.appContext = context;
        if (context instanceof Application) this.wechatApplication = (Application) context;
        startWorkerThread();
        // 读取持久化的保活模式，确保微信重启后用户上次选择不丢失
        try {
            KeepAliveService.powerSaverMode = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                    .getBoolean(SP_KEY_POWER_SAVER_MODE, false);
            XposedBridge.log(TAG + " 保活模式: " + (KeepAliveService.powerSaverMode ? "省电模式" : "性能模式"));
        } catch (Exception ignored) {}
        // 记录内置默认配置原文，用于后续合并（确保内置更新不被覆盖）
        defaultConfigJson = jsonString;
        // 读取持久化的版本配置（用户自行新增的版本适配），与内置默认配置合并
        try {
            String savedConfig = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                    .getString(SP_KEY_VERSION_CONFIG, null);
            if (savedConfig != null && !savedConfig.isEmpty()) {
                JSONObject baseCfg = new JSONObject(defaultConfigJson);
                JSONObject userCfg = new JSONObject(savedConfig);
                Iterator<String> keys = userCfg.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    baseCfg.put(key, userCfg.get(key));
                }
                jsonString = baseCfg.toString();
                XposedBridge.log(TAG + " 已合并持久化的版本配置（用户配置覆盖/追加到内置默认配置之上）");
            }
        } catch (Exception ignored) {}
        // 进程级常驻：HTTP server 启动即拉起前台 Service，确保所有 HTTP 线程
        // （含 NanoHTTPD accept）始终受前台优先级保护，不被 Doze 限流。
        startForegroundService();
    }

    /**
     * 前台保活模式：真正启动一个前台 Service，将进程优先级提升到前台，
     * 从而绕过系统对后台应用的网络限流/Doze延迟，并规避"后台启动Activity被拦截"的问题。
     */
    private void startForegroundService() {
        if (isForegroundServiceRunning) return;
        try {
            Intent intent = new Intent(appContext, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
            isForegroundServiceRunning = true;
            XposedBridge.log(TAG + " 前台保活Service已启动（进程优先级提升至前台）");
        } catch (Exception e) {
            XposedBridge.log(TAG + " 前台Service启动失败[" + e.getClass().getSimpleName() + "]:" + e.getMessage());
            isForegroundServiceRunning = false;
        }
    }

    /**
     * 停止前台保活 Service（WakeLock 由 Service.onDestroy 自行释放）
     */
    private void stopForegroundService() {
        try {
            appContext.stopService(new Intent(appContext, KeepAliveService.class));
        } catch (Exception ignored) {}
        isForegroundServiceRunning = false;
    }

    /**
     * 保存版本配置到 SharedPreferences（持久化），只保存用户提供的配置，
     * 启动时再与内置默认配置合并。
     */
    private void saveConfig(String userConfigJson) {
        try {
            JSONObject userCfg = new JSONObject(userConfigJson);
            appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                    .edit().putString(SP_KEY_VERSION_CONFIG, userConfigJson).apply();
            // 与内置默认配置合并（defaultConfigJson），确保内置版本更新不被覆盖
            String baseStr = defaultConfigJson != null ? defaultConfigJson : jsonString;
            JSONObject merged = new JSONObject(baseStr);
            Iterator<String> keys = userCfg.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                merged.put(key, userCfg.get(key));
            }
            jsonString = merged.toString();
            XposedBridge.log(TAG + " 版本配置已持久化保存（当前内存中为合并结果）");
            // 同步更新 ctx.jsonString，确保 HTML 缓存重建时读到最新配置
            if (httpServer != null && httpServer.getServerContext() != null) {
                httpServer.getServerContext().jsonString = jsonString;
                httpServer.getServerContext().configGeneration++;
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " 保存版本配置失败: " + e.getMessage());
        }
    }

    /**
     * 切换保活模式：更新标志 + 异步重启 KeepAliveService 以应用新模式。
     * 用 Handler.postDelayed 替代 Thread.sleep，更可靠且不阻塞调用线程。
     */
    private void switchPowerMode(boolean toPowerSaver) {
        if (KeepAliveService.powerSaverMode == toPowerSaver) return;
        KeepAliveService.powerSaverMode = toPowerSaver;
        // 持久化到 SharedPreferences，确保微信重启后模式选择不丢失
        try {
            appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(SP_KEY_POWER_SAVER_MODE, toPowerSaver).apply();
        } catch (Exception ignored) {}
        XposedBridge.log(TAG + " 切换保活模式: " + (toPowerSaver ? "省电模式" : "性能模式"));
        stopForegroundService();
        isForegroundServiceRunning = false;
        // 用 Handler 延迟启动替代 Thread.sleep，避免阻塞 HTTP 线程
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.postDelayed(this::startForegroundService, 200);
    }

    /**
     * 后台登录时确保前台保活 Service 已拉起，避免接口请求被系统限流卡住。
     */
    private void ensureForegroundForBackground() {
        if (!isForegroundServiceRunning) {
            startForegroundService();
        }
    }

    /**
     * 等待前台 Service 真正 startForeground 完成（startForegroundService 是异步的）。
     * 只有真正就绪（fgServiceActive=true）后，Android 10 才允许从后台启动 Activity。
     */
    private void waitForForegroundService(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!KeepAliveService.fgServiceActive && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
        }
    }


    /**
     * 启动后台轮询子线程
     */
    private void startWorkerThread() {
        if (workerThread != null && workerThread.isAlive()) return;
        workerThread = new HandlerThread("wxcode_worker_" + httpPort);
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        XposedBridge.log(TAG + " 工作线程启动成功");
    }

    /**
     * 安全停止工作线程
     */
    private void stopWorkerThread() {
        HandlerThread ht = workerThread;
        workerThread = null;
        workerHandler = null;
        if (ht != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                ht.quitSafely();
            }
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (!isWeChatPackage(loadPackageParam.packageName)) return;
        // 只在主进程初始化，子进程（如 :tools、:appbrand 等）跳过
        String processName = loadPackageParam.processName;
        if (!loadPackageParam.packageName.equals(processName)) {
            return;
        }
        currentPackageName = loadPackageParam.packageName;
        Class<?> appCls = Class.forName("android.app.Application");
        XposedHelpers.findAndHookMethod(appCls, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.args[0];
                ClassLoader classLoader = context.getClassLoader();
                appContext = context;
                currentUserId = getUserId();
                httpPort = calculatePort(currentUserId);
                // 检查端口是否已被占用（防止多进程重复初始化）
                if (isPortInUse(httpPort)) {
                    XposedBridge.log(TAG + " 端口 " + httpPort + " 已被占用，跳过初始化");
                    return;
                }
                PackageInfo pkgInfo = context.getPackageManager().getPackageInfo(currentPackageName, 0);
                versionName = pkgInfo.versionName;
                XposedBridge.log(TAG + " [" + currentPackageName + "] UID:" + currentUserId + " Ver:" + versionName + " Port:" + httpPort);
                
                try {
                    JSONObject rootCfg = new JSONObject(jsonString);
                    // 先加载公共配置
                    JSONObject commonCfg = rootCfg.optJSONObject("common");
                    if (commonCfg != null) {
                        j1StaticMethod = commonCfg.optString("j1_static_method", j1StaticMethod);
                        j1InstanceMethod = commonCfg.optString("j1_instance_method", j1InstanceMethod);
                        XposedBridge.log(TAG + " 公共配置加载成功: " + commonCfg);
                    }
                    // 再加载版本特定配置（覆盖公共配置）
                    JSONObject verCfg = rootCfg.optJSONObject(versionName);
                    if (verCfg != null) {
                        j1 = verCfg.optString("j1", j1);
                        c = verCfg.optString("c", c);
                        a1 = verCfg.optString("a1", a1);
                        a7 = verCfg.optString("a7", a7);
                        // 版本配置可覆盖公共配置中的方法名
                        j1StaticMethod = verCfg.optString("j1_static_method", j1StaticMethod);
                        j1InstanceMethod = verCfg.optString("j1_instance_method", j1InstanceMethod);
                        XposedBridge.log(TAG + " 版本配置加载成功: " + verCfg);
                    } else {
                        XposedBridge.log(TAG + " 版本配置不存在，使用默认配置");
                    }
                } catch (Exception e) {
                    XposedBridge.log(TAG + " 版本配置加载失败: " + e.getMessage());
                }
                try {
                    ServerContext serverContext = buildServerContext();
                    httpServer = new LoginHttpServer(serverContext, httpPort, classLoader);
                    httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                    XposedBridge.log(TAG + " HTTP服务启动成功 http://0.0.0.0:" + httpPort);
                    // 本地注册自己到内存实例表
                    registerInstanceInMemory(currentPackageName, currentUserId, httpPort, versionName);
                    // 非主端口实例：向主端口(8088)发送注册通知，由主端口汇总所有端口
                    if (httpPort != ServerContext.MASTER_PORT) {
                        notifyMasterRegister(httpPort, currentUserId, versionName);
                    } else {
                        XposedBridge.log(TAG + " 当前即主端口(8088)，负责汇总所有实例");
                    }
                    initConfig(context);
                } catch (IOException e) {
                    XposedBridge.log(TAG + " HTTP服务启动失败:" + e.getMessage());
                }
            }
        });
    }

    /**
     * 执行小程序登录，获取code
     */
    public String doLogin(final String str, ClassLoader classLoader) {
        if (!isLoginInFlight.compareAndSet(false, true)) {
            return "{\"err\":-100,\"msg\":\"登录请求正在处理中\"}";
        }
        final String[] res = {null};
        try {
            final long timeoutMs = 30000;
            XposedBridge.log(TAG + " doLogin 前台Service运行=" + isForegroundServiceRunning + " 超时=" + timeoutMs + "ms");
            Class<?> LoginTaskCls = XposedHelpers.findClass("com.tencent.mm.plugin.appbrand.jsapi.auth.JsApiLogin$LoginTask", classLoader);
            Class<?> h2Cls = XposedHelpers.findClass(ServerContext.COMMON_PACKAGE + "." + this.a1, classLoader);
            Class<?> l2Cls = XposedHelpers.findClass(ServerContext.COMMON_PACKAGE + "." + this.a7, classLoader);
            Class<?> cCls = XposedHelpers.findClass(this.c, classLoader);
            Class<?> j1Cls = XposedHelpers.findClass(this.j1, classLoader);
            XposedBridge.log(TAG + " a1=" + ServerContext.COMMON_PACKAGE + "." + this.a1 + " a7=" + ServerContext.COMMON_PACKAGE + "." + this.a7);
            XposedBridge.log(TAG + " 发起登录 appId=" + str);
            // 后台时确保前台 Service 就绪，避免网络请求被 Doze 限流
            ensureForegroundForBackground();
            waitForForegroundService(1000);
            Object loginTask = XposedHelpers.newInstance(LoginTaskCls);
            setField(loginTask, "o", "login");
            setField(loginTask, "p", str);
            setField(loginTask, "s", 1);
            setField(loginTask, "v", "");
            setField(loginTask, "t", 0);
            setField(loginTask, "u", 0);
            setField(loginTask, "A", 1271);
            Constructor<?> ctor = findHe0cConstructor(cCls);
            Constructor<?> h2Ctor = findSingleArgConstructor(h2Cls, LoginTaskCls);
            Object h2Obj = h2Ctor.newInstance(loginTask);
            Object l2Obj = XposedHelpers.newInstance(l2Cls, loginTask, h2Obj);
            Object cObj = ctor.newInstance(str, new LinkedList<>(), 1, "", "", 0, 1271, l2Obj);
            XposedHelpers.callMethod(XposedHelpers.callStaticMethod(j1Cls, j1StaticMethod), j1InstanceMethod, cObj);
            long start = System.currentTimeMillis();
            Handler handler;
            // 单一策略：子线程轮询，未就绪则重建，主线程兜底
            if (workerHandler == null || workerThread == null || !workerThread.isAlive()) {
                startWorkerThread();
            }
            handler = workerHandler != null ? workerHandler : new Handler(Looper.getMainLooper());
            Object lockObj = new Object();
            Runnable pollTask = new Runnable() {
                @Override
                public void run() {
                    synchronized (lockObj) {
                        try {
                            String code = (String) getField(loginTask, "r");
                            String rawCode = (String) getField(loginTask, "q");
                            if (code == null) {
                                if (System.currentTimeMillis() - start <= timeoutMs) {
                                    handler.postDelayed(this, 200);
                                    return;
                                } else {
                                    // 超时：探测字段可读性，区分"字段名不匹配(版本适配)"与"确实未返回"
                                    String probe;
                                    try {
                                        Object r = getField(loginTask, "r");
                                        Object q = getField(loginTask, "q");
                                        probe = "r=" + r + ",q=" + q;
                                    } catch (Throwable pe) {
                                        probe = "字段读取失败[" + pe.getClass().getSimpleName() + "]:" + pe.getMessage();
                                    }
                                    XposedBridge.log(TAG + " 登录超时 probe:" + probe);
                                    res[0] = "{\"err\":-210,\"msg\":\"登录超时\"}";
                                    lockObj.notify();
                                    return;
                                }
                            }
                            String type = classifyCode(rawCode);
                            res[0] = String.format("{\"err\":0,\"msg\":\"success\",\"appId\":\"%s\",\"status\":\"%s\",\"code\":\"%s\",\"codeType\":\"%s\",\"codeLength\":%d}",
                                    str, code, rawCode, type, rawCode == null ? 0 : rawCode.length());
                            lockObj.notify();
                        } catch (Throwable e) {
                            XposedBridge.log(TAG + " 轮询异常[" + e.getClass().getSimpleName() + "]:" + e.getMessage());
                            handler.postDelayed(this, 200);
                        }
                    }
                }
            };
            synchronized (lockObj) {
                handler.post(pollTask);
                long deadline = System.currentTimeMillis() + timeoutMs + 1000;
                while (res[0] == null) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    lockObj.wait(remaining);
                }
            }
            if (res[0] == null) res[0] = "{\"err\":-210,\"msg\":\"登录超时\"}";
        } catch (Throwable e) {
            XposedBridge.log(TAG + " doLogin异常:" + e.getMessage());
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().replace("\"", "\\\"");
            res[0] = "{\"err\":-500,\"msg\":\"" + msg + "\"}";
        } finally {
            isLoginInFlight.set(false);
        }
        return res[0];
    }

    /**
     * 反射设置对象字段
     */
    private void setField(Object obj, String name, Object val) throws Throwable {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, val);
    }

    /**
     * 反射读取对象字段
     */
    private Object getField(Object obj, String name) throws Throwable {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    /**
     * 匹配8参数构造函数，兼容微信内部类。
     * 使用 getParameterTypes().length 替代 getParameterCount()，兼容 API < 26。
     */
    private Constructor<?> findHe0cConstructor(Class<?> clazz) {
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (c.getParameterTypes().length == 8) {
                c.setAccessible(true);
                return c;
            }
        }
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (c.getParameterTypes().length >= 6) {
                c.setAccessible(true);
                return c;
            }
        }
        throw new RuntimeException("未找到目标构造函数:" + clazz.getName());
    }

    /**
     * 查找 a1(原h2) 的 1参构造函数 (LoginTask)。
     * 部分版本（如 8.0.76）构造函数签名发生混淆位移，故优先按"首参可接收 LoginTask"匹配，
     * 匹配不到再回退到精确 (LoginTask) 签名，以兼容旧版。
     */
    private Constructor<?> findSingleArgConstructor(Class<?> clazz, Class<?> loginTaskCls) {
        Constructor<?> exact = null;
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            Class<?>[] pts = c.getParameterTypes();
            if (pts.length == 1) {
                if (pts[0].isAssignableFrom(loginTaskCls)) {
                    c.setAccessible(true);
                    return c;
                }
                if (exact == null) exact = c;
            }
        }
        if (exact != null) {
            XposedBridge.log(TAG + " [a1] 未找到精确 (LoginTask) 构造，回退单参构造: " + exact);
            exact.setAccessible(true);
            return exact;
        }
        try {
            Constructor<?> c = clazz.getConstructor(loginTaskCls);
            c.setAccessible(true);
            return c;
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("未找到 a1 单参构造函数:" + clazz.getName());
        }
    }

    /**
     * 计算一组字符串的最长公共前缀，并回退到最后一个 '.' 之后断开，
     * 保证保留完整的类名段（如 com.tencent...auth.），避免在前缀中间截断。
     */
    private String longestCommonPrefix(java.util.List<String> strs) {
        if (strs == null || strs.isEmpty()) return "";
        String prefix = strs.get(0);
        for (int i = 1; i < strs.size(); i++) {
            String s = strs.get(i);
            int j = 0;
            int len = Math.min(prefix.length(), s.length());
            while (j < len && prefix.charAt(j) == s.charAt(j)) j++;
            prefix = prefix.substring(0, j);
            if (prefix.isEmpty()) break;
        }
        int lastDot = prefix.lastIndexOf('.');
        if (lastDot > 0) prefix = prefix.substring(0, lastDot + 1);
        return prefix;
    }

    /**
     * 将类名公共前缀替换为省略号，仅保留尾部不同的类名段。
     */
    private String shortenClass(String s, String prefix) {
        if (s == null || prefix == null || prefix.isEmpty()) return s;
        if (s.startsWith(prefix)) return "…" + s.substring(prefix.length());
        return s;
    }

    /**
     * 识别code编码类型
     */
    private String classifyCode(String str) {
        if (str == null || str.isEmpty()) return "invalid";
        if (str.matches("^[0-9a-fA-F]+$")) return "hex";
        if (str.matches("^[A-Za-z0-9+\\/=]+$")) return "base64";
        if (str.matches("^[A-Za-z0-9_\\-]+$")) return "base64url";
        if (str.matches("^[A-Za-z0-9]+$")) return "alnum";
        return "other";
    }

    /**
     * 构建 ServerContext，将当前实例的字段和方法注入到回调接口中，
     * 供 LoginHttpServer 解耦使用。
     */
    private ServerContext buildServerContext() {
        ServerContext ctx = new ServerContext();
        ctx.currentPackageName = currentPackageName;
        ctx.currentUserId = currentUserId;
        ctx.httpPort = httpPort;
        ctx.versionName = versionName;
        ctx.j1 = this.j1;
        ctx.c = this.c;
        ctx.a1 = this.a1;
        ctx.a7 = this.a7;
        ctx.j1StaticMethod = this.j1StaticMethod;
        ctx.j1InstanceMethod = this.j1InstanceMethod;
        ctx.jsonString = this.jsonString;
        ctx.methods = new ServerContext.Callback() {
            @Override
            public void saveConfig(String configJson) {
                WxLoginHook.this.saveConfig(configJson);
            }
            @Override
            public String doLogin(String appId, ClassLoader cl) {
                return WxLoginHook.this.doLogin(appId, cl);
            }
            @Override
            public JSONArray getMemoryInstances() {
                return WxLoginHook.this.getMemoryInstances();
            }
            @Override
            public JSONArray fetchMasterInstances() {
                return WxLoginHook.this.fetchMasterInstances();
            }
            @Override
            public void switchPowerMode(boolean powerSaver) {
                WxLoginHook.this.switchPowerMode(powerSaver);
            }
            @Override
            public void registerInstanceInMemory(String pkg, int uid, int port, String ver) {
                WxLoginHook.this.registerInstanceInMemory(pkg, uid, port, ver);
            }
            @Override
            public void resetConfig() {
                WxLoginHook.this.resetConfig();
            }
            @Override
            public String longestCommonPrefix(java.util.List<String> strs) {
                return WxLoginHook.this.longestCommonPrefix(strs);
            }
            @Override
            public String shortenClass(String s, String prefix) {
                return WxLoginHook.this.shortenClass(s, prefix);
            }
        };
        return ctx;
    }

    /**
     * 重置版本配置为默认值（仅移除持久化的用户配置，不影响内置默认配置）
     */
    private void resetConfig() {
        appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().remove(SP_KEY_VERSION_CONFIG).apply();
        // 回退到内置默认配置并同步 ctx
        jsonString = defaultConfigJson;
        if (httpServer != null && httpServer.getServerContext() != null) {
            httpServer.getServerContext().jsonString = jsonString;
            httpServer.getServerContext().configGeneration++;
        }
    }
}
