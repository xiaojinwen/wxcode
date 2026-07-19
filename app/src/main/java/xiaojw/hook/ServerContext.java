package xiaojw.hook;

import org.json.JSONArray;

/**
 * 承载 WxLoginHook 与 LoginHttpServer 之间共享的上下文数据和方法回调。
 * 用于解耦 LoginHttpServer，使其不再持有 WxLoginHook 实例引用。
 *
 * WxLoginHook 在初始化时创建此对象填充数据并注入回调，
 * LoginHttpServer 通过此对象读取数据、调用方法。
 */
public class ServerContext {

    // ========== 实例数据字段 ==========

    public String currentPackageName;
    public int currentUserId;
    public int httpPort;
    public String versionName;
    public String j1, c, a1, a7;
    public String j1StaticMethod, j1InstanceMethod;
    public String jsonString;

    // ========== 静态常量 ==========

    public static final String COMMON_PACKAGE = "com.tencent.mm";
    public static final int MASTER_PORT = 8088;
    public static final String DEFAULT_AUTO_APP_ID = "wxaa3a999db5d744c6";

    // ========== 方法回调 ==========

    public Callback methods;

    /**
     * LoginHttpServer 需要调用的 WxLoginHook 方法集合。
     * WxLoginHook 在构建 ServerContext 时通过匿名类注入具体实现，
     * 从而保持自身方法的 private 可见性，无需对外暴露。
     */
    public interface Callback {
        void saveConfig(String configJson);
        String doLogin(String appId, ClassLoader classLoader);
        JSONArray getMemoryInstances();
        JSONArray fetchMasterInstances();
        void switchPowerMode(boolean powerSaver);
        void registerInstanceInMemory(String packageName, int userId, int port, String version);
        void resetConfig();
        String longestCommonPrefix(java.util.List<String> strs);
        String shortenClass(String s, String prefix);
    }
}
