@echo off
echo 正在下载Xposed API...
curl -L -o "app\libs\api-82.jar" "https://github.com/rovo89/XposedApi/releases/download/v82/api-82.jar"
echo 下载完成！
echo 请修改 app\build.gradle，将 compileOnly 'de.robv.android.xposed:api:82' 改为：
echo   compileOnly files('libs/api-82.jar')
pause