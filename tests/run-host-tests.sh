#!/usr/bin/env bash
# Device-independent tests for Linux/macOS and Git Bash with JDK 17 on PATH.
set -euo pipefail
cd "$(dirname "$0")/.."
out=build/host-tests
mkdir -p "$out"
javac -encoding UTF-8 -d "$out" \
  shared/src/main/java/dev/pico/facialprobe/{TrackingBuffer,TrackingData,DirectFaceMapper,OscFacePacket,OscDestination,TrackingMode,StreamHealth,ResourceScope,FramePump,DeliveryLedger,TransmissionRates,XDeliveryStats}.java \
  bridge/src/main/java/dev/pico/facialprobe/PeerProtocol.java \
  tests/{DirectOscTest,TrackingBufferTest,PeerProtocolTest,CadenceTest,ForwardingTest,IndependentExpressionTest}.java
for test in DirectOscTest TrackingBufferTest PeerProtocolTest CadenceTest ForwardingTest IndependentExpressionTest; do
  java -cp "$out" "dev.pico.facialprobe.$test"
done
