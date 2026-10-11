#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .test-classes
javac -d .test-classes app/src/main/java/tw/mediaorganizer/Similarity.java tests/SimilarityTest.java tests/ScanAndDeleteTest.java
java -cp .test-classes tw.mediaorganizer.SimilarityTest
java -cp .test-classes tw.mediaorganizer.ScanAndDeleteTest
