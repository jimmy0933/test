package tw.mediaorganizer;

import java.util.*;

public class ScanAndDeleteTest {
    static int checks = 0;

    static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAILED: " + label);
        }
        checks++;
        System.out.println("PASS " + label);
    }

    // Helper class to simulate MainActivity scan lifecycle and busy state handling
    static class MockScanController {
        boolean busy = false;
        long resultGeneration = 0;
        Object currentResult = null;
        boolean lastResultSaved = false;

        void startScan(long jobGen) {
            busy = true;
            resultGeneration = jobGen;
            currentResult = null;
        }

        void clearThumbs(long newGen) {
            resultGeneration = newGen;
            currentResult = null;
        }

        void onComplete(Object scanned, long generation) {
            busy = false;
            if (generation < resultGeneration) {
                return;
            }
            resultGeneration = generation;
            currentResult = scanned;
            lastResultSaved = true;
        }

        void onError(String message, long generation) {
            busy = false;
            if (generation < resultGeneration) {
                return;
            }
            resultGeneration = generation;
            lastResultSaved = true;
        }
    }

    // Helper class to simulate batch delete logic and track direct delete counts
    static class MockDeleteController {
        static class Item {
            String uri;
            boolean removed = false;
            Item(String uri) { this.uri = uri; }
        }

        List<Item> pendingDeletes;
        Map<String, Integer> directDeleteAttempts = new HashMap<>();
        int operationDeleted = 0;
        boolean fileBusy = false;

        void confirmDelete(List<Item> items) {
            pendingDeletes = new ArrayList<>(items);
            fileBusy = true;
            operationDeleted = 0;
            requestDelete();
        }

        // Direct delete step - only called once per delete operation
        void requestDelete() {
            if (pendingDeletes == null || pendingDeletes.isEmpty()) {
                fileBusy = false;
                return;
            }
            List<Item> directList = new ArrayList<>(pendingDeletes);
            List<Item> needPermissionList = new ArrayList<>();
            int directDeleted = 0;

            for (Item item : directList) {
                directDeleteAttempts.put(item.uri, directDeleteAttempts.getOrDefault(item.uri, 0) + 1);
                // Simulate that direct delete fails for all items (requiring MediaStore permission)
                needPermissionList.add(item);
            }

            operationDeleted += directDeleted;
            pendingDeletes = needPermissionList;

            if (pendingDeletes.isEmpty()) {
                fileBusy = false;
            } else {
                requestDeleteBatch();
            }
        }

        void requestDeleteBatch() {
            if (pendingDeletes == null || pendingDeletes.isEmpty()) {
                fileBusy = false;
                return;
            }
            // Requests permission for next batch (up to 100)
            // No direct delete calls here!
        }

        void applyDeleteBatch() {
            if (pendingDeletes == null || pendingDeletes.isEmpty()) return;
            int batchSize = Math.min(100, pendingDeletes.size());
            List<Item> batch = new ArrayList<>(pendingDeletes.subList(0, batchSize));
            for (Item item : batch) {
                item.removed = true;
            }
            pendingDeletes.removeAll(batch);
            operationDeleted += batch.size();

            if (pendingDeletes != null && !pendingDeletes.isEmpty()) {
                requestDeleteBatch(); // Calls requestDeleteBatch, NOT requestDelete!
            } else {
                pendingDeletes = null;
                fileBusy = false;
            }
        }
    }

    public static void testScanClearCacheOnComplete() {
        MockScanController controller = new MockScanController();
        long scanJobGen = 100;

        // 1. Start scan
        controller.startScan(scanJobGen);
        check(controller.busy, "scan started and busy is true");

        // 2. Clear thumbs cache during scan (generates higher generation gen = 101)
        long clearCacheGen = 101;
        controller.clearThumbs(clearCacheGen);

        // 3. Scan completes with old scanJobGen (100 < 101)
        Object oldResult = new Object();
        controller.onComplete(oldResult, scanJobGen);

        // 4. Verify busy state was reset to false and stale result was not saved
        check(!controller.busy, "busy state reset to false after scan completion despite clear cache");
        check(controller.currentResult == null, "stale scan result ignored after clear cache");
    }

    public static void testScanClearCacheOnError() {
        MockScanController controller = new MockScanController();
        long scanJobGen = 200;

        // 1. Start scan
        controller.startScan(scanJobGen);
        check(controller.busy, "scan started for error test");

        // 2. Clear thumbs cache during scan
        controller.clearThumbs(201);

        // 3. Scan cancelled / errors with old scanJobGen (200 < 201)
        controller.onError("Scan cancelled", scanJobGen);

        // 4. Verify busy state was reset
        check(!controller.busy, "busy state reset to false after scan error/cancellation despite clear cache");
    }

    public static void testBatchDeleteDirectDeleteLimit() {
        MockDeleteController controller = new MockDeleteController();
        List<MockDeleteController.Item> items = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            items.add(new MockDeleteController.Item("content://media/external/file/" + i));
        }

        // 1. Confirm delete for 250 items
        controller.confirmDelete(items);

        // Verify direct delete attempted exactly once per item initially
        for (MockDeleteController.Item item : items) {
            check(controller.directDeleteAttempts.get(item.uri) == 1,
                  "direct delete attempted exactly once for " + item.uri + " initially");
        }

        // 2. Approve Batch 1 (100 items)
        controller.applyDeleteBatch();
        check(controller.operationDeleted == 100, "batch 1 deleted 100 items");

        // 3. Approve Batch 2 (100 items)
        controller.applyDeleteBatch();
        check(controller.operationDeleted == 200, "batch 2 deleted 200 items in total");

        // 4. Approve Batch 3 (50 items)
        controller.applyDeleteBatch();
        check(controller.operationDeleted == 250, "batch 3 deleted all 250 items");
        check(!controller.fileBusy, "fileBusy is false after all batches completed");

        // Verify direct delete was NEVER attempted a second time for any URI
        for (MockDeleteController.Item item : items) {
            check(controller.directDeleteAttempts.get(item.uri) == 1,
                  "direct delete attempt count remains exactly 1 for " + item.uri + " after all permission batches");
        }
    }

    static class MockScanService {
        long currentGeneration = 0;
        long currentScanGeneration = 0;
        boolean scanning = false;
        Object currentSnapshot = null;

        long clearCacheAsync() {
            return ++currentGeneration;
        }

        long startScanJob() {
            scanning = true;
            currentSnapshot = null;
            long gen = ++currentGeneration;
            currentScanGeneration = gen;
            return gen;
        }

        void setListener(MockActivityListener listener) {
            if (listener != null && scanning) {
                if (currentSnapshot != null && currentScanGeneration >= currentGeneration) {
                    listener.onPartialResult(currentSnapshot, currentScanGeneration);
                }
            }
        }
    }

    interface MockActivityListener {
        void onPartialResult(Object partial, long generation);
        void onComplete(Object scanned, long generation);
    }

    static class MockRecreatedActivity implements MockActivityListener {
        long resultGeneration;
        Object currentResult = null;
        boolean busy = false;

        void onCreate(MockScanService service) {
            this.resultGeneration = service.currentGeneration;
            service.setListener(this);
            if (service.scanning) {
                if (service.currentScanGeneration >= this.resultGeneration) {
                    this.resultGeneration = service.currentScanGeneration;
                }
                this.busy = true;
            }
        }

        @Override
        public void onPartialResult(Object partial, long generation) {
            if (generation < resultGeneration) return;
            resultGeneration = generation;
            currentResult = partial;
        }

        @Override
        public void onComplete(Object scanned, long generation) {
            busy = false;
            if (generation < resultGeneration) return;
            resultGeneration = generation;
            currentResult = scanned;
        }
    }

    public static void testRecreateActivityAfterClearCacheWithBackgroundScan() {
        MockScanService service = new MockScanService();
        long scanGen = service.startScanJob();

        Object partialResult = new Object();
        service.currentSnapshot = partialResult;

        long clearCacheGen = service.clearCacheAsync();
        check(clearCacheGen > scanGen, "clear cache incremented currentGeneration");

        MockRecreatedActivity newActivity = new MockRecreatedActivity();
        newActivity.onCreate(service);

        check(newActivity.resultGeneration == clearCacheGen, "recreated activity maintained higher clearCache generation");
        check(newActivity.currentResult == null, "stale snapshot was not replayed or accepted by recreated activity");

        Object staleCompleteResult = new Object();
        newActivity.onComplete(staleCompleteResult, scanGen);

        check(newActivity.currentResult == null, "stale complete scan result ignored by recreated activity");
        check(!newActivity.busy, "busy state reset upon completion");
    }

    public static void main(String[] args) {
        testScanClearCacheOnComplete();
        testScanClearCacheOnError();
        testBatchDeleteDirectDeleteLimit();
        testRecreateActivityAfterClearCacheWithBackgroundScan();
        System.out.println(checks + " scan and delete tests passed successfully!");
    }
}
