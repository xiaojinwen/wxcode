package android.content.res;

import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import de.robv.android.xposed.callbacks.XC_LayoutInflated;

/* JADX INFO: loaded from: classes2.dex */
public class XResources extends Resources {

    public static class ResourceNames {
        public final String fullName;
        public final int id;
        public final String name;
        public final String pkg;
        public final String type;

        ResourceNames() {
            throw new RuntimeException("Stub!");
        }

        public boolean equals(String pkg, String name, String type, int id) {
            throw new RuntimeException("Stub!");
        }
    }

    public static abstract class DrawableLoader {
        public abstract Drawable newDrawable(XResources xResources, int i) throws Throwable;

        public DrawableLoader() {
            throw new RuntimeException("Stub!");
        }

        public Drawable newDrawableForDensity(XResources res, int id, int density) throws Throwable {
            throw new RuntimeException("Stub!");
        }
    }

    public static class DimensionReplacement {
        public DimensionReplacement(float value, int unit) {
            throw new RuntimeException("Stub!");
        }

        public float getDimension(DisplayMetrics metrics) {
            throw new RuntimeException("Stub!");
        }

        public int getDimensionPixelOffset(DisplayMetrics metrics) {
            throw new RuntimeException("Stub!");
        }

        public int getDimensionPixelSize(DisplayMetrics metrics) {
            throw new RuntimeException("Stub!");
        }
    }

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    XResources() {
        super(null, null, null);
        throw new RuntimeException("Stub!");
    }

    public String getPackageName() {
        throw new RuntimeException("Stub!");
    }

    public static String getPackageNameDuringConstruction() {
        throw new RuntimeException("Stub!");
    }

    public void setReplacement(int id, Object replacement) {
        throw new RuntimeException("Stub!");
    }

    @Deprecated
    public void setReplacement(String fullName, Object replacement) {
        throw new RuntimeException("Stub!");
    }

    public void setReplacement(String pkg, String type, String name, Object replacement) {
        throw new RuntimeException("Stub!");
    }

    public static void setSystemWideReplacement(int id, Object replacement) {
        throw new RuntimeException("Stub!");
    }

    @Deprecated
    public static void setSystemWideReplacement(String fullName, Object replacement) {
        throw new RuntimeException("Stub!");
    }

    public static void setSystemWideReplacement(String pkg, String type, String name, Object replacement) {
        throw new RuntimeException("Stub!");
    }

    public static int getFakeResId(String resName) {
        throw new RuntimeException("Stub!");
    }

    public static int getFakeResId(Resources res, int id) {
        throw new RuntimeException("Stub!");
    }

    public int addResource(Resources res, int id) {
        throw new RuntimeException("Stub!");
    }

    public XC_LayoutInflated.Unhook hookLayout(int id, XC_LayoutInflated callback) {
        throw new RuntimeException("Stub!");
    }

    @Deprecated
    public XC_LayoutInflated.Unhook hookLayout(String fullName, XC_LayoutInflated callback) {
        throw new RuntimeException("Stub!");
    }

    public XC_LayoutInflated.Unhook hookLayout(String pkg, String type, String name, XC_LayoutInflated callback) {
        throw new RuntimeException("Stub!");
    }

    public static XC_LayoutInflated.Unhook hookSystemWideLayout(int id, XC_LayoutInflated callback) {
        throw new RuntimeException("Stub!");
    }

    @Deprecated
    public static XC_LayoutInflated.Unhook hookSystemWideLayout(String fullName, XC_LayoutInflated callback) {
        throw new RuntimeException("Stub!");
    }

    public static XC_LayoutInflated.Unhook hookSystemWideLayout(String pkg, String type, String name, XC_LayoutInflated callback) {
        throw new RuntimeException("Stub!");
    }
}
