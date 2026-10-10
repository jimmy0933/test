# 最新 APK 原始碼 Review

審查來源：`8cc3f98ac46886fb8c72b3b67c9bfb2ca3136a04`（已合併 PR #5 / #6 的 master）。本 PR 重新建置此來源的 APK，功能程式碼保持相同；建置與簽章驗證結果由 GitHub Actions 產生於 `dist/`。下列操作流程問題來自程式碼審查，未宣稱已在 Android 手機重現。

## 已確認修正

- `JSONObject.toString("UTF-8")` 已改為合法的 `toString().getBytes("UTF-8")`。
- 來源勾選使用暫存集合，取消不再改變正式設定。
- 開始掃描時複製來源集合。
- 既有根目錄不再被改成 `DCIM` 下的同名子目錄。
- 快取保存 `removed`，完整搬移／刪除完成後更新快取。
- 拒絕移動授權時，依成功數恢復或清除復原備份。

## 待修正

1. **P1：取得後續批次寫入授權失敗時，沒有還原復原備份。** 第一批全部更新失敗後，若下一批 `createWriteRequest` 或啟動系統授權失敗，`requestWrite()` 的 catch 只清除 pending 與忙碌狀態。新的失敗意圖紀錄仍取代舊紀錄，備份存在但「復原」不會讀取它。應將取消、例外、完成及啟動恢復集中在同一套收尾流程。[位置](https://github.com/jimmy0933/test/blob/8cc3f98ac46886fb8c72b3b67c9bfb2ca3136a04/app/src/main/java/tw/mediaorganizer/MainActivity.java#L199)

2. **P2：復原完成後，已刪除的快取可能被排程重新寫回。** 完成 callback 先呼叫 `saveScanCache(result)`，將舊 result 排入 fileWorker，再清空 result 並刪除 cacheFile。稍後該排程又寫回舊資料，重開 App 會載入失效分組。復原完成應清除快取，避免排程保存舊 result。[位置](https://github.com/jimmy0933/test/blob/8cc3f98ac46886fb8c72b3b67c9bfb2ca3136a04/app/src/main/java/tw/mediaorganizer/MainActivity.java#L232)

3. **P2：搬移中仍可開始新掃描。** `requestScan()` 只检查 busy，不检查 fileBusy。新掃描可能在某檔案搬走前保存舊 path；再次搬移時就以該舊 path 寫入復原紀錄，而不是檔案真正的上一個目錄。應避免在未完成的搬移期間啟動新掃描，或依 URI 同步並重新核對 metadata。[位置](https://github.com/jimmy0933/test/blob/8cc3f98ac46886fb8c72b3b67c9bfb2ca3136a04/app/src/main/java/tw/mediaorganizer/MainActivity.java#L112)

4. **P2：只處理照片／影片時，權限要求和完整授權判斷仍同時依賴兩類媒體。** 僅照片且照片權限完整、影片未授權時，會再次要求影片權限，並顯示媒體授權不完整。應按所選 mediaType 決定必要權限。[位置](https://github.com/jimmy0933/test/blob/8cc3f98ac46886fb8c72b3b67c9bfb2ca3136a04/app/src/main/java/tw/mediaorganizer/MainActivity.java#L113)、[完成訊息](https://github.com/jimmy0933/test/blob/8cc3f98ac46886fb8c72b3b67c9bfb2ca3136a04/app/src/main/java/tw/mediaorganizer/MainActivity.java#L127)

5. **P2：停止掃描未發布最後處理結果。** Scanner 仍每五個項目才發布快照；取消時 MainActivity 保存的 result 是最後已發布版本，可能漏掉在兩次發布間已找到的群組。應在取消前發布最後快照，或在群組形成時立即發布。[位置](https://github.com/jimmy0933/test/blob/8cc3f98ac46886fb8c72b3b67c9bfb2ca3136a04/app/src/main/java/tw/mediaorganizer/MainActivity.java#L128)

## 驗證界線

相似度測試目前包含 13 個檢查；仍未涵蓋以上檔案操作、復原、快取及權限流程。請以本次 `dist/test-results.txt` 和 `dist/build-info.txt` 的實際建置結果為準。既有 master 中較早提交的 APK 不是上述新來源的產物，本 PR 會以重新建置的 APK 取代它。
