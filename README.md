# InkAsk for Android

InkAsk 是一款面向 Android 平板的 PDF 阅读和问答应用。可以选取 PDF 或已有回答中的文字来创建问题、继续追问，并在设备上保存便签和附件。

阅读器由 Kotlin 和 Jetpack Compose 实现。构建时将 Web 端的阅读上下文、引用和 Markdown 导出逻辑打包到本地 JavaScript 运行时。共享代码快照取自 InkAsk-web 本地工作树，基准提交为 `45789abe4ef2ac1ef79fbc00348e7822974bcfe8`。

## 构建与测试

需要 Node.js 22.12、24 或 26 及以上版本、JDK 17 或更高版本和 Android SDK 36。命令行构建时将 `ANDROID_HOME` 指向 SDK。

```sh
npm ci
npm test
./android/gradlew -p android testDebugUnitTest assembleDebug
```

调试包位于 `android/app/build/outputs/apk/debug/app-debug.apk`，支持 Android 12 及以上。已安装版本需要使用相同的签名才能原位更新。

长按并拖动选取文字。单指滑动翻阅，双指缩放，双击 PDF 可在适应窗口和放大之间切换。点击回答中的独立公式可放大查看和复制 LaTeX。
