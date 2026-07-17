package xiaojw.hook;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.app.ActivityManager;
import android.app.Application;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.os.SystemClock;
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
import java.time.LocalDateTime;
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
    private static final String DEFAULT_AUTO_APP_ID = "wxaa3a999db5d744c6";
    private static final String TAG = "xiaojw-wxcode";
    private static final String WECHAT_PACKAGE_PREFIX = "com.tencent.mm";

    private LoginHttpServer httpServer;
    // 用 CAS 保证并发登录请求串行化,避免 check-then-act 竞态
    private final java.util.concurrent.atomic.AtomicBoolean isLoginInFlight = new java.util.concurrent.atomic.AtomicBoolean(false);
    private Context appContext;
    // 多线程共享(HTTP线程/Activity主线程/Service线程),需保证可见性
    private volatile HandlerThread workerThread;
    private volatile Handler workerHandler;
    private volatile boolean isForegroundServiceRunning = false;
    // static 标志：供 KeepAliveService（static 内部类）上报"已真正 startForeground 完成"。
    // 仅当它为 true 时，Android 10 的"有前台Service可后台启动Activity"豁免才成立。
    private static volatile boolean fgServiceActive = false;
    // 省电模式：true=AlarmManager 定时唤醒(省电但响应有延迟)，false=常驻 WakeLock(即时响应)
    private static volatile boolean powerSaverMode = false;
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
    private static final String COMMON_PACKAGE = "com.tencent.mm";
    private String j1StaticMethod = "d";
    private String j1InstanceMethod = "g";
    private static volatile String versionName = "000";
    private static volatile String currentPackageName = "";
    private static volatile int currentUserId = 0;
    private static volatile int httpPort = 8088;
    // 主端口：所有分身实例向该端口(用户0的8088)发送注册通知，由其汇总所有启动的端口
    private static final int MASTER_PORT = 8088;
    // 前台保活 Service 通知ID与渠道ID（真正提升进程优先级，避免后台网络限流）
    private static final int FOREGROUND_NOTIF_ID = 1001;
    private static final String FOREGROUND_CHANNEL_ID = "wxcode_foreground_keepalive";

    // 内存实例表：跨用户实例通过向主端口注册通知汇聚，无需共享文件系统即可多用户共享
    private static final java.util.Map<String, JSONObject> instanceMap = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 判断是否微信包名（系统分身与主应用包名相同）
     */
    private boolean isWeChatPackage(String packageName) {
        return WECHAT_PACKAGE_PREFIX.equals(packageName);
    }

    /**
     * 获取当前进程UserID，区分多用户/系统分身
     */
    private int getUserId() {
        try {
            Method myUserIdMethod = UserHandle.class.getDeclaredMethod("myUserId");
            myUserIdMethod.setAccessible(true);
            int userId = (int) myUserIdMethod.invoke(null);
            // XposedBridge.log(TAG + " [" + currentPackageName + "] UserHandle.myUserId 获取UID: " + userId);
            return userId;
        } catch (Exception e1) {
            XposedBridge.log(TAG + " UserHandle.myUserId 失败: " + e1.getMessage());
        }
        try {
            UserHandle userHandle = android.os.Process.myUserHandle();
            if (userHandle != null) {
                Field userIdField = UserHandle.class.getDeclaredField("mHandle");
                userIdField.setAccessible(true);
                int userId = userIdField.getInt(userHandle);
                XposedBridge.log(TAG + " Process.myUserHandle 获取UID: " + userId);
                return userId;
            }
        } catch (Exception e2) {
            XposedBridge.log(TAG + " Process.myUserHandle 失败: " + e2.getMessage());
        }
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
        } catch (Exception e3) {
            XposedBridge.log(TAG + " 目录解析UID失败: " + e3.getMessage());
        }
        XposedBridge.log(TAG + " 无法获取UID，默认0");
        return 0;
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
            return false; // 端口可用
        } catch (IOException e) {
            return true; // 端口已被占用
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
        // ConcurrentHashMap 的迭代器支持并发修改且弱一致,无需额外 synchronized
        JSONArray result = new JSONArray();
        java.util.Iterator<java.util.Map.Entry<String, JSONObject>> it = instanceMap.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, JSONObject> entry = it.next();
            try {
                JSONObject item = entry.getValue();
                result.put(item);
            } catch (Exception e) {
                // 忽略异常数据
            }
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
                String url = "http://127.0.0.1:" + MASTER_PORT + "/register?port=" + port
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
                XposedBridge.log(TAG + " 向主端口注册失败(127.0.0.1:" + MASTER_PORT + " 不可达?): " + e.getMessage());
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
            URL url = new URL("http://127.0.0.1:" + MASTER_PORT + "/instances");
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
            powerSaverMode = appContext.getSharedPreferences("wxcode_config", Context.MODE_PRIVATE)
                    .getBoolean("power_saver_mode", false);
            XposedBridge.log(TAG + " 保活模式: " + (powerSaverMode ? "省电模式" : "性能模式"));
        } catch (Exception ignored) {}
        // 进程级常驻：HTTP server 启动即拉起前台 Service，确保所有 HTTP 线程
        // （含 NanoHTTPD accept）始终受前台优先级保护，不被 Doze 限流。
        startForegroundService();
    }

    /**
     * 前台保活模式：真正启动一个前台 Service，将进程优先级提升到前台，
     * 从而绕过系统对后台应用的网络限流/Doze延迟，并规避"后台启动Activity被拦截"的问题。
     * （注意：必须在 AndroidManifest.xml 中声明 KeepAliveService）
     */
    private void startForegroundService() {
        if (isForegroundServiceRunning) return;
        try {
            // WakeLock 由 KeepAliveService 自身持有（常驻无超时），此处只启动 Service
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
     * 切换保活模式：更新标志 + 重启 KeepAliveService 以应用新模式
     */
    private void switchPowerMode(boolean toPowerSaver) {
        if (powerSaverMode == toPowerSaver) return;
        powerSaverMode = toPowerSaver;
        // 持久化到 SharedPreferences，确保微信重启后模式选择不丢失
        try {
            appContext.getSharedPreferences("wxcode_config", Context.MODE_PRIVATE)
                    .edit().putBoolean("power_saver_mode", toPowerSaver).apply();
        } catch (Exception ignored) {}
        XposedBridge.log(TAG + " 切换保活模式: " + (toPowerSaver ? "省电模式" : "性能模式"));
        stopForegroundService();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        isForegroundServiceRunning = false;
        startForegroundService();
    }

    /**
     * 后台登录时确保前台保活 Service 已拉起，避免接口请求被系统限流卡住。
     * 与临时拉起Activity相比，前台Service在 Android 10+ 上不受"后台启动Activity"限制，
     * 且能真正提升进程优先级，是后台可靠性的关键。
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
        while (!fgServiceActive && System.currentTimeMillis() < deadline) {
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
        // 先清空引用再 quitSafely:quitSafely 是异步的,正在执行的 pollTask 可能仍访问
        // workerHandler/workerThread。先置空让后续 doLogin 检测到 null 会重建,
        // 而已入队的旧任务通过自身捕获的 handler 引用继续执行,不会读到半空状态。
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
                    httpServer = new LoginHttpServer(WxLoginHook.this, httpPort, classLoader);
                    httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                    XposedBridge.log(TAG + " HTTP服务启动成功 http://0.0.0.0:" + httpPort);
                    // 本地注册自己到内存实例表
                    registerInstanceInMemory(currentPackageName, currentUserId, httpPort, versionName);
                    // 非主端口实例：向主端口(8088)发送注册通知，由主端口汇总所有端口
                    if (httpPort != MASTER_PORT) {
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
            // 后台登录超时
            final long timeoutMs = 30000;
            XposedBridge.log(TAG + " doLogin 前台Service运行=" + isForegroundServiceRunning + " 超时=" + timeoutMs + "ms");
            Class<?> LoginTaskCls = XposedHelpers.findClass("com.tencent.mm.plugin.appbrand.jsapi.auth.JsApiLogin$LoginTask", classLoader);
            Class<?> h2Cls = XposedHelpers.findClass(COMMON_PACKAGE + "." + this.a1, classLoader);
            Class<?> l2Cls = XposedHelpers.findClass(COMMON_PACKAGE + "." + this.a7, classLoader);
            Class<?> cCls = XposedHelpers.findClass(this.c, classLoader);
            Class<?> j1Cls = XposedHelpers.findClass(this.j1, classLoader);
            XposedBridge.log(TAG + " a1=" + COMMON_PACKAGE + "." + this.a1 + " a7=" + COMMON_PACKAGE + "." + this.a7);
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
                // 用 while 循环检查 res[0]:即使 pollTask 在主线程进入 wait 之前就已完成并 notify,
                // 主线程进入 synchronized 后会先检查 res[0],非空则立即退出,不会错过通知拖到超时。
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
            // getMessage() 可能返回 null(如某些 NPE),直接 replace 会再次抛 NPE
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().replace("\"", "\\\"");
            res[0] = "{\"err\":-500,\"msg\":\"" + msg + "\"}";
        } finally {
            isLoginInFlight.set(false);
            // 前台 Service 现为 HTTP server 常驻依赖，不在登录结束时停止
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
     * 匹配8参数构造函数，兼容微信内部类
     */
    private Constructor<?> findHe0cConstructor(Class<?> clazz) {
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (c.getParameterCount() == 8) {
                    c.setAccessible(true);
                    return c;
                }
            }
        }
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (c.getParameterCount() >= 6) {
                    c.setAccessible(true);
                    return c;
                }
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
     * HTTP服务内部类
     */
    class LoginHttpServer extends NanoHTTPD {
        private final WxLoginHook outer;
        private final ClassLoader classLoader;

        public LoginHttpServer(WxLoginHook hook, int port, ClassLoader cl) {
            super(port);
            outer = hook;
            classLoader = cl;
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
                    info.put("packageName", outer.currentPackageName);
                    info.put("userId", outer.currentUserId);
                    info.put("port", outer.httpPort);
                    info.put("version", outer.versionName);
                    info.put("j1", outer.j1);
                    info.put("c", outer.c);
                    info.put("a1", COMMON_PACKAGE + "." + outer.a1);
                    info.put("a7", COMMON_PACKAGE + "." + outer.a7);
                    info.put("j1StaticMethod", outer.j1StaticMethod);
                    info.put("j1InstanceMethod", outer.j1InstanceMethod);
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
                    String ver = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        ver = iHTTPSession.getParms().getOrDefault("version", "");
                    }
                    String pkg = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        pkg = iHTTPSession.getParms().getOrDefault("packageName", "com.tencent.mm");
                    }
                    if (portStr != null && userIdStr != null) {
                        outer.registerInstanceInMemory(pkg, Integer.parseInt(userIdStr), Integer.parseInt(portStr), ver);
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
                    if (outer.httpPort == MASTER_PORT) {
                        instances = outer.getMemoryInstances();
                    } else {
                        JSONArray master = outer.fetchMasterInstances();
                        instances = master != null ? master : outer.getMemoryInstances();
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
                    result.put("current", outer.currentPackageName);
                    result.put("currentUserId", outer.currentUserId);
                    result.put("currentPort", outer.httpPort);
                    result.put("count", instances.length());
                    result.put("role", outer.httpPort == MASTER_PORT ? "master" : "slave");
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", result.toString());
                } catch (Exception e) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", "{\"err\":-500,\"msg\":\"" + e.getMessage() + "\"}");
                }
            }

            // /config 接口：查看/切换保活模式
            if (uri.equals("/config")) {
                String mode = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mode = iHTTPSession.getParms().getOrDefault("mode", "");
                }
                if ("performance".equals(mode) || "power_saver".equals(mode)) {
                    outer.switchPowerMode("power_saver".equals(mode));
                }
                try {
                    JSONObject cfg = new JSONObject();
                    cfg.put("mode", powerSaverMode ? "power_saver" : "performance");
                    cfg.put("modeDesc", powerSaverMode ? "省电模式(AlarmManager定时唤醒)" : "性能模式(常驻WakeLock)");
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", cfg.toString());
                } catch (Exception e) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", "{\"err\":-500,\"msg\":\"" + e.getMessage() + "\"}");
                }
            }

            // /login 接口：执行登录（后台唤醒逻辑已由 doLogin 内部按需处理）
            if (uri.equals("/login")) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", outer.doLogin(iHTTPSession.getParms().getOrDefault("appId", DEFAULT_AUTO_APP_ID), classLoader));
                }
            }

            // 首页HTML页面
            try {
                JSONObject jSONObject = new JSONObject(outer.jsonString);
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
                sb.append("<p><strong>包名：</strong> <code>").append(outer.currentPackageName).append("</code></p>");
                sb.append("<p><strong>User ID：</strong> <code>").append(String.valueOf(outer.currentUserId)).append("</code> <small style='color:#666;'>（用于区分系统级分身）</small></p>");
                sb.append("<p><strong>HTTP端口：</strong> <code>").append(String.valueOf(outer.httpPort)).append("</code></p>");
                sb.append("<p><strong>微信版本：</strong> <code>").append(outer.versionName).append("</code></p>");

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
                    sb.append("<tr><td><code>COMMON_PACKAGE</code></td><td class='code'>").append(COMMON_PACKAGE).append("</td></tr>");
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
                final String aPrefix = longestCommonPrefix(classNames);
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
                    String stringA1 = shortenClass(a1Full, aPrefix);
                    String stringA7 = shortenClass(a7Full, aPrefix);

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

    /**
     * 前台保活 Service：用于把微信进程优先级提升到前台，避免后台网络被系统限流/Doze延迟。
     * 必须是 public static，否则框架无法用默认构造器实例化。
     * 注意：需在 AndroidManifest.xml 中声明 <service android:name=".hook.WxLoginHook$KeepAliveService" />。
     */
    public static class KeepAliveService extends Service {
        private PowerManager.WakeLock serviceWakeLock;
        private static final int ALARM_INTERVAL_MS = 2 * 60 * 1000;
        private static final String EXTRA_ALARM_TRIGGER = "alarm_trigger";
        private static PendingIntent alarmPendingIntent;

        @Override
        public void onCreate() {
            super.onCreate();
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            boolean isAlarmTrigger = intent != null && intent.getBooleanExtra(EXTRA_ALARM_TRIGGER, false);
            if (isAlarmTrigger) {
                // 闹钟触发：acquire 短时 wakeLock(10s 自动释放)
                // 不 stopSelf：保持 Service 前台运行，进程优先级不降，只是 CPU 休眠省电
                try {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    PowerManager.WakeLock alarmWl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wxcode:alarm_wakeup");
                    alarmWl.acquire(10000);
                    scheduleNextAlarm();
                } catch (Throwable ignored) {}
                return START_STICKY;
            }
            // 正常启动：startForeground + 根据模式选择保活策略
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        FOREGROUND_CHANNEL_ID, "wxcode后台保活", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("wxcode HTTP服务保活，避免后台网络被限流");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
            Notification notification;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notification = new Notification.Builder(this, FOREGROUND_CHANNEL_ID)
                        .setContentTitle("wxcode 服务运行中")
                        .setContentText(powerSaverMode ? "省电模式：定时唤醒保活" : "性能模式：常驻保活中")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setOngoing(true)
                        .build();
            } else {
                notification = new Notification.Builder(this)
                        .setContentTitle("wxcode 服务运行中")
                        .setContentText(powerSaverMode ? "省电模式：定时唤醒保活" : "性能模式：常驻保活中")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setOngoing(true)
                        .build();
            }
            try {
                // Android 14+ 需要指定前台服务类型
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(FOREGROUND_NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(FOREGROUND_NOTIF_ID, notification);
                }
                fgServiceActive = true;
                cancelAlarm();
                if (powerSaverMode) {
                    // 省电模式：AlarmManager 每 2 分钟唤醒一次，不持有常驻 WakeLock
                    scheduleNextAlarm();
                    XposedBridge.log(TAG + " KeepAliveService 省电模式：AlarmManager 定时唤醒(间隔" + ALARM_INTERVAL_MS / 1000 + "s)");
                } else {
                    // 性能模式：常驻 WakeLock，CPU 始终可调度，HTTP 即时响应
                    if (serviceWakeLock == null || !serviceWakeLock.isHeld()) {
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        serviceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wxcode:keepalive");
                        serviceWakeLock.acquire();
                    }
                    XposedBridge.log(TAG + " KeepAliveService 性能模式：WakeLock 常驻持有");
                }
            } catch (Throwable e) {
                fgServiceActive = false;
                XposedBridge.log(TAG + " KeepAliveService.startForeground 失败[" + e.getClass().getSimpleName() + "]:" + e.getMessage());
            }
            return START_STICKY;
        }

        private void scheduleNextAlarm() {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(this, KeepAliveService.class);
            intent.putExtra(EXTRA_ALARM_TRIGGER, true);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            // Android 11+ 使用 getForegroundService，之前版本使用 getService
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                alarmPendingIntent = PendingIntent.getForegroundService(this, 1002, intent, flags);
            } else {
                alarmPendingIntent = PendingIntent.getService(this, 1002, intent, flags);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS, alarmPendingIntent);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS, alarmPendingIntent);
            }
        }

        private void cancelAlarm() {
            if (alarmPendingIntent != null) {
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                am.cancel(alarmPendingIntent);
                alarmPendingIntent = null;
            }
        }

        @Override
        public void onDestroy() {
            fgServiceActive = false;
            cancelAlarm();
            if (serviceWakeLock != null && serviceWakeLock.isHeld()) {
                try { serviceWakeLock.release(); } catch (Exception ignored) {}
            }
            serviceWakeLock = null;
            super.onDestroy();
            XposedBridge.log(TAG + " KeepAliveService 已销毁");
        }


        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
    }
}
