# Noise-suppression native sources: sync note

Synced from the Flutter SDK (`gravix_cloud`, branch `feat/ns`, commit `0ef8a63`) on 2026-10-21;
`gxns/` resynced at `c0eef6a` (review #1: gx_ns.c gates our AGC on a requested engine, test_agc uses an identity
studio engine), gx_ns.c SHA-256 `9ba28e7aa85656b266351745ab401883f150ab836f5a30623d5b30673faac61f`;
resynced at `3784674` (pause gate: pre-gain and AGC never lift the noise floor over DTX; test_dtx_quiet),
gx_ns.c SHA-256 `70ddcb00fa1576863efb1866fb6bcb8a5b75763f5a6e43f87d789132ec4aee29`; tests resynced at `a56dcfc` (test_dtx_quiet onset case; gx_ns.c unchanged).
Resynced at `aaab339` (talk detector on what the NS leaves + pause expander: DTX on traffic noise; new
test_dtx_street), gx_ns.c SHA-256 `76ceaf102265738f2e3c1ef813cc0516dad1cbe1aa07d03d149fe7cc5ce78bf8`.
Resynced at `03b9a4a` (status reports the APPLIED pre-gain / AGC / pause attenuation; test_room_replay and
tools/gxns_replay: the room recording they read is private and stays outside the repo, the test skips without it),
gx_ns.c SHA-256 `3d43a958cc9198fee084c296468aca83bc7c53c742bb513b674f587d062eb727`, gx_ns.h SHA-256
`5157382419be22a1da8b72e1c94fa7e02f765f7cb5d2f52d0ed8aa81468894ba`. The JNI shim's nativeStatus follows the
Flutter shim (out[8..10]).
Resynced at `f488ddd` (the pause gate follows sure talk: DTX in a fan-noise room; new test_wind),
gx_ns.c SHA-256 `e6bfc6380ff8242ec9f78bd64306a7e9f929e3e3074afd0a2f9e3d8f860d9eeb`, gx_ns.h unchanged.
Resynced at `33f7346` (word onsets open the gate at once: no first-syllable dips),
gx_ns.c SHA-256 `e0f875ac1021641f9dc19b78050e795fd2aa1fec2f34bf2ee87b5c64a52e127b`, gx_ns.h unchanged.

| Here | Flutter source | Changes |
|---|---|---|
| `gxns/` | `native/gxns/` | none (byte-identical; `diff -r` clean) |
| `gx_ns_jni.c`, `gx_dfn_engine.c` | `android/src/main/cpp/` | JNI symbol prefix only: `com.gravixcloud.rtc.noise.GxNsNative` / `GxNsStudio` |
| `CMakeLists.txt` | `android/src/main/cpp/CMakeLists.txt` | `GXNS_ROOT` points at `./gxns` |
| `third_party/onnxruntime/` | `android/src/main/cpp/third_party/onnxruntime/` | none |
| `../../studio/jniLibs/arm64-v8a/*.so` | `android/src/studio/jniLibs/arm64-v8a/` | none (same SHA-256) |

Edit the C core in the Flutter repo first, then copy it here (and keep the policy
fixture `src/test/resources/fixtures/ns_policy_cases.json` byte-identical too).
Resynced at `368a786` (quiet mics count as talk again: talk floor -80 dB),
gx_ns.c SHA-256 `9b9a2c8209da71abac2f87b1496104ad6ee980fc43ecb2e3631f3517f6f1837b`, gx_ns.h unchanged.
Resynced at `013c168` (talk judged on the louder of DFN3 and RNNoise; studio_suppress vs RNNoise),
gx_ns.c SHA-256 `0801745c10bb56db4e467863a2657175bc3b63f1b3e50994196eaf8e36eafea9`, gx_ns.h SHA-256 `2d5503911d8ad8acc8de4d467b0444a0aeb70cccdb34a4968604c63dbf11d7d9`.
