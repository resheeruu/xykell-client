# Performance

## 1. Targets
Low RAM/storage/CPU/GPU overhead, fast startup, incremental + lazy module loading. Never load optional subsystems unneeded. Phone constraints apply to the build too (small deps, cached/incremental builds).

## 2. Feature set (each version-gated; destabilizing hacks excluded)
FPS counter/limiter/unlock-where-supported, dynamic + background FPS, frame-time graph, entity/render/particle/animation/cloud/weather/fog optimizations, memory/CPU/GPU readouts, performance profiles + low-end mode + render-distance presets. `PerformanceManager` auto-profiles the device and recommends safe settings (M1: readout only).

## 3. Measurement rule
No perf claim without observed on-device numbers (before/after, same build/scene). BedrockTools' FPS Unlocker + Atlas' unlocker prove the category; Xykell implements its own behind VersionAdapter.
