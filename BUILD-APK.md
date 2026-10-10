# APK 建置與下載

GitHub Actions 的 `Build signed APK` 工作流程會在每次 push 時建置，也可手動執行。

## 產物

- `dist/Media-Organizer.apk`：使用專案既有本地測試簽章的 Release APK。
- `dist/Media-Organizer.apk.sha256`：APK 的 SHA-256。
- `dist/apk-signature.txt`：apksigner 簽章驗證結果。
- `dist/apk-metadata.txt`：套件名稱、版本與 Android 相容性資訊。
- `dist/test-results.txt`：相似度邏輯測試輸出。
- `dist/build-info.txt`：來源 commit、建置執行位置與模型檢查结果。

每次建置會將這些檔案上傳為 Actions artifact；在 `codex/package-apk-*` 分支的 push 建置中，亦會將經驗證的產物提交至同一分支，方便從 PR 直接下載 APK。合併後的 master 建置只上傳 artifact，不自行提交產物。

## 建置環境

- Ubuntu 24.04、Temurin JDK 17
- Gradle 8.9、Android Gradle Plugin 8.7.3
- Android SDK 35、Build Tools 34.0.0 / 35.0.0
- 第三方 Actions 固定至完整 commit SHA

## 驗證範圍

流程會執行專案的 13 項相似度邏輯測試，編譯 Release APK，驗證簽章、套件資訊、模型內容，以及 ARM64 / ARMv7 / x86 / x86_64 函式庫封裝。

這些檢查不代表手機上的掃描、刪除、搬移、復原或人臉辨識已通過操作測試。此次 APK 重新建置自 `8cc3f98ac46886fb8c72b3b67c9bfb2ca3136a04`，功能程式碼不額外修改。最新 review 與尚未完成的操作測試列於 [APK-REVIEW.md](APK-REVIEW.md)。
