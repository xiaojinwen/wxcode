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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.app.ActivityManager;
import android.app.Application;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import fi.iki.elonen.NanoHTTPD;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.LinkedList;
import org.json.JSONArray;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public class WxLoginHook implements IXposedHookLoadPackage {
    private static final long CALLBACK_TIMEOUT_MS = 15000;
    private static final String DEFAULT_AUTO_APP_ID = "wxaa3a999db5d744c6";
    private static final String TAG = "xiaojw-wxcode";
    private static final String WECHAT_PACKAGE_PREFIX = "com.tencent.mm";
    // 共享文件路径：用于记录所有已启动的微信实例
    private static final String REGISTRY_FILE = "/data/local/tmp/wxcode_registry.json";

    // 执行模式常量
    private static final String MODE_FOREGROUND_SERVICE = "foreground_service";  // 前台服务保活
    private static final String MODE_WORKER_THREAD = "worker_thread";            // 子线程轮询
    private static final String MODE_TEMP_WAKEUP = "temp_wakeup";                // 临时唤醒

    private LoginHttpServer httpServer;
    private boolean isLoginInFlight = false;
    private Context appContext;
    private SharedPreferences prefs;
    private String currentMode = MODE_WORKER_THREAD;  // 默认使用子线程模式
    private HandlerThread workerThread;
    private Handler workerHandler;
    private boolean isForegroundServiceRunning = false;
    private PowerManager.WakeLock wakeLock;  // WakeLock 保持 CPU 运行
    private Application wechatApplication;   // 微信 Application 实例
    private Activity fakeTopActivity;        // 用于伪造前台的 Activity 引用
    private ClassLoader savedClassLoader;    // 保存 ClassLoader 用于恢复状态
    private boolean wasForegroundBeforeLogin = false;  // 记录登录前的前台状态

    // 版本配置JSON字符串（使用文本块，无需转义双引号）
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
    private int httpPort = 8088;

    /**
     * 检查是否为微信或微信分身包名
     * 支持的格式:
     * - com.tencent.mm (原始微信)
     * - com.tencent.mm:dual (某些厂商分身)
     * - com.tencent.mm_xxxxx (小米等厂商分身)
     * - com.tencent.mm.clone (克隆应用)
     * - com.tencent.mm.xxx (其他变体)
     */
    private boolean isWeChatPackage(String packageName) {
        if (packageName == null) return false;
        // 精确匹配原始微信
        if (packageName.equals("com.tencent.mm")) return true;
        // 匹配分身格式: com.tencent.mm:dual, com.tencent.mm_xxx, com.tencent.mm.xxx
        if (packageName.startsWith("com.tencent.mm:")) return true;
        if (packageName.startsWith("com.tencent.mm_")) return true;
        if (packageName.startsWith("com.tencent.mm.")) return true;
        // 匹配其他可能的分身格式
        if (packageName.contains("tencent.mm") && packageName.length() > "com.tencent.mm".length()) return true;
        return false;
    }

    /**
     * 根据包名计算HTTP端口，确保不同分身使用不同端口
     * 端口分配策略:
     * - 原始微信(com.tencent.mm): 8088
     * - 常见分身格式按后缀分配固定端口
     * - 其他格式使用包名哈希在大范围内分配
     */
    private int calculatePort(String packageName) {
        if (packageName.equals("com.tencent.mm")) {
            return 8088; // 原始微信使用默认端口
        }

        // 常见分身后缀的固定端口映射
        if (packageName.endsWith(":dual")) return 8089;
        if (packageName.endsWith(":clone")) return 8090;
        if (packageName.endsWith("_1")) return 8091;
        if (packageName.endsWith("_2")) return 8092;
        if (packageName.endsWith("_xiaomi")) return 8093;
        if (packageName.endsWith(".dual")) return 8094;
        if (packageName.endsWith(".clone")) return 8095;

        // 其他格式：使用完整包名计算更独特的端口
        // 将包名每个字符的ASCII值累加，确保更均匀分布
        int sum = 0;
        for (int i = "com.tencent.mm".length(); i < packageName.length(); i++) {
            sum += packageName.charAt(i);
        }
        // 端口范围: 8096 - 8995，约900个端口，足够避免冲突
        int port = 8096 + (sum % 900);
        XposedBridge.log(TAG + " 计算端口: " + packageName + " -> " + port);
        return port;
    }

    /**
     * 注册当前实例到共享文件
     * 所有微信实例启动时都会写入自己的信息，实现跨进程发现
     */
    private void registerInstance(String packageName, int port, String version) {
        try {
            JSONArray registry = readRegistry();

            // 移除旧记录（如果已存在）
            for (int i = 0; i < registry.length(); i++) {
                JSONObject item = registry.getJSONObject(i);
                if (item.getString("packageName").equals(packageName)) {
                    registry.remove(i);
                    break;
                }
            }

            // 添加新记录
            JSONObject instance = new JSONObject();
            instance.put("packageName", packageName);
            instance.put("port", port);
            instance.put("version", version);
            instance.put("registerTime", System.currentTimeMillis());
            registry.put(instance);

            // 写入文件
            writeRegistry(registry);
            XposedBridge.log(TAG + " 实例已注册: " + packageName + ":" + port);
        } catch (Exception e) {
            XposedBridge.log(TAG + " 注册实例失败: " + e.getMessage());
        }
    }

    /**
     * 从共享文件读取所有已注册的实例
     */
    private JSONArray readRegistry() {
        try {
            File file = new File(REGISTRY_FILE);
            if (!file.exists()) {
                return new JSONArray();
            }
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[fis.available()];
            fis.read(data);
            fis.close();
            return new JSONArray(new String(data, "UTF-8"));
        } catch (Exception e) {
            XposedBridge.log(TAG + " 读取注册表失败: " + e.getMessage());
            return new JSONArray();
        }
    }

    /**
     * 写入注册表到共享文件
     */
    private void writeRegistry(JSONArray registry) {
        try {
            File file = new File(REGISTRY_FILE);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(registry.toString().getBytes("UTF-8"));
            fos.close();
            // 设置文件权限为可读写（所有进程都能访问）
            file.setReadable(true, false);
            file.setWritable(true, false);
        } catch (Exception e) {
            XposedBridge.log(TAG + " 写入注册表失败: " + e.getMessage());
        }
    }

    /**
     * 清理过期的实例记录（超过5分钟未更新的视为已关闭）
     */
    private JSONArray cleanExpiredInstances(JSONArray registry) {
        long now = System.currentTimeMillis();
        JSONArray cleaned = new JSONArray();
        try {
            for (int i = 0; i < registry.length(); i++) {
                JSONObject item = registry.getJSONObject(i);
                long registerTime = item.getLong("registerTime");
                // 保留最近5分钟内注册的实例
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
     * 初始化配置
     */
    private void initConfig(Context context) {
        this.appContext = context;
        if (context instanceof Application) {
            this.wechatApplication = (Application) context;
        }
        this.prefs = context.getSharedPreferences("wxcode_config", Context.MODE_PRIVATE);
        this.currentMode = prefs.getString("exec_mode", MODE_WORKER_THREAD);
        XposedBridge.log(TAG + " [" + currentPackageName + "] 当前执行模式: " + currentMode);

        // Hook Activity 生命周期，记录当前 Activity
        hookActivityLifecycle();

        // 根据配置启动相应的服务
        applyExecutionMode();
    }

    /**
     * Hook 微信 Activity 生命周期，记录顶部 Activity
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
                    XposedBridge.log(TAG + " [" + currentPackageName + "] Activity Resumed: " + activity.getClass().getSimpleName());
                }

                @Override
                public void onActivityPaused(Activity activity) {
                    XposedBridge.log(TAG + " [" + currentPackageName + "] Activity Paused: " + activity.getClass().getSimpleName());
                }

                @Override
                public void onActivityStopped(Activity activity) {}

                @Override
                public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {}

                @Override
                public void onActivityDestroyed(Activity activity) {
                    if (fakeTopActivity == activity) {
                        fakeTopActivity = null;
                    }
                }
            };
            wechatApplication.registerActivityLifecycleCallbacks(callback);
            XposedBridge.log(TAG + " [" + currentPackageName + "] Activity 生命周期 Hook 已注册");
        } catch (Exception e) {
            XposedBridge.log(TAG + " [" + currentPackageName + "] 注册 ActivityLifecycleCallbacks 失败: " + e.getMessage());
        }
    }

    /**
     * 强制伪造前台状态（通过反射设置微信内部状态）
     * 尝试多种方式让微信认为自己在前台
     * 注意：会保存原始状态，登录完成后需要调用 restoreForegroundState 恢复
     */
    private void forceForegroundState(ClassLoader classLoader) {
        XposedBridge.log(TAG + " [" + currentPackageName + "] 尝试伪造前台状态...");
        savedClassLoader = classLoader;

        // 方法1：尝试 Hook 微信的 ForegroundDetector（常见的前台检测类）
        try {
            Class<?> foregroundClass = XposedHelpers.findClassIfExists("com.tencent.mm.sdk.platformtools.ForegroundDetector", classLoader);
            if (foregroundClass != null) {
                // 先保存原始状态
                try {
                    Field isForegroundField = foregroundClass.getDeclaredField("isForeground");
                    isForegroundField.setAccessible(true);
                    wasForegroundBeforeLogin = isForegroundField.getBoolean(null);
                    XposedBridge.log(TAG + " [" + currentPackageName + "] 原始前台状态: " + wasForegroundBeforeLogin);

                    // 设置为前台
                    isForegroundField.set(null, true);
                    XposedBridge.log(TAG + " [" + currentPackageName + "] ForegroundDetector.isForeground 已设置为 true");
                } catch (Exception e) {
                    XposedBridge.log(TAG + " [" + currentPackageName + "] 设置 ForegroundDetector 字段失败: " + e.getMessage());
                }

                // 尝试设置 foreground 字段为 true（可能是这个名字）
                try {
                    Field foregroundField = foregroundClass.getDeclaredField("foreground");
                    foregroundField.setAccessible(true);
                    foregroundField.set(null, true);
                    XposedBridge.log(TAG + " [" + currentPackageName + "] ForegroundDetector.foreground 已设置为 true");
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " [" + currentPackageName + "] ForegroundDetector 类不存在或访问失败");
        }

        // 方法2：尝试 Hook 微信的 MMAppForegroundMonitor
        try {
            Class<?> monitorClass = XposedHelpers.findClassIfExists("com.tencent.mm.sdk.platformtools.MMAppForegroundMonitor", classLoader);
            if (monitorClass != null) {
                Field[] fields = monitorClass.getDeclaredFields();
                for (Field field : fields) {
                    if (field.getName().contains("foreground") || field.getName().contains("isForeground")) {
                        try {
                            field.setAccessible(true);
                            field.set(null, true);
                            XposedBridge.log(TAG + " [" + currentPackageName + "] MMAppForegroundMonitor." + field.getName() + " 已设置为 true");
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}

        XposedBridge.log(TAG + " [" + currentPackageName + "] 前台状态伪造完成");
    }

    /**
     * 恢复原始前台状态
     * 在登录完成后调用，避免微信持续认为自己在前台导致额外耗电
     */
    private void restoreForegroundState() {
        if (savedClassLoader == null) return;

        XposedBridge.log(TAG + " [" + currentPackageName + "] 恢复原始前台状态...");

        // 恢复 ForegroundDetector 的原始状态
        try {
            Class<?> foregroundClass = XposedHelpers.findClassIfExists("com.tencent.mm.sdk.platformtools.ForegroundDetector", savedClassLoader);
            if (foregroundClass != null) {
                try {
                    Field isForegroundField = foregroundClass.getDeclaredField("isForeground");
                    isForegroundField.setAccessible(true);
                    isForegroundField.set(null, wasForegroundBeforeLogin);
                    XposedBridge.log(TAG + " [" + currentPackageName + "] ForegroundDetector.isForeground 已恢复为 " + wasForegroundBeforeLogin);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        savedClassLoader = null;
        XposedBridge.log(TAG + " [" + currentPackageName + "] 前台状态恢复完成");
    }

    /**
     * 检查微信是否在前台运行
     * @return true 如果微信有可见的 Activity
     */
    private boolean isWeChatForeground() {
        // 方法1：检查 fakeTopActivity（通过 ActivityLifecycleCallbacks 记录）
        if (fakeTopActivity != null && !fakeTopActivity.isFinishing()) {
            XposedBridge.log(TAG + " [" + currentPackageName + "] 微信在前台（通过 ActivityLifecycleCallbacks）");
            return true;
        }

        // 方法2：检查进程重要性
        try {
            ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            for (ActivityManager.RunningAppProcessInfo process : am.getRunningAppProcesses()) {
                if (process.processName.equals(currentPackageName)) {
                    // IMPORTANCE_FOREGROUND = 100, IMPORTANCE_VISIBLE = 200
                    boolean isForeground = process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
                    XposedBridge.log(TAG + " [" + currentPackageName + "] 进程重要性: " + process.importance + ", 是否前台: " + isForeground);
                    return isForeground;
                }
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " [" + currentPackageName + "] 检查进程重要性失败: " + e.getMessage());
        }

        // 方法3：检查是否有 Activity 在运行（通过 ActivityManager.getRunningTasks）
        try {
            ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            // Android 5.0+ 只能获取自己应用的任务信息
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                java.util.List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                if (tasks != null && !tasks.isEmpty()) {
                    ActivityManager.RunningTaskInfo topTask = tasks.get(0);
                    if (topTask.topActivity != null &&
                        topTask.topActivity.getPackageName().equals(currentPackageName)) {
                        XposedBridge.log(TAG + " [" + currentPackageName + "] 微信在前台（通过 RunningTasks）");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " [" + currentPackageName + "] 检查 RunningTasks 失败: " + e.getMessage());
        }

        XposedBridge.log(TAG + " [" + currentPackageName + "] 微信不在前台");
        return false;
    }

    /**
     * 应用当前执行模式
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
                // 临时唤醒模式不需要预先启动服务
                XposedBridge.log(TAG + " [" + currentPackageName + "] 使用临时唤醒模式");
                break;
            default:
                startWorkerThread();
                break;
        }
    }

    /**
     * 启动前台服务保活
     * 在 Xposed 环境中无法直接启动 Service，使用 WakeLock + 通知 + 进程优先级提升
     */
    private void startForegroundService() {
        if (isForegroundServiceRunning) {
            XposedBridge.log(TAG + " [" + currentPackageName + "] 前台服务已运行");
            return;
        }
        try {
            // 1. 获取 WakeLock，保持 CPU 运行
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wxcode:wakelock_" + currentPackageName
            );
            wakeLock.acquire(10 * 60 * 1000L); // 最多持有10分钟，防止永久占用
            XposedBridge.log(TAG + " [" + currentPackageName + "] WakeLock 已获取");

            // 2. 创建通知渠道和通知
            String channelId = "wxcode_service_" + currentPackageName;
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);

            // Android 8.0+ 需要创建通知渠道
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "wxcode 后台服务",
                    NotificationManager.IMPORTANCE_LOW  // 低重要性，但比 MIN 更稳定
                );
                channel.setDescription("保持 wxcode HTTP 服务在后台运行");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }

            // 创建通知
            Notification.Builder builder = new Notification.Builder(appContext)
                .setContentTitle("wxcode 服务运行中")
                .setContentText(currentPackageName + " | 点击查看状态")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(Notification.PRIORITY_LOW)
                .setOngoing(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setChannelId(channelId);
            }

            Notification notification = builder.build();
            nm.notify(httpPort, notification);  // 显示通知，提升进程可见性
            XposedBridge.log(TAG + " [" + currentPackageName + "] 通知已显示");

            // 3. 尝试提升进程优先级（通过 ActivityManager）
            try {
                ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
                for (ActivityManager.RunningAppProcessInfo process : am.getRunningAppProcesses()) {
                    if (process.processName.equals(currentPackageName)) {
                        // 设置为重要进程（IMPORTANCE_FOREGROUND_SERVICE 级别）
                        XposedBridge.log(TAG + " [" + currentPackageName + "] 当前进程重要性: " + process.importance);
                        break;
                    }
                }
            } catch (Exception e) {
                XposedBridge.log(TAG + " [" + currentPackageName + "] 获取进程信息失败: " + e.getMessage());
            }

            isForegroundServiceRunning = true;
            XposedBridge.log(TAG + " [" + currentPackageName + "] 前台服务保活模式已激活");

        } catch (Exception e) {
            XposedBridge.log(TAG + " [" + currentPackageName + "] 启动前台服务失败: " + e.getMessage());
            // 释放 WakeLock
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            // 失败时回退到子线程模式
            startWorkerThread();
        }
    }

    /**
     * 启动子线程用于轮询
     */
    private void startWorkerThread() {
        if (workerThread != null && workerThread.isAlive()) {
            XposedBridge.log(TAG + " [" + currentPackageName + "] 工作线程已运行");
            return;
        }
        try {
            workerThread = new HandlerThread("wxcode_worker_" + httpPort);
            workerThread.start();
            workerHandler = new Handler(workerThread.getLooper());
            XposedBridge.log(TAG + " [" + currentPackageName + "] 工作线程已启动");
        } catch (Exception e) {
            XposedBridge.log(TAG + " [" + currentPackageName + "] 启动工作线程失败: " + e.getMessage());
        }
    }

    /**
     * 停止工作线程
     */
    private void stopWorkerThread() {
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            workerHandler = null;
        }
    }

    /**
     * 临时唤醒微信到前台
     */
    private void tempWakeupWeChat() {
        try {
            Intent intent = appContext.getPackageManager().getLaunchIntentForPackage(currentPackageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                appContext.startActivity(intent);
                XposedBridge.log(TAG + " [" + currentPackageName + "] 已临时唤醒微信");
                // 等待微信启动
                Thread.sleep(500);
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " [" + currentPackageName + "] 临时唤醒失败: " + e.getMessage());
        }
    }

    /**
     * 更新执行模式配置
     */
    private void updateExecutionMode(String newMode) {
        if (newMode.equals(currentMode)) {
            XposedBridge.log(TAG + " [" + currentPackageName + "] 模式未变化: " + newMode);
            return;
        }

        // 停止旧模式
        if (currentMode.equals(MODE_WORKER_THREAD)) {
            stopWorkerThread();
        }

        // 更新配置
        String oldMode = currentMode;
        currentMode = newMode;
        prefs.edit().putString("exec_mode", newMode).apply();
        XposedBridge.log(TAG + " [" + currentPackageName + "] 执行模式已切换: " + oldMode + " -> " + newMode);

        // 启动新模式
        applyExecutionMode();
    }

    /**
     * 获取当前配置信息
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
            XposedBridge.log(TAG + " 获取配置信息失败: " + e.getMessage());
        }
        return config;
    }

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        // 支持微信分身：检测所有微信相关包
        if (!isWeChatPackage(loadPackageParam.packageName)) {
            return;
        }
        currentPackageName = loadPackageParam.packageName;
        httpPort = calculatePort(currentPackageName);
        XposedBridge.log(TAG + " 检测到微信包: " + currentPackageName + ", 端口: " + httpPort);
        try {
            Class<?> cls = Class.forName("android.app.Application");
            Object[] objArr = new Object[2];
            try {
                objArr[0] = Class.forName("android.content.Context");
                objArr[1] = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                        super.afterHookedMethod(methodHookParam);
                        Context context = (Context) methodHookParam.args[0];
                        ClassLoader classLoader = context.getClassLoader();
                        // 使用当前包名获取版本信息（支持分身）
                        PackageInfo packageInfo = context.getPackageManager().getPackageInfo(WxLoginHook.this.currentPackageName, 0);
                        WxLoginHook.this.versionName = packageInfo.versionName;
                        XposedBridge.log(TAG + " [" + WxLoginHook.this.currentPackageName + "] 当前版本: " + WxLoginHook.this.versionName);
                        try {
                            JSONObject jSONObject = new JSONObject(WxLoginHook.this.jsonString).getJSONObject(WxLoginHook.this.versionName);
                            WxLoginHook.this.j1 = jSONObject.getString("j1");
                            WxLoginHook.this.c = jSONObject.getString("c");
                            XposedBridge.log(TAG + " [" + WxLoginHook.this.currentPackageName + "] 已读取" + WxLoginHook.this.versionName + "配置: " + jSONObject);
                        } catch (Exception e) {
                            XposedBridge.log(TAG + " [" + WxLoginHook.this.currentPackageName + "] 版本配置读取失败: " + e.getMessage());
                            e.printStackTrace();
                        }
                        try {
                            WxLoginHook.this.httpServer = new LoginHttpServer(WxLoginHook.this, WxLoginHook.this.httpPort, classLoader);
                            WxLoginHook.this.httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                            XposedBridge.log(TAG + " [" + WxLoginHook.this.currentPackageName + "] HTTP服务启动成功: http://设备IP:" + WxLoginHook.this.httpPort + "/login");
                            // 注册当前实例到共享文件，供其他实例发现
                            WxLoginHook.this.registerInstance(WxLoginHook.this.currentPackageName, WxLoginHook.this.httpPort, WxLoginHook.this.versionName);
                            // 初始化执行模式配置
                            WxLoginHook.this.initConfig(context);
                        } catch (IOException e2) {
                            XposedBridge.log(TAG + " [" + WxLoginHook.this.currentPackageName + "] HTTP服务启动失败: " + e2.getMessage());
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
        PowerManager.WakeLock tempWakeLock = null;  // 临时 WakeLock，用于登录期间保活
        try {
            Class<?> clsFindClass = XposedHelpers.findClass("com.tencent.mm.plugin.appbrand.jsapi.auth.JsApiLogin$LoginTask", classLoader);
            Class clsFindClass2 = XposedHelpers.findClass("com.tencent.mm.plugin.appbrand.jsapi.auth.h2", classLoader);
            Class clsFindClass3 = XposedHelpers.findClass("com.tencent.mm.plugin.appbrand.jsapi.auth.l2", classLoader);
            Class<?> clsFindClass4 = XposedHelpers.findClass(this.c, classLoader);
            Class clsFindClass5 = XposedHelpers.findClass(this.j1, classLoader);
            XposedBridge.log(TAG + " [" + currentPackageName + "] 发起登录请求: appId=" + str);

            // 检查微信是否在前台，如果不在前台则自动唤醒
            boolean needWakeup = !isWeChatForeground();
            if (needWakeup) {
                XposedBridge.log(TAG + " [" + currentPackageName + "] 微信不在前台，正在唤醒...");
                tempWakeupWeChat();
                // 等待微信启动
                Thread.sleep(1000);
                // 再次检查
                if (!isWeChatForeground()) {
                    XposedBridge.log(TAG + " [" + currentPackageName + "] 唤醒后仍不在前台，继续尝试登录");
                }
            }

            // 强制伪造前台状态（尝试绕过微信的前台检测）
            forceForegroundState(classLoader);

            final Object objNewInstance = XposedHelpers.newInstance(clsFindClass, new Object[0]);
            setField(objNewInstance, "o", "login");
            setField(objNewInstance, "p", str);
            setField(objNewInstance, "s", Integer.valueOf(1));
            setField(objNewInstance, "v", "");
            setField(objNewInstance, "t", Integer.valueOf(0));
            setField(objNewInstance, "u", Integer.valueOf(0));
            setField(objNewInstance, "A", Integer.valueOf(1271));
            XposedHelpers.callMethod(XposedHelpers.callStaticMethod(clsFindClass5, "d", new Object[0]), "g", new Object[]{findHe0cConstructor(clsFindClass4).newInstance(str, new LinkedList(), Integer.valueOf(1), "", "", Integer.valueOf(0), Integer.valueOf(1271), XposedHelpers.newInstance(clsFindClass3, new Object[]{objNewInstance, clsFindClass2.getConstructor(clsFindClass).newInstance(objNewInstance)}))});
            final long jCurrentTimeMillis = System.currentTimeMillis();

            // 登录前确保 WakeLock 被持有（临时获取，最多30秒）
            if (wakeLock == null || !wakeLock.isHeld()) {
                try {
                    PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
                    tempWakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "wxcode:login_temp_" + currentPackageName
                    );
                    tempWakeLock.acquire(30 * 1000L);  // 最多30秒
                    XposedBridge.log(TAG + " [" + currentPackageName + "] 临时 WakeLock 已获取");
                } catch (Exception e) {
                    XposedBridge.log(TAG + " [" + currentPackageName + "] 获取临时 WakeLock 失败: " + e.getMessage());
                }
            }

            // 根据执行模式选择 Handler
            final Handler handler;
            if (currentMode.equals(MODE_TEMP_WAKEUP)) {
                // 临时唤醒模式：先唤醒微信到前台
                XposedBridge.log(TAG + " [" + currentPackageName + "] 临时唤醒模式：唤醒微信");
                tempWakeupWeChat();
                handler = new Handler(Looper.getMainLooper());
            } else if (currentMode.equals(MODE_WORKER_THREAD) && workerHandler != null) {
                // 子线程模式：使用工作线程 Handler
                XposedBridge.log(TAG + " [" + currentPackageName + "] 子线程模式：使用工作线程轮询");
                handler = workerHandler;
            } else {
                // 前台服务模式或默认：使用主线程 Handler
                XposedBridge.log(TAG + " [" + currentPackageName + "] 前台服务模式：使用主线程轮询");
                handler = new Handler(Looper.getMainLooper());
            }

            final Object obj = new Object();
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    String str2;
                    String str3;
                    synchronized (obj) {
                        try {
                            str2 = (String) WxLoginHook.this.getField(objNewInstance, "r");
                            str3 = (String) WxLoginHook.this.getField(objNewInstance, "q");
                        } catch (Throwable th) {
                            XposedBridge.log(TAG + " 轮询异常: " + th.getMessage());
                            handler.postDelayed(this, 200);
                            return;
                        }
                        if (str2 == null) {
                            if (System.currentTimeMillis() - jCurrentTimeMillis <= 15000) {
                                handler.postDelayed(this, 200);
                                return;
                            } else {
                                strArr[0] = "{\"err\":-210,\"msg\":\"登录请求超时\"}";
                                synchronized (obj) {
                                    obj.notify();
                                }
                                return;
                            }
                        }
                        String strClassifyCode = WxLoginHook.this.classifyCode(str3);
                        strArr[0] = String.format("{\"err\":0,\"msg\":\"success\",\"appId\":\"%s\",\"status\":\"%s\",\"code\":\"%s\",\"codeType\":\"%s\",\"codeLength\":%d}", str, str2, str3, strClassifyCode, str3 == null ? 0 : str3.length());
                        synchronized (obj) {
                            obj.notify();
                        }
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
        } catch (Throwable e) {
            XposedBridge.log(TAG + " doLogin异常: " + e.getMessage());
            strArr[0] = "{\"err\":-500,\"msg\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        } finally {
            this.isLoginInFlight = false;
            // 恢复原始前台状态（避免持续耗电）
            restoreForegroundState();
            // 释放临时 WakeLock
            if (tempWakeLock != null && tempWakeLock.isHeld()) {
                try {
                    tempWakeLock.release();
                    XposedBridge.log(TAG + " [" + currentPackageName + "] 临时 WakeLock 已释放");
                } catch (Exception e) {
                    XposedBridge.log(TAG + " [" + currentPackageName + "] 释放临时 WakeLock 失败: " + e.getMessage());
                }
            }
        }
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
        查找微信内部类的构造函数
        参数签名: (String, LinkedList, Integer, String, String, Integer, Integer, Object)
    */
    private java.lang.reflect.Constructor<?> findHe0cConstructor(java.lang.Class<?> clazz) {
        // 查找接受8个参数的构造函数
        for (java.lang.reflect.Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 8) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        // 如果没找到8参数的，尝试查找其他构造函数
        for (java.lang.reflect.Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            if (constructor.getParameterCount() >= 6) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        throw new RuntimeException("找不到合适的构造函数 for " + clazz.getName());
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

            String uri = iHTTPSession.getUri();

            // /whoami 接口：返回当前实例信息（JSON格式）
            if (uri.equals("/whoami")) {
                try {
                    JSONObject info = new JSONObject();
                    info.put("packageName", this.this$0.currentPackageName);
                    info.put("port", this.this$0.httpPort);
                    info.put("version", this.this$0.versionName);
                    info.put("j1", this.this$0.j1);
                    info.put("c", this.this$0.c);
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", info.toString());
                } catch (Exception e) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", "{\"err\":-500,\"msg\":\"" + e.getMessage() + "\"}");
                }
            }

            // /instances 接口：返回所有已启动的实例（实时数据）
            if (uri.equals("/instances")) {
                try {
                    JSONArray registry = this.this$0.readRegistry();
                    registry = this.this$0.cleanExpiredInstances(registry);

                    JSONObject result = new JSONObject();
                    result.put("instances", registry);
                    result.put("current", this.this$0.currentPackageName);
                    result.put("currentPort", this.this$0.httpPort);
                    result.put("count", registry.length());
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
                        // 更新配置
                        this.this$0.updateExecutionMode(mode);
                        JSONObject result = new JSONObject();
                        result.put("success", true);
                        result.put("currentMode", this.this$0.currentMode);
                        result.put("message", "执行模式已更新为: " + mode);
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", result.toString());
                    } else {
                        // 返回当前配置
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", this.this$0.getConfigInfo().toString());
                    }
                } catch (Exception e) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", "{\"err\":-500,\"msg\":\"" + e.getMessage() + "\"}");
                }
            }

            // /login 接口：执行登录
            if (uri.equals("/login")) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", this.this$0.doLogin(iHTTPSession.getParms().getOrDefault("appId", "wxaa3a999db5d744c6"), this.classLoader));
            }

            // 其他路径：显示HTML帮助页面
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
                    sb.append("</style>");
                    sb.append("</head>");
                    sb.append("<body>");
                    sb.append("<div class='container'>");
                    sb.append("<h1>📦 wxcode </h1>");
                    sb.append("<h2>📦 当前实例信息</h2>");
                    sb.append("<p><strong>包名：</strong> <code>").append(this.this$0.currentPackageName).append("</code></p>");
                    sb.append("<p><strong>HTTP端口：</strong> <code>").append(this.this$0.httpPort).append("</code></p>");
                    sb.append("<p><strong>微信版本：</strong> <code>").append(this.this$0.versionName).append("</code></p>");

                    // 执行模式配置区域
                    sb.append("<h2>⚙️ 执行模式配置</h2>");
                    sb.append("<div class='current-mode'>");
                    sb.append("<strong>当前模式：</strong> <code>").append(this.this$0.currentMode).append("</code>");
                    sb.append("</div>");
                    sb.append("<p>选择登录请求的执行方式（后台运行稳定性）：</p>");
                    sb.append("<div style='text-align: center; margin: 20px 0;'>");
                    // 前台服务模式按钮
                    sb.append("<a class='mode-btn ").append(this.this$0.currentMode.equals("foreground_service") ? "active" : "").append("' href='/config?mode=foreground_service'>前台服务保活</a>");
                    sb.append("<div class='mode-desc'>进程优先级最高，最稳定</div>");
                    // 子线程模式按钮
                    sb.append("<a class='mode-btn ").append(this.this$0.currentMode.equals("worker_thread") ? "active" : "").append("' href='/config?mode=worker_thread'>子线程轮询</a>");
                    sb.append("<div class='mode-desc'>默认模式，平衡性能与稳定性</div>");
                    // 临时唤醒模式按钮
                    sb.append("<a class='mode-btn ").append(this.this$0.currentMode.equals("temp_wakeup") ? "active" : "").append("' href='/config?mode=temp_wakeup'>临时唤醒</a>");
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
                    sb.append("<table>");
                    sb.append("<thead><tr><th>包名</th><th>端口</th></tr></thead>");
                    sb.append("<tbody>");
                    sb.append("<tr><td><code>com.tencent.mm</code></td><td>8088</td></tr>");
                    sb.append("<tr><td><code>com.tencent.mm:dual</code></td><td>8089</td></tr>");
                    sb.append("<tr><td><code>com.tencent.mm:clone</code></td><td>8090</td></tr>");
                    sb.append("<tr><td><code>com.tencent.mm_1</code></td><td>8091</td></tr>");
                    sb.append("<tr><td><code>com.tencent.mm_2</code></td><td>8092</td></tr>");
                    sb.append("<tr><td><code>com.tencent.mm_xiaomi</code></td><td>8093</td></tr>");
                    sb.append("<tr><td><code>其他分身</code></td><td>8096-8995(动态计算)</td></tr>");
                    sb.append("</tbody></table>");
                    sb.append("<p>💡 提示：访问 <a href='/whoami'>/whoami</a> 可确认当前实例信息</p>");
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
