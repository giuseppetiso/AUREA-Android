(function (root, factory) {
  const RigCore = factory();
  if (typeof module === "object" && module.exports) module.exports = RigCore;
  root.AureaRigCore = RigCore;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const STATES = Object.freeze(["idle", "listen", "speak"]);
  const VISEMES = Object.freeze([
    "REST", "MBP", "A", "E", "I", "O", "U", "FV", "L", "SZ", "CH", "CNS",
  ]);
  const ROUNDED = new Set(["O", "U", "CH"]);
  const CLOSED = new Set(["REST", "MBP"]);
  const HEAD_EPSILON = .0008;

  function clamp(value, min, max) {
    return Math.min(max, Math.max(min, Number(value) || 0));
  }

  function smoothingFactor(deltaMs, timeConstantMs) {
    return 1 - Math.exp(-Math.max(0, deltaMs) / Math.max(1, timeConstantMs));
  }

  function blinkPose(elapsedMs, timing) {
    const closeMs = timing.closeMs;
    const holdMs = timing.holdMs;
    const openMs = timing.openMs;
    const total = closeMs + holdMs + openMs;
    if (elapsedMs < 0 || elapsedMs >= total) return "open";

    if (elapsedMs < closeMs) {
      const progress = elapsedMs / closeMs;
      if (progress < .14) return "open";
      if (progress < .34) return "quarter";
      if (progress < .58) return "half";
      if (progress < .82) return "threeQuarter";
      return "closed";
    }

    if (elapsedMs < closeMs + holdMs) return "closed";
    const progress = (elapsedMs - closeMs - holdMs) / openMs;
    if (progress < .18) return "closed";
    if (progress < .42) return "threeQuarter";
    if (progress < .69) return "half";
    if (progress < .91) return "quarter";
    return "open";
  }

  function bridgeViseme(from, to) {
    const start = String(from || "REST").toUpperCase();
    const end = String(to || "REST").toUpperCase();
    if (start === end) return end;
    if (CLOSED.has(start) || CLOSED.has(end)) return "MBP";
    if (ROUNDED.has(start) || ROUNDED.has(end)) return "U";
    return "CNS";
  }

  function validateTrack(track) {
    if (!Array.isArray(track) || !track.length) {
      throw new Error("La traccia visemi deve contenere almeno un cue.");
    }
    const normalized = track.map((cue, index) => {
      if (!cue || typeof cue !== "object") {
        throw new Error(`Cue visema non valido all'indice ${index}.`);
      }
      const at = Number(cue.at);
      const viseme = String(cue.viseme || "").toUpperCase();
      if (!Number.isFinite(at) || at < 0) {
        throw new Error(`Tempo visema non valido all'indice ${index}.`);
      }
      if (!VISEMES.includes(viseme)) {
        throw new Error(`Visema non valido all'indice ${index}: ${cue.viseme}`);
      }
      const transitionMs = cue.transitionMs == null
        ? undefined
        : clamp(cue.transitionMs, 36, 220);
      return { at, viseme, ...(transitionMs ? { transitionMs } : {}) };
    });
    return normalized.sort((left, right) => left.at - right.at);
  }

  class AureaRigCore {
    constructor() {
      this.state = "idle";
      this.viseme = "REST";
      this.gaze = { x: 0, y: 0 };
      this.gazeTarget = { x: 0, y: 0 };
      this.head = { x: 0, y: 0, roll: 0 };
      this.headTarget = { x: 0, y: 0, roll: 0 };
    }

    setState(next) {
      if (!STATES.includes(next)) throw new Error(`Stato non valido: ${next}`);
      this.state = next;
      if (next !== "speak") this.viseme = "REST";
      return this.state;
    }

    setGaze(x, y) {
      this.gazeTarget = { x: clamp(x, -1, 1), y: clamp(y, -1, 1) };
      return { ...this.gazeTarget };
    }

    stepGaze(deltaMs, timeConstantMs = 165) {
      const factor = smoothingFactor(deltaMs, timeConstantMs);
      this.gaze.x += (this.gazeTarget.x - this.gaze.x) * factor;
      this.gaze.y += (this.gazeTarget.y - this.gaze.y) * factor;
      if (Math.abs(this.gazeTarget.x - this.gaze.x) < .001) {
        this.gaze.x = this.gazeTarget.x;
      }
      if (Math.abs(this.gazeTarget.y - this.gaze.y) < .001) {
        this.gaze.y = this.gazeTarget.y;
      }
      return { ...this.gaze };
    }

    gazeMoving() {
      return Math.abs(this.gazeTarget.x - this.gaze.x) >= .001 ||
        Math.abs(this.gazeTarget.y - this.gaze.y) >= .001;
    }

    setHeadPose(x, y, roll) {
      this.headTarget = {
        x: clamp(x, -1, 1),
        y: clamp(y, -1, 1),
        roll: clamp(roll, -1, 1),
      };
      return { ...this.headTarget };
    }

    stepHead(deltaMs, timeConstantMs = 760) {
      const factor = smoothingFactor(deltaMs, timeConstantMs);
      for (const axis of ["x", "y", "roll"]) {
        this.head[axis] += (this.headTarget[axis] - this.head[axis]) * factor;
        if (Math.abs(this.headTarget[axis] - this.head[axis]) < HEAD_EPSILON) {
          this.head[axis] = this.headTarget[axis];
        }
      }
      return { ...this.head };
    }

    headMoving() {
      return ["x", "y", "roll"].some(
        axis => Math.abs(this.headTarget[axis] - this.head[axis]) >= HEAD_EPSILON,
      );
    }

    setViseme(next) {
      const normalized = String(next || "").toUpperCase();
      if (!VISEMES.includes(normalized)) {
        throw new Error(`Visema non valido: ${next}`);
      }
      this.viseme = normalized;
      return this.viseme;
    }

    snapshot() {
      return {
        state: this.state,
        viseme: this.viseme,
        gaze: { ...this.gaze },
        gazeTarget: { ...this.gazeTarget },
        head: { ...this.head },
        headTarget: { ...this.headTarget },
      };
    }
  }

  AureaRigCore.STATES = STATES;
  AureaRigCore.VISEMES = VISEMES;
  AureaRigCore.clamp = clamp;
  AureaRigCore.smoothingFactor = smoothingFactor;
  AureaRigCore.blinkPose = blinkPose;
  AureaRigCore.bridgeViseme = bridgeViseme;
  AureaRigCore.validateTrack = validateTrack;
  return AureaRigCore;
});
