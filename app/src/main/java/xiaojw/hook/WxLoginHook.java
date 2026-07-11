package xiaojw.hook;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.LinkedList;
import org.json.JSONArray;
import org.json.JSONObject;

public class WxLoginHook implements IXposedHookLoadPackage {
    private static final long CALLBACK_TIMEOUT_MS = 15000;
    private static final String DEFAULT_AUTO_APP_ID = "wxaa3a999db5d744c6";
    private static final String TAG = "xiaojw-wxcode";
    private static final String WECHAT_PACKAGE_PREFIX = "com.tencent.mm";

    // 全局共享注册表路径 ROOT跨多用户专用
    private static final String GLOBAL_REGISTRY_DIR = "/data/misc/wxcode/";
    private static final String GLOBAL_REGISTRY_NAME = "registry.json";
    private String registryFilePath = null;

    // 执行模式常量
    private static final String MODE_FOREGROUND_SERVICE = "foreground_service";
    private static final String MODE_WORKER_THREAD = "worker_thread";
    private static final String MODE_TEMP_WAKEUP = "temp_wakeup";

    private LoginHttpServer httpServer;
    private boolean isLoginInFlight = false;
    private Context appContext;
    private SharedPreferences prefs;
    private String currentMode = MODE_WORKER_THREAD;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private boolean isForegroundServiceRunning = false;
    private PowerManager.WakeLock wakeLock;
    private Application wechatApplication;
    private Activity fakeTopActivity;
    private ClassLoader savedClassLoader;
    private boolean wasForegroundBeforeLogin = false;

    // 版本配置JSON
    private String jsonString = """
        {
            "8.0.49": {"j1": "u70.k1", "c": "o60.c"},
            "8.0.62": {"j1": "of0.j1", "c": "he0.c"},
            "8.0.70": {"j1": "yj0.j1", "c": "ti0.c"},
            "8.0.71": {"j1": "tk0.j1", "c": "oj0.c"},
            "8.0.72": {"j1": "dl0.k1", "c": "yj0.c"},
            "8.0.74": {"j1": "gm0.j1", "c": "bl0.c"}
        }""";

    private String j1 = "of0.j1";
    private String c = "he0.c";
    private String versionName = "000";
    private String currentPackageName = "";
    private int currentUserId = 0;
    private int httpPort = 8088;

    /**
     * 判断是否微信/微信分身包名
     */
    private boolean isWeChatPackage(String packageName) {
        if (packageName == null) return false;
        if (packageName.equals("com.tencent.mm")) return true;
        if (packageName.startsWith("com.tencent.mm:")) return true;
        if (packageName.startsWith("com.tencent.mm_")) return true;
        if (packageName.startsWith("com.tencent.mm.")) return true;
        if (packageName.contains("tencent.mm") && packageName.length() > "com.tencent.mm".length()) return true;
        return false;
    }

    /**
     * 初始化全局共享注册表路径，ROOT自动赋777权限
     */
    private void initRegistryFilePath() {
        File globalDir = new File(GLOBAL_REGISTRY_DIR);
        registryFilePath = GLOBAL_REGISTRY_DIR + GLOBAL_REGISTRY_NAME;
        File regFile = new File(registryFilePath);

        try {
            if (!globalDir.exists()) {
                globalDir.mkdirs();
                XposedBridge.log(TAG + " 创建全局共享目录: " + globalDir.getAbsolutePath());
            }
            // ROOT授权全用户读写
            Runtime.getRuntime().exec("chmod 777 " + GLOBAL_REGISTRY_DIR);
            Runtime.getRuntime().exec("chmod 777 " + registryFilePath);
            // 文件权限全局可读可写
            globalDir.setReadable(true, false);
            globalDir.setWritable(true, false);
            globalDir.setExecutable(true, false);
            regFile.setReadable(true, false);
            regFile.setWritable(true, false);
            XposedBridge.log(TAG + " [" + currentPackageName + " User:" + currentUserId + "] 全局共享注册表路径: " + registryFilePath);
        } catch (Exception e) {
            XposedBridge.log(TAG + " 全局目录授权失败，设备未ROOT，降级本地存储: " + e.getMessage());
            fallbackLocalRegistryPath();
        }
    }

    /**
     * 无ROOT降级方案：仅当前用户分身互通
     */
    private void fallbackLocalRegistryPath() {
        try {
            File externalDir = Environment.getExternalStorageDirectory();
            if (externalDir != null && externalDir.canWrite()) {
                registryFilePath = externalDir.getAbsolutePath() + "/wxcode_registry.json";
                XposedBridge.log(TAG + " 降级外部存储注册表: " + registryFilePath);
                return;
            }
        } catch (Exception ignored) {}
        try {
            if (appContext != null) {
                File appExternalDir = appContext.getExternalFilesDir(null);
                if (appExternalDir != null) {
                    registryFilePath = appExternalDir.getAbsolutePath() + "/wxcode_registry.json";
                    XposedBridge.log(TAG + " 降级应用私有外部存储: " + registryFilePath);
                    return;
                }
            }
        } catch (Exception ignored) {}
        registryFilePath = "/data/local/tmp/wxcode_registry.json";
        XposedBridge.log(TAG + " 降级 /data/local/tmp 注册表: " + registryFilePath);
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
     * 根据包名+UserID计算唯一端口，避免冲突
     */
    private int calculatePort(String packageName, int userId) {
        int basePort = 8088;
        if (userId > 0) {
            if (userId < 100) {
                basePort = basePort + userId;
            } else {
                basePort = 8200 + (userId % 100);
            }
            XposedBridge.log(TAG + " [" + packageName + "] UID端口偏移: " + basePort);
        }
        if (!packageName.equals("com.tencent.mm")) {
            if (packageName.endsWith(":dual")) return basePort + 1;
            if (packageName.endsWith(":clone")) return basePort + 2;
            if (packageName.endsWith("_1")) return basePort + 3;
            if (packageName.endsWith("_2")) return basePort + 4;
            if (packageName.endsWith("_xiaomi")) return basePort + 5;
            if (packageName.endsWith(".dual")) return basePort + 6;
            if (packageName.endsWith(".clone")) return basePort + 7;
            int sum = 0;
            for (int i = "com.tencent.mm".length(); i < packageName.length(); i++) {
                sum += packageName.charAt(i);
            }
            int extraOffset = 8 + (sum % 100);
            int port = basePort + extraOffset;
            if (port > 9000) {
                port = 8096 + ((port - 8096) % 900);
            }
            XposedBridge.log(TAG + " [" + packageName + "] 后缀偏移:" + extraOffset + " 最终端口:" + port);
            return port;
        }
        XposedBridge.log(TAG + " [" + packageName + "] 端口:" + basePort + " UID:" + userId);
        return basePort;
    }

    /**
     * 注册当前分身实例到全局共享注册表
     */
    private void registerInstance(String packageName, int userId, int port, String version) {
        try {
            JSONArray registry = readRegistry();
            registry = cleanExpiredInstances(registry);
            // 移除同包同UID旧记录
            for (int i = 0; i < registry.length(); i++) {
                JSONObject item = registry.getJSONObject(i);
                if (item.getString("packageName").equals(packageName) && item.getInt("userId") == userId) {
                    registry.remove(i);
                    break;
                }
            }
            JSONObject instance = new JSONObject();
            instance.put("packageName", packageName);
            instance.put("userId", userId);
            instance.put("port", port);
            instance.put("version", version);
            instance.put("registerTime", System.currentTimeMillis());
            registry.put(instance);
            writeRegistry(registry);
            XposedBridge.log(TAG + " 全局注册表注册实例: " + packageName + " UID" + userId + " :" + port);
        } catch (Exception e) {
            XposedBridge.log(TAG + " 注册实例失败: " + e.getMessage());
        }
    }

    /**
     * 带共享读锁读取注册表，防止并发损坏JSON
     */
    private JSONArray readRegistry() {
        if (registryFilePath == null) initRegistryFilePath();
        File file = new File(registryFilePath);
        if (!file.exists()) return new JSONArray();
        RandomAccessFile raf = null;
        FileLock lock = null;
        JSONArray result = new JSONArray();
        try {
            raf = new RandomAccessFile(file, "r");
            lock = raf.getChannel().lock(0, Long.MAX_VALUE, true);
            byte[] data = new byte[(int) raf.length()];
            raf.readFully(data);
            result = new JSONArray(new String(data, "UTF-8"));
        } catch (Exception e) {
            XposedBridge.log(TAG + " 读取注册表异常: " + e.getMessage());
        } finally {
            try {
                if (lock != null) lock.release();
                if (raf != null) raf.close();
            } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * 独占写锁写入注册表，多进程串行写入
     */
    private void writeRegistry(JSONArray registry) {
        if (registryFilePath == null) initRegistryFilePath();
        File file = new File(registryFilePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();
        RandomAccessFile raf = null;
        FileLock lock = null;
        try {
            raf = new RandomAccessFile(file, "rw");
            lock = raf.getChannel().lock();
            raf.setLength(0);
            byte[] content = registry.toString().getBytes("UTF-8");
            raf.write(content);
            // 重新授权全局权限
            Runtime.getRuntime().exec("chmod 777 " + registryFilePath);
            file.setReadable(true, false);
            file.setWritable(true, false);
            XposedBridge.log(TAG + " 全局注册表写入成功");
        } catch (Exception e) {
            XposedBridge.log(TAG + " 写入注册表失败: " + e.getMessage() + " path:" + registryFilePath);
        } finally {
            try {
                if (lock != null) lock.release();
                if (raf != null) raf.close();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 清理5分钟过期实例记录
     */
    private JSONArray cleanExpiredInstances(JSONArray registry) {
        long now = System.currentTimeMillis();
        JSONArray cleaned = new JSONArray();
        try {
            for (int i = 0; i < registry.length(); i++) {
                JSONObject item = registry.getJSONObject(i);
                long registerTime = item.getLong("registerTime");
                if (now - registerTime < 300000) {
                    cleaned.put(item);
                }
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " 清理过期实例失败: " + e.getMessage());
        }
        return cleaned;
    }

    /**
     * 初始化全局配置、生命周期、执行模式
     */
    private void initConfig(Context context) {
        this.appContext = context;
        if (context instanceof Application) this.wechatApplication = (Application) context;
        this.prefs = context.getSharedPreferences("wxcode_config", Context.MODE_PRIVATE);
        this.currentMode = prefs.getString("exec_mode", MODE_WORKER_THREAD);
        XposedBridge.log(TAG + " [" + currentPackageName + "] 当前执行模式: " + currentMode);
        hookActivityLifecycle();
        applyExecutionMode();
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
     * 根据配置启动对应保活模式
     */
    private void applyExecutionMode() {
        switch (currentMode) {
            case MODE_FOREGROUND_SERVICE:
                startForegroundService();
                break;
            case MODE_WORKER_THREAD:
                startWorkerThread();
                break;
            case MODE_TEMP_WAKEUP:
                XposedBridge.log(TAG + " 使用临时唤醒模式");
                break;
            default:
                startWorkerThread();
                break;
        }
    }

    /**
     * 前台保活模式：WakeLock+常驻通知提升进程优先级
     */
    private void startForegroundService() {
        if (isForegroundServiceRunning) return;
        try {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wxcode:wakelock_" + currentPackageName);
            wakeLock.acquire(10 * 60 * 1000L);
            String channelId = "wxcode_service_" + currentPackageName;
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(channelId, "wxcode后台服务", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("wxcode HTTP服务保活");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
            Notification.Builder builder = new Notification.Builder(appContext)
                    .setContentTitle("wxcode 服务运行中")
                    .setContentText(currentPackageName + " HTTP端口:" + httpPort)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setPriority(Notification.PRIORITY_LOW)
                    .setOngoing(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setChannelId(channelId);
            nm.notify(httpPort, builder.build());
            isForegroundServiceRunning = true;
            XposedBridge.log(TAG + " 前台保活模式已启动");
        } catch (Exception e) {
            XposedBridge.log(TAG + " 前台服务启动失败:" + e.getMessage());
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            startWorkerThread();
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

    /**
     * 切换执行模式并持久化配置
     */
    private void updateExecutionMode(String newMode) {
        if (newMode.equals(currentMode)) return;
        if (MODE_WORKER_THREAD.equals(currentMode)) stopWorkerThread();
        String old = currentMode;
        currentMode = newMode;
        prefs.edit().putString("exec_mode", newMode).apply();
        XposedBridge.log(TAG + " 模式切换 " + old + " -> " + newMode);
        applyExecutionMode();
    }

    /**
     * 获取当前运行配置JSON
     */
    private JSONObject getConfigInfo() {
        JSONObject config = new JSONObject();
        try {
            config.put("currentMode", currentMode);
            config.put("modeDescriptions", new JSONObject()
                    .put(MODE_FOREGROUND_SERVICE, "前台服务保活 - 进程优先级最高，最稳定")
                    .put(MODE_WORKER_THREAD, "子线程轮询 - 默认模式，平衡性能与稳定性")
                    .put(MODE_TEMP_WAKEUP, "临时唤醒 - 最省电，但可能不稳定")
            );
            config.put("isWorkerThreadRunning", workerThread != null && workerThread.isAlive());
            config.put("isForegroundServiceRunning", isForegroundServiceRunning);
        } catch (Exception e) {
            XposedBridge.log(TAG + " 获取配置失败:" + e.getMessage());
        }
        return config;
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
                // 初始化全局共享注册表路径
                initRegistryFilePath();
                currentUserId = getUserId();
                httpPort = calculatePort(currentPackageName, currentUserId);
                PackageInfo pkgInfo = context.getPackageManager().getPackageInfo(currentPackageName, 0);
                versionName = pkgInfo.versionName;
                XposedBridge.log(TAG + " [" + currentPackageName + "] UID:" + currentUserId + " Ver:" + versionName + " Port:" + httpPort);
                try {
                    JSONObject verCfg = new JSONObject(jsonString).getJSONObject(versionName);
                    j1 = verCfg.getString("j1");
                    c = verCfg.getString("c");
                    XposedBridge.log(TAG + " 版本配置加载成功: " + verCfg);
                } catch (Exception e) {
                    XposedBridge.log(TAG + " 版本配置不存在: " + e.getMessage());
                }
                try {
                    httpServer = new LoginHttpServer(WxLoginHook.this, httpPort, classLoader);
                    httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                    XposedBridge.log(TAG + " HTTP服务启动成功 http://0.0.0.0:" + httpPort);
                    // 写入全局共享注册表
                    registerInstance(currentPackageName, currentUserId, httpPort, versionName);
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
        PowerManager.WakeLock tempWakeLock = null;
        try {
            Class<?> LoginTaskCls = XposedHelpers.findClass("com.tencent.mm.plugin.appbrand.jsapi.auth.JsApiLogin$LoginTask", classLoader);
            Class<?> h2Cls = XposedHelpers.findClass("com.tencent.mm.plugin.appbrand.jsapi.auth.h2", classLoader);
            Class<?> l2Cls = XposedHelpers.findClass("com.tencent.mm.plugin.appbrand.jsapi.auth.l2", classLoader);
            Class<?> cCls = XposedHelpers.findClass(this.c, classLoader);
            Class<?> j1Cls = XposedHelpers.findClass(this.j1, classLoader);
            XposedBridge.log(TAG + " 发起登录 appId=" + str);
            boolean needWake = !isWeChatForeground();
            if (needWake) {
                tempWakeupWeChat();
                Thread.sleep(1000);
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
            Object h2Obj = h2Cls.getConstructor(LoginTaskCls).newInstance(loginTask);
            Object l2Obj = XposedHelpers.newInstance(l2Cls, loginTask, h2Obj);
            Object cObj = ctor.newInstance(str, new LinkedList<>(), 1, "", "", 0, 1271, l2Obj);
            XposedHelpers.callMethod(XposedHelpers.callStaticMethod(j1Cls, "d"), "g", cObj);
            long start = System.currentTimeMillis();
            if (wakeLock == null || !wakeLock.isHeld()) {
                PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
                tempWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wxcode:login_temp_" + currentPackageName);
                tempWakeLock.acquire(30000);
            }
            Handler handler;
            if (MODE_TEMP_WAKEUP.equals(currentMode)) {
                tempWakeupWeChat();
                handler = new Handler(Looper.getMainLooper());
            } else if (MODE_WORKER_THREAD.equals(currentMode) && workerHandler != null) {
                handler = workerHandler;
            } else {
                handler = new Handler(Looper.getMainLooper());
            }
            Object lockObj = new Object();
            Runnable pollTask = new Runnable() {
                @Override
                public void run() {
                    synchronized (lockObj) {
                        try {
                            String code = (String) getField(loginTask, "r");
                            String rawCode = (String) getField(loginTask, "q");
                            if (code == null) {
                                if (System.currentTimeMillis() - start <= 15000) {
                                    handler.postDelayed(this, 200);
                                    return;
                                } else {
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
                            XposedBridge.log(TAG + " 轮询异常:" + e.getMessage());
                            handler.postDelayed(this, 200);
                        }
                    }
                }
            };
            synchronized (lockObj) {
                handler.post(pollTask);
                lockObj.wait(16000);
            }
            if (res[0] == null) res[0] = "{\"err\":-210,\"msg\":\"登录超时\"}";
        } catch (Throwable e) {
            XposedBridge.log(TAG + " doLogin异常:" + e.getMessage());
            res[0] = "{\"err\":-500,\"msg\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        } finally {
            isLoginInFlight = false;
            restoreForegroundState();
            if (tempWakeLock != null && tempWakeLock.isHeld()) {
                tempWakeLock.release();
            }
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
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", info.toString());
                } catch (Exception e) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", "{\"err\":-500,\"msg\":\"" + e.getMessage() + "\"}");
                }
            }

            // /instances 接口：返回所有已启动的实例（实时数据）
            if (uri.equals("/instances")) {
                try {
                    JSONArray registry = outer.readRegistry();
                    registry = outer.cleanExpiredInstances(registry);

                    JSONObject portMap = new JSONObject();
                    for (int i = 0; i < registry.length(); i++) {
                        JSONObject item = registry.getJSONObject(i);
                        String key = item.getString("packageName") + "_User" + item.getInt("userId");
                        portMap.put(key, item.getInt("port"));
                    }

                    JSONObject result = new JSONObject();
                    result.put("instances", registry);
                    result.put("portMap", portMap);
                    result.put("current", outer.currentPackageName);
                    result.put("currentUserId", outer.currentUserId);
                    result.put("currentPort", outer.httpPort);
                    result.put("count", registry.length());
                    result.put("registryFile", outer.registryFilePath);
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", result.toString());
                } catch (Exception e) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", "{\"err\":-500,\"msg\":\"" + e.getMessage() + "\"}");
                }
            }

            // /config 接口：配置执行模式
            if (uri.equals("/config")) {
                try {
                    String mode = iHTTPSession.getParms().get("mode");
                    if (mode != null && !mode.isEmpty()) {
                        outer.updateExecutionMode(mode);
                        JSONObject result = new JSONObject();
                        result.put("success", true);
                        result.put("currentMode", outer.currentMode);
                        result.put("message", "执行模式已更新为: " + mode);
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", result.toString());
                    } else {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", outer.getConfigInfo().toString());
                    }
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
                sb.append(".mode-btn {");
                sb.append("  display: inline-block;");
                sb.append("  padding: 10px 20px;");
                sb.append("  margin: 5px;");
                sb.append("  border: 2px solid #3498db;");
                sb.append("  border-radius: 8px;");
                sb.append("  cursor: pointer;");
                sb.append("  transition: all 0.3s ease;");
                sb.append("  background: white;");
                sb.append("  color: #3498db;");
                sb.append("  font-weight: bold;");
                sb.append("  text-decoration: none;");
                sb.append("}");
                sb.append(".mode-btn:hover {");
                sb.append("  background: #3498db;");
                sb.append("  color: white;");
                sb.append("}");
                sb.append(".mode-btn.active {");
                sb.append("  background: #27ae60;");
                sb.append("  color: white;");
                sb.append("  border-color: #27ae60;");
                sb.append("}");
                sb.append(".mode-desc {");
                sb.append("  font-size: 0.85em;");
                sb.append("  color: #7f8c8d;");
                sb.append("  margin-top: 5px;");
                sb.append("}");
                sb.append(".current-mode {");
                sb.append("  background: #e8f8f5;");
                sb.append("  padding: 10px 15px;");
                sb.append("  border-radius: 8px;");
                sb.append("  border-left: 4px solid #27ae60;");
                sb.append("  margin: 15px 0;");
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

                sb.append("<h2>📦 当前实例信息</h2>");
                sb.append("<p><strong>包名：</strong> <code>").append(outer.currentPackageName).append("</code></p>");
                sb.append("<p><strong>User ID：</strong> <code>").append(String.valueOf(outer.currentUserId)).append("</code> <small style='color:#666;'>（用于区分系统级分身）</small></p>");
                sb.append("<p><strong>HTTP端口：</strong> <code>").append(String.valueOf(outer.httpPort)).append("</code></p>");
                sb.append("<p><strong>微信版本：</strong> <code>").append(outer.versionName).append("</code></p>");

                sb.append("<h2>⚙️ 执行模式配置</h2>");
                sb.append("<div class='current-mode'>");
                sb.append("<strong>当前模式：</strong> <code>").append(outer.currentMode).append("</code>");
                sb.append("</div>");
                sb.append("<p>选择登录请求的执行方式（后台运行稳定性）：</p>");
                sb.append("<div style='text-align: center; margin: 20px 0;'>");
                sb.append("<a class='mode-btn ").append(outer.currentMode.equals("foreground_service") ? "active" : "").append("' href='/config?mode=foreground_service'>前台服务保活</a>");
                sb.append("<div class='mode-desc'>进程优先级最高，最稳定</div>");
                sb.append("<a class='mode-btn ").append(outer.currentMode.equals("worker_thread") ? "active" : "").append("' href='/config?mode=worker_thread'>子线程轮询</a>");
                sb.append("<div class='mode-desc'>默认模式，平衡性能与稳定性</div>");
                sb.append("<a class='mode-btn ").append(outer.currentMode.equals("temp_wakeup") ? "active" : "").append("' href='/config?mode=temp_wakeup'>临时唤醒</a>");
                sb.append("<div class='mode-desc'>最省电，但可能不稳定</div>");
                sb.append("</div>");
                sb.append("<p>💡 提示：切换模式后立即生效，配置会保存到 SharedPreferences</p>");

                sb.append("<h2>📖 API接口说明</h2>");
                sb.append("<table>");
                sb.append("<thead><tr><th>接口</th><th>说明</th><th>示例</th></tr></thead>");
                sb.append("<tbody>");
                sb.append("<tr><td><code>/whoami</code></td><td>返回当前实例信息(JSON)</td><td><a href='/whoami'>点击查看</a></td></tr>");
                sb.append("<tr><td><code>/instances</code></td><td>返回端口映射表(JSON)</td><td><a href='/instances'>点击查看</a></td></tr>");
                sb.append("<tr><td><code>/config</code></td><td>查看/配置执行模式</td><td><a href='/config'>点击查看</a></td></tr>");
                sb.append("<tr><td><code>/login</code></td><td>执行登录获取code</td><td><a href='/login?appId=wxaa3a999db5d744c6'>点击测试</a></td></tr>");
                sb.append("</tbody></table>");
                sb.append("<h2>💡 分身端口映射</h2>");
                sb.append("<p>端口计算公式：<code>基础端口(8088) + User ID偏移 + 包名后缀偏移</code></p>");
                sb.append("<p><code>大于100的userId: (8200+999%100)</code></p>");
                sb.append("<table>");
                sb.append("<thead><tr><th>类型</th><th>标识</th><th>端口</th></tr></thead>");
                sb.append("<tbody>");
                sb.append("<tr><td>主用户微信</td><td><code>com.tencent.mm (User 0)</code></td><td>8088</td></tr>");
                sb.append("<tr><td>系统分身</td><td><code>com.tencent.mm (User 999)</code></td><td>8299</td></tr>");
                sb.append("<tr><td>包名后缀分身</td><td><code>com.tencent.mm:dual</code></td><td>8089</td></tr>");
                sb.append("<tr><td>包名后缀分身</td><td><code>com.tencent.mm:clone</code></td><td>8090</td></tr>");
                sb.append("<tr><td>包名后缀分身</td><td><code>com.tencent.mm_1</code></td><td>8091</td></tr>");
                sb.append("<tr><td>包名后缀分身</td><td><code>com.tencent.mm_2</code></td><td>8092</td></tr>");
                sb.append("<tr><td>包名后缀分身</td><td><code>com.tencent.mm_xiaomi</code></td><td>8093</td></tr>");
                sb.append("<tr><td>其他分身</td><td><code>动态计算</code></td><td>8096-9000</td></tr>");
                sb.append("</tbody></table>");
                sb.append("<p>💡 提示：访问 <a href='/whoami'>/whoami</a> 可确认当前实例信息，访问 <a href='/instances'>/instances</a> 可查看所有实例</p>");
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
    }
}
