# 相簿整理員 v1.0

Android 11 以上的離線照片／影片整理工具。無網路權限，原始檔與人臉特徵不會上傳。

## 安裝與操作

1. 在手機下載 `Media-Organizer-v1.0.apk`，允許下載來源安裝此應用程式後安裝。
2. 點「開始掃描」，選擇授權所有照片與影片。Android 14 以上若只授權部分照片，只能分析所選檔案。
3. 保持應用程式畫面開啟直到掃描完成。可按「停止」取消；不會更動原始媒體。
4. 切換「重複／相似／人物／全部」。點縮圖或檔名，用手機原有播放器或相簿預覽。
5. 填入目的資料夾名稱，如 `旅行整理`。目的地固定為手機內部儲存空間 `DCIM/旅行整理/`，照片、影片可移到同一資料夾。目的地會記住，能逐組重複移入。
6. 勾選個別檔案或整組，按「移到此資料夾」。程式確認後，Android 會再要求檔案寫入授權。
7. 可使用「復原上次移動」。只保留最近一次移動操作的紀錄；跨批次仍屬同一次操作，每批最多 100 個寫入授權。

## 三種比對

- **完全重複**：同類媒體先比大小，再以完整檔案 SHA-256 核對。不同編碼或畫質不會被視為完全相同。
- **畫面相似**：照片以 64 位元 dHash、色彩、長寬比和畫面變異度比較。影片抽樣 7 個位置，至少 60% 且 3 個以上畫面符合。適合近似副本、轉檔或小幅亮度改變；不保證能找到裁切、鏡像、重剪或所有語意相似內容。
- **疑似同一人物**：Android FaceDetector 偵測清楚人臉，依眼線校正後，以 MobileFaceNet 離線產生 192 維特徵並計算餘弦相似度。每個檔案最多保留 8 個不同人臉樣本。可跨照片與影片分組；一組代表至少一張抽樣人臉接近，不代表影片中所有人都相同。

人物功能屬實驗性估計，沒有在你的相簿資料上驗證準確率；側臉、遮擋、模糊、年齡變化、多人、膚色與光線可能造成誤判或漏判。分數閾值不是準確率，也不推測人物姓名。移動前請人工預覽。

## 檔案與限制

- 只讀取 Android MediaStore 已索引且授權的手機內部儲存空間圖片／影片；不涵蓋雲端限定檔案、其他 App 私有空間、安全資料夾或所有 SD 卡搬移情境。
- 移動以 MediaStore 修改資料夾位置，不是把原始媒體複製後刪除。從外部磁碟搬到內部磁碟尚未支援。
- 同名檔案加隨機識別碼，避免覆蓋。失敗項目會計數；成功項目仍可復原。
- 復原紀錄在移動前寫入，異常中斷時可能含未移動完成的項目。復原會嘗試回到原位置；不存在或失去權限的檔案會失敗。
- 本版不自動刪除、不在背景持續監控、不自動移動新加入的檔案、不辨識人物姓名。
- 大型相簿需等待，掃描期間畫面保持亮起；離開程式後不承諾背景完成。再次掃描會重新分析，不沿用舊分組。
- 「清除縮圖快取」只清除本程式的分析縮圖；不影響你的原始媒體與復原紀錄。移除 App 會清除復原紀錄。

## 開發建置

使用 Android Studio 匯入此資料夾，JDK 17、Gradle 8.9、Android SDK 35，AGP 8.7.3。執行 `gradle :app:assembleDebug` 會產生可安裝 Debug APK。第一次需下載 Gradle／Android SDK／TensorFlow Lite 相依套件。本 ZIP 未包含 Gradle wrapper 或 Android SDK。

交付 APK 與 Gradle 設定使用相同的本地測試簽章，已保留於 `signing/local-testing.p12`，方便重新編譯後更新安裝。密碼與別名已在 `app/build.gradle` 設定；此簽章只用於本機測試交付。若改用不同簽章，需先移除現有 APK，移除將使本程式快取與復原紀錄消失。

## 第三方來源

- TensorFlow Lite 2.16.1，Apache-2.0：https://github.com/tensorflow/tensorflow
- MobileFaceNet 模型來源（附原始 MIT 授權）：https://github.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing
- Android MediaStore：https://developer.android.com/training/data-storage/shared/media
- FaceDetector：https://developer.android.com/reference/android/media/FaceDetector

詳見 `TEST-REPORT.md` 的驗證範圍。
