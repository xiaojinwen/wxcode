package xiaojw.hook;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.app.ActivityManager;
import android.app.Application;
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
    private boolean isLoginInFlight = false;
    private Context appContext;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private boolean isForegroundServiceRunning = false;
    // static 标志：供 KeepAliveService（static 内部类）上报"已真正 startForeground 完成"。
    // 仅当它为 true 时，Android 10 的"有前台Service可后台启动Activity"豁免才成立。
    private static volatile boolean fgServiceActive = false;
    private Application wechatApplication;
    private Activity fakeTopActivity;
    private ClassLoader savedClassLoader;
    private boolean wasForegroundBeforeLogin = false;
    private Activity tempWakedActivity = null; // 记录本次登录临时拉起的微信Activity，用于登录后退回后台

    // 版本配置JSON
    // a1 = 1参回调(o2)实现类，构造: (LoginTask)；a7 = 2参回调(h80/j)实现类，构造: (LoginTask, o2)
    // 8.0.76 起 h2/l2 混淆位移：h2->i2(a1)、l2->m2(a7)，构造函数签名随版本变化，故均纳入配置
    private String jsonString = """
        {
            "8.0.49": {"j1": "u70.k1", "c": "o60.c", "a1": "com.tencent.mm.plugin.appbrand.jsapi.auth.h2", "a7": "com.tencent.mm.plugin.appbrand.jsapi.auth.l2"},
            "8.0.62": {"j1": "of0.j1", "c": "he0.c", "a1": "com.tencent.mm.plugin.appbrand.jsapi.auth.h2", "a7": "com.tencent.mm.plugin.appbrand.jsapi.auth.l2"},
            "8.0.70": {"j1": "yj0.j1", "c": "ti0.c", "a1": "com.tencent.mm.plugin.appbrand.jsapi.auth.h2", "a7": "com.tencent.mm.plugin.appbrand.jsapi.auth.l2"},
            "8.0.71": {"j1": "tk0.j1", "c": "oj0.c", "a1": "com.tencent.mm.plugin.appbrand.jsapi.auth.h2", "a7": "com.tencent.mm.plugin.appbrand.jsapi.auth.l2"},
            "8.0.72": {"j1": "dl0.k1", "c": "yj0.c", "a1": "com.tencent.mm.plugin.appbrand.jsapi.auth.h2", "a7": "com.tencent.mm.plugin.appbrand.jsapi.auth.l2"},
            "8.0.74": {"j1": "gm0.j1", "c": "bl0.c", "a1": "com.tencent.mm.plugin.appbrand.jsapi.auth.h2", "a7": "com.tencent.mm.plugin.appbrand.jsapi.auth.l2"},
            "8.0.76": {"j1": "hm0.j1", "c": "ccl0.c", "a1": "com.tencent.mm.plugin.appbrand.jsapi.auth.i2", "a7": "com.tencent.mm.plugin.appbrand.jsapi.auth.m2"}
        }""";

    private String j1 = "of0.j1";
    private String c = "he0.c";
    private String a1 = "com.tencent.mm.plugin.appbrand.jsapi.auth.h2";
    private String a7 = "com.tencent.mm.plugin.appbrand.jsapi.auth.l2";
    private String versionName = "000";
    private String currentPackageName = "";
    private int currentUserId = 0;
    private int httpPort = 8088;
    // 主端口：所有分身实例向该端口(用户0的8088)发送注册通知，由其汇总所有启动的端口
    private static final int MASTER_PORT = 8088;
    // 前台保活 Service 通知ID与渠道ID（真正提升进程优先级，避免后台网络限流）
    private static final int FOREGROUND_NOTIF_ID = 1001;
    private static final String FOREGROUND_CHANNEL_ID = "wxcode_foreground_keepalive";

    // 内存实例表：跨用户实例通过向主端口注册通知汇聚，无需共享文件系统即可多用户共享
    private final Object instanceLock = new Object();
    private final java.util.Map<String, JSONObject> instanceMap = new java.util.concurrent.ConcurrentHashMap<>();
    private Thread heartbeatThread;

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
            XposedBridge.log(TAG + " [" + currentPackageName + "] UserHandle.myUserId 获取UID: " + userId);
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
            String dataDir = appContext.getDataDir() != null ? appContext.getDataDir().getAbsolutePath() : null;
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
     * 将实例注册到内存实例表（跨用户共享的主要数据源，无需共享文件系统）
     */
    private void registerInstanceInMemory(String packageName, int userId, int port, String version) {
        try {
            String key = packageName + "_" + userId;
            JSONObject item = new JSONObject();
            item.put("packageName", packageName);
            item.put("userId", userId);
            item.put("port", port);
            item.put("version", version);
            item.put("registerTime", System.currentTimeMillis());
            synchronized (instanceLock) {
                instanceMap.put(key, item);
            }
            XposedBridge.log(TAG + " 内存注册实例: " + key + " :" + port);
        } catch (Exception e) {
            XposedBridge.log(TAG + " 内存注册失败: " + e.getMessage());
        }
    }

    /**
     * 读取内存实例表并清理5分钟过期的实例
     */
    private JSONArray getMemoryInstances() {
        synchronized (instanceLock) {
            long now = System.currentTimeMillis();
            JSONArray result = new JSONArray();
            java.util.Iterator<java.util.Map.Entry<String, JSONObject>> it = instanceMap.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String, JSONObject> entry = it.next();
                try {
                    JSONObject item = entry.getValue();
                    if (now - item.getLong("registerTime") < 300000) {
                        result.put(item);
                    } else {
                        it.remove();
                    }
                } catch (Exception e) {
                    it.remove();
                }
            }
            return result;
        }
    }

    /**
     * 非主端口实例：向主端口(8088)发送注册通知
     */
    private void notifyMasterRegister(int port, int userId, String version) {
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
     * 定时续期实例存活：主端口刷新自己的 registerTime，非主端口向主端口重新注册。
     * 否则 getMemoryInstances() 按 5 分钟过期清理后，/instances 会返回空列表。
     */
    private void startHeartbeat() {
        if (heartbeatThread != null && heartbeatThread.isAlive()) return;
        heartbeatThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(60000);
                    if (httpPort == MASTER_PORT) {
                        registerInstanceInMemory(currentPackageName, currentUserId, httpPort, versionName);
                    } else {
                        notifyMasterRegister(httpPort, currentUserId, versionName);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "wxcode-heartbeat");
        heartbeatThread.start();
        XposedBridge.log(TAG + " 心跳线程启动");
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
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
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
        hookActivityLifecycle();
        startWorkerThread();
        // 进程级常驻：HTTP server 启动即拉起前台 Service，确保所有 HTTP 线程
        // （含 NanoHTTPD accept）始终受前台优先级保护，不被 Doze 限流。
        startForegroundService();
    }

    /**
     * 监听微信Activity生命周期，记录前台页面
     */
    private void hookActivityLifecycle() {
        if (wechatApplication == null) return;
        try {
            Application.ActivityLifecycleCallbacks callback = new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {}
                @Override
                public void onActivityStarted(Activity activity) {}
                @Override
                public void onActivityResumed(Activity activity) {
                    fakeTopActivity = activity;
                    XposedBridge.log(TAG + " [" + currentPackageName + "] 前台Activity: " + activity.getClass().getSimpleName());
                }
                @Override
                public void onActivityPaused(Activity activity) {}
                @Override
                public void onActivityStopped(Activity activity) {}
                @Override
                public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {}
                @Override
                public void onActivityDestroyed(Activity activity) {
                    if (fakeTopActivity == activity) fakeTopActivity = null;
                }
            };
            wechatApplication.registerActivityLifecycleCallbacks(callback);
            XposedBridge.log(TAG + " Activity生命周期Hook注册完成");
        } catch (Exception e) {
            XposedBridge.log(TAG + " 注册生命周期回调失败: " + e.getMessage());
        }
    }

    /**
     * 强制伪造微信前台状态，保存原始状态
     */
    private void forceForegroundState(ClassLoader classLoader) {
        XposedBridge.log(TAG + " [" + currentPackageName + "] 伪造前台状态");
        savedClassLoader = classLoader;
        try {
            Class<?> foregroundClass = XposedHelpers.findClassIfExists("com.tencent.mm.sdk.platformtools.ForegroundDetector", classLoader);
            if (foregroundClass != null) {
                Field isForegroundField = foregroundClass.getDeclaredField("isForeground");
                isForegroundField.setAccessible(true);
                wasForegroundBeforeLogin = isForegroundField.getBoolean(null);
                isForegroundField.set(null, true);
                try {
                    Field fgField = foregroundClass.getDeclaredField("foreground");
                    fgField.setAccessible(true);
                    fgField.set(null, true);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        try {
            Class<?> monitorClass = XposedHelpers.findClassIfExists("com.tencent.mm.sdk.platformtools.MMAppForegroundMonitor", classLoader);
            if (monitorClass != null) {
                Field[] fields = monitorClass.getDeclaredFields();
                for (Field field : fields) {
                    if (field.getName().contains("foreground")) {
                        field.setAccessible(true);
                        field.set(null, true);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * 登录完成恢复原始前台状态
     */
    private void restoreForegroundState() {
        if (savedClassLoader == null) return;
        XposedBridge.log(TAG + " 恢复原始前台状态");
        try {
            Class<?> foregroundClass = XposedHelpers.findClassIfExists("com.tencent.mm.sdk.platformtools.ForegroundDetector", savedClassLoader);
            if (foregroundClass != null) {
                Field isForegroundField = foregroundClass.getDeclaredField("isForeground");
                isForegroundField.setAccessible(true);
                isForegroundField.set(null, wasForegroundBeforeLogin);
            }
        } catch (Exception ignored) {}
        savedClassLoader = null;
    }

    /**
     * 判断微信当前是否前台运行
     */
    private boolean isWeChatForeground() {
        if (fakeTopActivity != null && !fakeTopActivity.isFinishing()) return true;
        try {
            ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            for (ActivityManager.RunningAppProcessInfo process : am.getRunningAppProcesses()) {
                if (process.processName.equals(currentPackageName)) {
                    return process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
                }
            }
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            try {
                ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
                java.util.List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                if (!tasks.isEmpty()) {
                    ActivityManager.RunningTaskInfo topTask = tasks.get(0);
                    if (topTask.topActivity != null && topTask.topActivity.getPackageName().equals(currentPackageName)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }
        return false;
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
            startWorkerThread();
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
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            workerHandler = null;
        }
    }

    /**
     * 拉起微信到前台
     */
    private void tempWakeupWeChat() {
        try {
            Intent intent = appContext.getPackageManager().getLaunchIntentForPackage(currentPackageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                appContext.startActivity(intent);
                Thread.sleep(500);
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " 唤醒微信失败:" + e.getMessage());
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (!isWeChatPackage(loadPackageParam.packageName)) return;
        currentPackageName = loadPackageParam.packageName;
        XposedBridge.log(TAG + " 捕获微信包: " + currentPackageName);
        Class<?> appCls = Class.forName("android.app.Application");
        XposedHelpers.findAndHookMethod(appCls, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.args[0];
                ClassLoader classLoader = context.getClassLoader();
                appContext = context;
                currentUserId = getUserId();
                httpPort = calculatePort(currentUserId);
                PackageInfo pkgInfo = context.getPackageManager().getPackageInfo(currentPackageName, 0);
                versionName = pkgInfo.versionName;
                XposedBridge.log(TAG + " [" + currentPackageName + "] UID:" + currentUserId + " Ver:" + versionName + " Port:" + httpPort);
                try {
                    JSONObject verCfg = new JSONObject(jsonString).getJSONObject(versionName);
                    j1 = verCfg.getString("j1");
                    c = verCfg.getString("c");
                    a1 = verCfg.optString("a1", a1);
                    a7 = verCfg.optString("a7", a7);
                    XposedBridge.log(TAG + " 版本配置加载成功: " + verCfg);
                } catch (Exception e) {
                    XposedBridge.log(TAG + " 版本配置不存在: " + e.getMessage());
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
                    // 所有实例都启动心跳续期，避免被 5 分钟过期清理
                    startHeartbeat();
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
        if (isLoginInFlight) return "{\"err\":-100,\"msg\":\"登录请求正在处理中\"}";
        isLoginInFlight = true;
        final String[] res = {null};
        try {
            // 后台登录超时
            final long timeoutMs = 30000;
            XposedBridge.log(TAG + " doLogin 前台Service运行=" + isForegroundServiceRunning + " 超时=" + timeoutMs + "ms");
            Class<?> LoginTaskCls = XposedHelpers.findClass("com.tencent.mm.plugin.appbrand.jsapi.auth.JsApiLogin$LoginTask", classLoader);
            Class<?> h2Cls = XposedHelpers.findClass(this.a1, classLoader);
            Class<?> l2Cls = XposedHelpers.findClass(this.a7, classLoader);
            Class<?> cCls = XposedHelpers.findClass(this.c, classLoader);
            Class<?> j1Cls = XposedHelpers.findClass(this.j1, classLoader);
            XposedBridge.log(TAG + " a1=" + this.a1 + " a7=" + this.a7);
            XposedBridge.log(TAG + " 发起登录 appId=" + str);
            // 仅看微信是否真有前台 Activity（fakeTopActivity）。不能依赖 isWeChatForeground()，
            // 因为前台 Service 会抬高进程 importance 让其返回 true，从而错误跳过拉起，导致微信仍后台限速卡住。
            boolean weChatHasForegroundActivity = fakeTopActivity != null && !fakeTopActivity.isFinishing();
            wasForegroundBeforeLogin = weChatHasForegroundActivity;
            if (!weChatHasForegroundActivity) {
                // 微信无前台 Activity：其内部按"后台"限速网络，必须真正拉起一个前台 Activity 才能解除限速。
                // 先拉起前台Service，等其真正 startForeground（异步）后，Android 10 才允许从后台启动 Activity。
                ensureForegroundForBackground();
                waitForForegroundService(1000);
                tempWakeupWeChat();
                // 等微信主界面 resume，记录我们临时拉起的 Activity，供登录结束后退回后台
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                if (fakeTopActivity != null && !fakeTopActivity.isFinishing()) {
                    tempWakedActivity = fakeTopActivity;
                }
                XposedBridge.log(TAG + " 后台拉起微信后: fgServiceActive=" + fgServiceActive
                        + " fakeTopActivity=" + (fakeTopActivity == null ? "null" : fakeTopActivity.getClass().getSimpleName()));
            }
            forceForegroundState(classLoader);
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
            XposedHelpers.callMethod(XposedHelpers.callStaticMethod(j1Cls, "d"), "g", cObj);
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
                lockObj.wait(timeoutMs + 1000);
            }
            if (res[0] == null) res[0] = "{\"err\":-210,\"msg\":\"登录超时\"}";
        } catch (Throwable e) {
            XposedBridge.log(TAG + " doLogin异常:" + e.getMessage());
            res[0] = "{\"err\":-500,\"msg\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        } finally {
            isLoginInFlight = false;
            restoreForegroundState();
            // 若本次是临时把微信拉到前台，登录结束后退回后台，避免微信一直停留前台打扰用户
            if (!wasForegroundBeforeLogin && tempWakedActivity != null) {
                try {
                    if (tempWakedActivity == fakeTopActivity && !tempWakedActivity.isFinishing()) {
                        tempWakedActivity.finish();
                    }
                } catch (Throwable ignored) {}
                tempWakedActivity = null;
            }
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
            if (c.getParameterCount() == 8) {
                c.setAccessible(true);
                return c;
            }
        }
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (c.getParameterCount() >= 6) {
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

            // 请求级自愈：确保前台 Service 就绪，避免后台进程被 Doze 限流导致 HTTP 无响应。
            // 已就绪时近零开销（一次 volatile 读 + 一次 isHeld 判断）；未就绪时最多阻塞 1.5s。
            outer.ensureForegroundForBackground();
            outer.waitForForegroundService(1500);

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
                    info.put("a1", outer.a1);
                    info.put("a7", outer.a7);
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
                    String ver = iHTTPSession.getParms().getOrDefault("version", "");
                    String pkg = iHTTPSession.getParms().getOrDefault("packageName", "com.tencent.mm");
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

            // /login 接口：执行登录
            if (uri.equals("/login")) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", outer.doLogin(iHTTPSession.getParms().getOrDefault("appId", DEFAULT_AUTO_APP_ID), classLoader));
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
                sb.append("</tr>");
                sb.append("</thead>");
                // 计算所有 a1/a7 的公共前缀，展示时省略只保留尾部不同的类名段
                java.util.List<String> classNames = new java.util.ArrayList<>();
                Iterator<String> preIt = jSONObject.keys();
                while (preIt.hasNext()) {
                    JSONObject o = jSONObject.getJSONObject(preIt.next());
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
                    JSONObject jSONObject2 = jSONObject.getJSONObject(next);
                    String string = jSONObject2.getString("j1");
                    String string2 = jSONObject2.getString("c");
                    String a1Full = jSONObject2.optString("a1", "-");
                    String a7Full = jSONObject2.optString("a7", "-");
                    String stringA1 = shortenClass(a1Full, aPrefix);
                    String stringA7 = shortenClass(a7Full, aPrefix);
                    sb.append("<tr>");
                    sb.append("<td class='version'>").append(next).append("</td>");
                    sb.append("<td class='code'>").append(string).append("</td>");
                    sb.append("<td class='code'>").append(string2).append("</td>");
                    sb.append("<td class='code' title='").append(a1Full).append("'>").append(stringA1).append("</td>");
                    sb.append("<td class='code' title='").append(a7Full).append("'>").append(stringA7).append("</td>");
                    sb.append("</tr>");
                }
                sb.append("</tbody>");
                sb.append("</table>");
                sb.append("</div>");
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
    }

    /**
     * 前台保活 Service：用于把微信进程优先级提升到前台，避免后台网络被系统限流/Doze延迟。
     * 必须是 public static，否则框架无法用默认构造器实例化。
     * 注意：需在 AndroidManifest.xml 中声明 <service android:name=".hook.WxLoginHook$KeepAliveService" />。
     */
    public static class KeepAliveService extends Service {
        private PowerManager.WakeLock serviceWakeLock;

        @Override
        public void onCreate() {
            super.onCreate();
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            // 立即调用 startForeground，否则系统会抛出 ForegroundServiceDidNotStartInTimeException
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        FOREGROUND_CHANNEL_ID, "wxcode后台保活", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("wxcode HTTP服务保活，避免后台网络被限流");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
            Notification notification = new Notification.Builder(this)
                    .setContentTitle("wxcode 服务运行中")
                    .setContentText("后台保活中，避免接口请求被限流")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setPriority(Notification.PRIORITY_LOW)
                    .setOngoing(true)
                    .setChannelId(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? FOREGROUND_CHANNEL_ID : null)
                    .build();
            try {
                startForeground(FOREGROUND_NOTIF_ID, notification);
                fgServiceActive = true;
                // 常驻 WakeLock（无超时）：保持 CPU 唤醒，确保 HTTP server 线程随时可调度。
                // 与 Service 生命周期绑定，onDestroy 中释放。根治长时间空闲后 HTTP 无响应。
                if (serviceWakeLock == null || !serviceWakeLock.isHeld()) {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    serviceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wxcode:keepalive");
                    serviceWakeLock.acquire();
                }
                XposedBridge.log(TAG + " KeepAliveService 已 startForeground，WakeLock 常驻持有");
            } catch (Throwable e) {
                fgServiceActive = false;
                XposedBridge.log(TAG + " KeepAliveService.startForeground 失败[" + e.getClass().getSimpleName() + "]:" + e.getMessage());
            }
            // START_STICKY：被系统回收后尽量重建，保持后台可靠性
            return START_STICKY;
        }

        @Override
        public void onDestroy() {
            fgServiceActive = false;
            if (serviceWakeLock != null && serviceWakeLock.isHeld()) {
                try { serviceWakeLock.release(); } catch (Exception ignored) {}
            }
            serviceWakeLock = null;
            super.onDestroy();
            XposedBridge.log(TAG + " KeepAliveService 已销毁，WakeLock 已释放");
        }


        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
    }
}
