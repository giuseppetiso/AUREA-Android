"use strict";

const assert = require("node:assert/strict");
const AureaRigCore = require("./rig-core.js");
const manifest = require("./rig-manifest.js");

const core = new AureaRigCore();
assert.deepEqual(core.snapshot(), {
  state: "idle",
  viseme: "REST",
  gaze: { x: 0, y: 0 },
  gazeTarget: { x: 0, y: 0 },
  head: { x: 0, y: 0, roll: 0 },
  headTarget: { x: 0, y: 0, roll: 0 },
});

assert.equal(core.setState("listen"), "listen");
assert.deepEqual(core.setGaze(9, -9), { x: 1, y: -1 });
assert.equal(core.gazeMoving(), true);
for (let index = 0; index < 100; index += 1) core.stepGaze(16, 165);
assert.equal(core.gazeMoving(), false);
assert.deepEqual(core.gaze, { x: 1, y: -1 });

assert.deepEqual(core.setHeadPose(4, -4, .5), { x: 1, y: -1, roll: .5 });
assert.equal(core.headMoving(), true);
for (let index = 0; index < 500; index += 1) core.stepHead(16, 760);
assert.equal(core.headMoving(), false);
assert.deepEqual(core.head, { x: 1, y: -1, roll: .5 });

for (const viseme of AureaRigCore.VISEMES) {
  assert.equal(core.setViseme(viseme.toLowerCase()), viseme);
}
assert.equal(core.setState("idle"), "idle");
assert.equal(core.snapshot().viseme, "REST");
assert.throws(() => core.setState("alarm"));
assert.throws(() => core.setViseme("X"));

const timing = manifest.motion.blink;
assert.equal(AureaRigCore.blinkPose(-1, timing), "open");
assert.equal(AureaRigCore.blinkPose(16, timing), "quarter");
assert.equal(AureaRigCore.blinkPose(38, timing), "half");
assert.equal(AureaRigCore.blinkPose(64, timing), "threeQuarter");
assert.equal(AureaRigCore.blinkPose(86, timing), "closed");
assert.equal(AureaRigCore.blinkPose(112, timing), "closed");
assert.equal(AureaRigCore.blinkPose(170, timing), "threeQuarter");
assert.equal(AureaRigCore.blinkPose(205, timing), "half");
assert.equal(AureaRigCore.blinkPose(247, timing), "quarter");
assert.equal(AureaRigCore.blinkPose(999, timing), "open");

assert.equal(AureaRigCore.bridgeViseme("REST", "A"), "MBP");
assert.equal(AureaRigCore.bridgeViseme("A", "O"), "U");
assert.equal(AureaRigCore.bridgeViseme("E", "L"), "CNS");

const validated = AureaRigCore.validateTrack([
  { at: 120, viseme: "A" },
  { at: 0, viseme: "mbp", transitionMs: 2 },
]);
assert.deepEqual(validated, [
  { at: 0, viseme: "MBP", transitionMs: 36 },
  { at: 120, viseme: "A" },
]);
assert.throws(() => AureaRigCore.validateTrack([]));
assert.throws(() => AureaRigCore.validateTrack([{ at: -1, viseme: "A" }]));
assert.throws(() => AureaRigCore.validateTrack([{ at: 1, viseme: "ZZ" }]));

assert.deepEqual(AureaRigCore.STATES, ["idle", "listen", "speak"]);
assert.equal(AureaRigCore.VISEMES.length, 12);
assert.equal(manifest.version, "05");
assert.equal(manifest.motion.activeFps, 60);
assert.equal(manifest.motion.lowPowerFps, 20);
assert.equal(manifest.motion.headMotion.enabled, true);
assert.ok(manifest.motion.headMotion.maxRollDeg <= .8);

console.log("AUREA Rig Preview 05: tutti i test core superati.");
