(() => {
  "use strict";

  const config = window.AUREA_RIG_MANIFEST;
  const core = new AureaRigCore();
  const canvas = document.querySelector("#aurea-overlay");
  const ctx = canvas.getContext("2d", { alpha: true });
  const backgroundImage = document.querySelector("#aurea-background");
  const headImage = document.querySelector("#aurea-head");
  const hairImage = document.querySelector("#aurea-hair");
  const torsoImage = document.querySelector("#aurea-torso");
  const headGroup = document.querySelector("#aurea-head-group");
  const stage = document.querySelector(".stage");
  const portrait = document.querySelector(".portrait");
  const statusLabel = document.querySelector("#status-label");
  const stateButtons = [...document.querySelectorAll("[data-state-button]")];
  const visemeButtons = [...document.querySelectorAll("[data-viseme]")];
  const gazeButtons = [...document.querySelectorAll("[data-gaze]")];
  const allButtons = [...document.querySelectorAll("button")];
  const blinkButton = document.querySelector("#blink");
  const headMotionButton = document.querySelector("#head-motion");
  const demoButton = document.querySelector("#auto-demo");
  const labels = { idle: "RIPOSO", listen: "ASCOLTO", speak: "PARLATO" };

  const assets = new Map();
  const eyeClips = new Map();
  const timers = {
    blink: 0,
    doubleBlink: 0,
    saccade: 0,
    saccadeReturn: 0,
    headMotion: 0,
    headReturn: 0,
    speechLoop: 0,
    touchReturn: 0,
    visemeTrack: [],
    demo: [],
  };

  const italianTrack = [
    { at: 0, viseme: "MBP" },
    { at: 70, viseme: "U" },
    { at: 150, viseme: "O" },
    { at: 270, viseme: "CNS" },
    { at: 340, viseme: "CH" },
    { at: 430, viseme: "O" },
    { at: 540, viseme: "CNS" },
    { at: 620, viseme: "O" },
    { at: 810, viseme: "CH" },
    { at: 890, viseme: "U" },
    { at: 1040, viseme: "SZ" },
    { at: 1130, viseme: "E" },
    { at: 1300, viseme: "MBP" },
    { at: 1380, viseme: "E" },
    { at: 1540, viseme: "REST" },
    { at: 1770, viseme: "SZ" },
    { at: 1850, viseme: "O" },
    { at: 1990, viseme: "CNS" },
    { at: 2070, viseme: "O" },
    { at: 2250, viseme: "REST" },
    { at: 2470, viseme: "A" },
    { at: 2550, viseme: "U" },
    { at: 2650, viseme: "CNS" },
    { at: 2740, viseme: "E" },
    { at: 2830, viseme: "A" },
    { at: 3070, viseme: "REST" },
  ];

  let ready = false;
  let intersecting = true;
  let active = !document.hidden;
  let lowPower = false;
  let animationFrame = 0;
  let lastDrawAt = 0;
  let blinkStartedAt = -1;
  let currentViseme = "REST";
  let mouthTransition = null;
  let demoRunning = false;
  let dirtyEyes = true;
  let dirtyMouth = true;
  let dirtyHead = true;

  const reducedMotionQuery = window.matchMedia?.("(prefers-reduced-motion: reduce)");
  let reducedMotion = Boolean(reducedMotionQuery?.matches);
  const blinkTotalMs =
    config.motion.blink.closeMs +
    config.motion.blink.holdMs +
    config.motion.blink.openMs;

  const metrics = {
    startedAt: performance.now(),
    drawCount: 0,
    eyeDraws: 0,
    mouthDraws: 0,
    headUpdates: 0,
  };

  function randomBetween(min, max) {
    return min + Math.random() * (max - min);
  }

  function clearTimeoutId(name) {
    if (timers[name]) window.clearTimeout(timers[name]);
    timers[name] = 0;
  }

  function clearTimerList(name) {
    for (const timer of timers[name]) window.clearTimeout(timer);
    timers[name] = [];
  }

  function parentTargetOrigin() {
    return window.location.protocol === "file:" ? "*" : window.location.origin;
  }

  function postParent(type, detail) {
    if (window.parent === window) return;
    window.parent.postMessage({ type, detail }, parentTargetOrigin());
  }

  function emitChange(reason) {
    const detail = { ...core.snapshot(), reason };
    window.dispatchEvent(new CustomEvent("aurea-changed", { detail }));
    postParent("aurea-changed", detail);
  }

  function loadImage(src) {
    return new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = async () => {
        try {
          if (image.decode) await image.decode();
        } catch {
          // onload è sufficiente anche nei WebView Android meno recenti.
        }
        resolve(image);
      };
      image.onerror = () => reject(new Error(`Impossibile caricare ${src}`));
      image.src = src;
    });
  }

  function assetPaths() {
    const paths = new Set();
    for (const side of ["left", "right"]) {
      const eye = config.eyes[side];
      paths.add(eye.iris.file);
      for (const pose of ["quarter", "half", "threeQuarter", "closed"]) {
        paths.add(eye[pose].file);
      }
    }
    for (const mouth of Object.values(config.mouth)) {
      if (mouth) paths.add(mouth.file);
    }
    return [...paths];
  }

  async function loadAssets() {
    const loadDomImage = (image, src) => new Promise((resolve, reject) => {
      image.onload = async () => {
        try {
          if (image.decode) await image.decode();
        } catch {
          // L'immagine è già disponibile dopo onload.
        }
        resolve();
      };
      image.onerror = () => reject(new Error(`Impossibile caricare ${src}`));
      image.src = src;
    });

    const [loaded] = await Promise.all([
      Promise.all(assetPaths().map(
        async assetPath => [assetPath, await loadImage(assetPath)],
      )),
      loadDomImage(backgroundImage, config.layers.background),
      loadDomImage(headImage, config.layers.head),
      loadDomImage(hairImage, config.layers.hair),
      loadDomImage(torsoImage, config.layers.torso),
    ]);
    for (const [assetPath, image] of loaded) assets.set(assetPath, image);
    for (const side of ["left", "right"]) {
      eyeClips.set(side, new Path2D(config.eyes[side].clipPath));
    }
  }

  function drawImage(spec, dx = 0, dy = 0) {
    const image = assets.get(spec.file);
    if (!image) return;
    ctx.drawImage(image, spec.x + dx, spec.y + dy, spec.width, spec.height);
  }

  function clearRegion(region) {
    ctx.clearRect(region.x, region.y, region.width, region.height);
  }

  function blinkPoseForSide(now, side) {
    if (blinkStartedAt < 0) return "open";
    const offset = side === "right" ? config.motion.blink.rightEyeOffsetMs : 0;
    return AureaRigCore.blinkPose(now - blinkStartedAt - offset, config.motion.blink);
  }

  function drawEyes(now) {
    clearRegion(config.regions.eyes);
    for (const side of ["left", "right"]) {
      const eye = config.eyes[side];
      const pose = blinkPoseForSide(now, side);
      if (pose === "open") {
        ctx.save();
        ctx.clip(eyeClips.get(side));
        drawImage(
          eye.iris,
          core.gaze.x * eye.gazeLimit.x,
          core.gaze.y * eye.gazeLimit.y,
        );
        ctx.restore();
      } else {
        drawImage(eye[pose]);
      }
    }
    metrics.eyeDraws += 1;

    if (
      blinkStartedAt >= 0 &&
      now - blinkStartedAt >= blinkTotalMs + config.motion.blink.rightEyeOffsetMs
    ) {
      blinkStartedAt = -1;
    }
  }

  function visibleMouthViseme(now) {
    if (!mouthTransition) return currentViseme;
    const progress = (now - mouthTransition.startedAt) / mouthTransition.durationMs;
    if (progress >= 1) {
      currentViseme = mouthTransition.to;
      mouthTransition = null;
      return currentViseme;
    }
    if (progress < .36) return mouthTransition.from;
    if (progress < .66) return mouthTransition.bridge;
    return mouthTransition.to;
  }

  function drawMouth(now) {
    clearRegion(config.regions.mouth);
    const viseme = visibleMouthViseme(now);
    const spec = config.mouth[viseme];
    if (spec) drawImage(spec);
    metrics.mouthDraws += 1;
  }

  function applyHeadPose() {
    const motion = config.motion.headMotion;
    const x = core.head.x * motion.maxXpx;
    const y = core.head.y * motion.maxYpx;
    const roll = core.head.roll * motion.maxRollDeg;
    headGroup.style.transform =
      `translate3d(${x.toFixed(3)}px, ${y.toFixed(3)}px, 0) rotate(${roll.toFixed(4)}deg)`;
    metrics.headUpdates += 1;
  }

  function animationTargetFps() {
    if (lowPower) return config.motion.lowPowerFps;
    if (blinkStartedAt >= 0 || mouthTransition || core.headMoving()) {
      return config.motion.activeFps;
    }
    return config.motion.idleFps;
  }

  function requestRender({ eyes = false, mouth = false, head = false } = {}) {
    if (eyes) dirtyEyes = true;
    if (mouth) dirtyMouth = true;
    if (head) dirtyHead = true;
    if (!ready || !active || animationFrame) return;
    animationFrame = requestAnimationFrame(renderFrame);
  }

  function renderFrame(now) {
    animationFrame = 0;
    if (!active || !ready) return;

    const minFrameMs = 1000 / animationTargetFps();
    if (lastDrawAt && now - lastDrawAt < minFrameMs - .75) {
      animationFrame = requestAnimationFrame(renderFrame);
      return;
    }

    const deltaMs = lastDrawAt ? Math.min(80, now - lastDrawAt) : minFrameMs;
    lastDrawAt = now;
    const gazeWasMoving = core.gazeMoving();
    if (gazeWasMoving) core.stepGaze(deltaMs, config.motion.gazeEaseMs);
    const headWasMoving = core.headMoving();
    if (headWasMoving) core.stepHead(deltaMs, config.motion.headMotion.easeMs);

    const eyesAnimating = blinkStartedAt >= 0 || gazeWasMoving || core.gazeMoving();
    const mouthAnimating = Boolean(mouthTransition);
    if (dirtyEyes || eyesAnimating) drawEyes(now);
    if (dirtyMouth || mouthAnimating) drawMouth(now);
    if (dirtyHead || headWasMoving || core.headMoving()) applyHeadPose();
    dirtyEyes = false;
    dirtyMouth = false;
    dirtyHead = false;
    metrics.drawCount += 1;

    if (
      blinkStartedAt >= 0 ||
      mouthTransition ||
      core.gazeMoving() ||
      core.headMoving()
    ) {
      requestRender();
    }
  }

  function syncUi() {
    stage.dataset.state = core.state;
    statusLabel.textContent = labels[core.state];
    stateButtons.forEach(button => {
      button.classList.toggle("active", button.dataset.stateButton === core.state);
    });
    visemeButtons.forEach(button => {
      button.classList.toggle("active", button.dataset.viseme === core.viseme);
    });
  }

  function transitionViseme(next, durationMs = config.motion.visemeEaseMs) {
    const normalized = core.setViseme(next);
    const now = performance.now();
    const from = visibleMouthViseme(now);
    if (from === normalized) {
      currentViseme = normalized;
      mouthTransition = null;
      syncUi();
      requestRender({ mouth: true });
      return normalized;
    }

    mouthTransition = {
      from,
      bridge: AureaRigCore.bridgeViseme(from, normalized),
      to: normalized,
      startedAt: now,
      durationMs: Math.max(54, Number(durationMs) || config.motion.visemeEaseMs),
    };
    syncUi();
    requestRender({ mouth: true });
    emitChange("viseme");
    return normalized;
  }

  function stateGaze(state) {
    if (state === "listen") return { x: 0, y: -.12 };
    return { x: 0, y: 0 };
  }

  function stateHead(state) {
    if (state === "listen") return { x: .05, y: -.12, roll: -.16 };
    return { x: 0, y: 0, roll: 0 };
  }

  function setGazeInternal(x, y, reason = "gaze") {
    const gaze = core.setGaze(x, y);
    requestRender({ eyes: true });
    emitChange(reason);
    return gaze;
  }

  function setHeadInternal(x, y, roll, reason = "head") {
    const pose = core.setHeadPose(x, y, roll);
    requestRender({ head: true });
    emitChange(reason);
    return pose;
  }

  function scheduleHeadMotion() {
    clearTimeoutId("headMotion");
    if (
      !active ||
      !ready ||
      reducedMotion ||
      demoRunning ||
      !config.motion.headMotion.enabled
    ) return;
    const range = lowPower
      ? config.motion.headMotion.lowPowerIntervalMs
      : config.motion.headMotion.idleIntervalMs;
    timers.headMotion = window.setTimeout(() => {
      const center = stateHead(core.state);
      const amplitude = core.state === "listen" ? .14 : core.state === "speak" ? .24 : .19;
      setHeadInternal(
        center.x + randomBetween(-amplitude * .45, amplitude * .45),
        center.y + randomBetween(-amplitude * .32, amplitude * .18),
        center.roll + randomBetween(-amplitude, amplitude),
        "head-idle",
      );
      clearTimeoutId("headReturn");
      timers.headReturn = window.setTimeout(() => {
        setHeadInternal(center.x, center.y, center.roll, "head-return");
      }, randomBetween(980, 1650));
      scheduleHeadMotion();
    }, randomBetween(...range));
  }

  function triggerHeadMotion() {
    if (!active || !config.motion.headMotion.enabled) return false;
    clearTimeoutId("headMotion");
    clearTimeoutId("headReturn");
    const center = stateHead(core.state);
    const direction = Math.random() < .5 ? -1 : 1;
    setHeadInternal(
      center.x + .20 * direction,
      center.y - .24,
      center.roll + .90 * direction,
      "head-test",
    );
    timers.headReturn = window.setTimeout(() => {
      setHeadInternal(center.x, center.y, center.roll, "head-test-return");
      scheduleHeadMotion();
    }, 1250);
    return true;
  }

  function scheduleBlink() {
    clearTimeoutId("blink");
    if (!active || !ready || reducedMotion) return;
    const intervals = {
      idle: lowPower ? [5200, 9600] : [3400, 7600],
      listen: [5200, 9000],
      speak: [3400, 6500],
    };
    const [min, max] = intervals[core.state];
    timers.blink = window.setTimeout(() => {
      triggerBlink(true);
      scheduleBlink();
    }, randomBetween(min, max));
  }

  function triggerBlink(allowDouble = false) {
    if (!active || blinkStartedAt >= 0) return false;
    blinkStartedAt = performance.now();
    requestRender({ eyes: true });
    emitChange("blink");

    const chance = core.state === "idle" ? .05 : core.state === "listen" ? .015 : .035;
    if (allowDouble && !lowPower && Math.random() < chance) {
      clearTimeoutId("doubleBlink");
      timers.doubleBlink = window.setTimeout(
        () => triggerBlink(false),
        blinkTotalMs + randomBetween(92, 132),
      );
    }
    return true;
  }

  function scheduleSaccade() {
    clearTimeoutId("saccade");
    if (!active || !ready || reducedMotion) return;
    const delays = lowPower
      ? [6200, 10800]
      : core.state === "listen"
        ? [4300, 8200]
        : [3200, 7000];
    timers.saccade = window.setTimeout(() => {
      const center = stateGaze(core.state);
      const amplitude = core.state === "idle" ? .22 : .12;
      setGazeInternal(
        center.x + randomBetween(-amplitude, amplitude),
        center.y + randomBetween(-amplitude * .48, amplitude * .48),
        "saccade",
      );
      clearTimeoutId("saccadeReturn");
      timers.saccadeReturn = window.setTimeout(
        () => setGazeInternal(center.x, center.y, "saccade-return"),
        randomBetween(180, 330),
      );
      scheduleSaccade();
    }, randomBetween(...delays));
  }

  function clearSpeechTimers(closeMouth = false) {
    clearTimeoutId("speechLoop");
    clearTimerList("visemeTrack");
    if (closeMouth) transitionViseme("REST", 72);
  }

  function cancelDemo() {
    if (!demoRunning && !timers.demo.length) return;
    clearTimerList("demo");
    demoRunning = false;
    demoButton.textContent = "Avvia demo";
    demoButton.classList.remove("active");
  }

  function setStateInternal(next, { automaticSpeech = true, internal = false } = {}) {
    if (!internal) cancelDemo();
    clearSpeechTimers(false);
    core.setState(next);
    if (next !== "speak") transitionViseme("REST", 72);
    const gaze = stateGaze(next);
    setGazeInternal(gaze.x, gaze.y, "state-gaze");
    const head = stateHead(next);
    setHeadInternal(head.x, head.y, head.roll, "state-head");
    syncUi();
    scheduleBlink();
    scheduleSaccade();
    scheduleHeadMotion();
    if (next === "speak" && automaticSpeech) {
      playTrackInternal(italianTrack, { loop: true });
    }
    emitChange("state");
    return core.snapshot();
  }

  function setVisemeInternal(next, durationMs, { internal = false } = {}) {
    if (!internal) cancelDemo();
    clearSpeechTimers(false);
    if (core.state !== "speak") core.setState("speak");
    transitionViseme(next, durationMs);
    syncUi();
    return core.snapshot();
  }

  function playTrackInternal(track, { loop = false, internal = true } = {}) {
    const normalized = AureaRigCore.validateTrack(track);
    if (!internal) cancelDemo();
    clearSpeechTimers(false);
    core.setState("speak");
    syncUi();

    for (const cue of normalized) {
      timers.visemeTrack.push(
        window.setTimeout(
          () => transitionViseme(cue.viseme, cue.transitionMs),
          cue.at,
        ),
      );
    }

    const lastCue = normalized[normalized.length - 1];
    const endAt = lastCue.at + (lastCue.viseme === "REST" ? 0 : 180);
    if (lastCue.viseme !== "REST") {
      timers.visemeTrack.push(
        window.setTimeout(() => transitionViseme("REST", 70), endAt),
      );
    }
    if (loop) {
      timers.speechLoop = window.setTimeout(
        () => playTrackInternal(normalized, { loop: true }),
        endAt + 430,
      );
    }
    emitChange("viseme-track");
    return normalized;
  }

  function stopDemo() {
    cancelDemo();
    clearSpeechTimers(false);
    setStateInternal("idle", { automaticSpeech: false, internal: true });
  }

  function scheduleDemo(delay, action) {
    timers.demo.push(window.setTimeout(action, delay));
  }

  function playDemo() {
    stopDemo();
    demoRunning = true;
    demoButton.textContent = "Ferma demo";
    demoButton.classList.add("active");
    setStateInternal("idle", { automaticSpeech: false, internal: true });
    scheduleDemo(650, () => setGazeInternal(-.38, -.06, "demo"));
    scheduleDemo(820, () => setHeadInternal(-.04, -.10, -.44, "demo-head"));
    scheduleDemo(1350, () => setGazeInternal(.34, .04, "demo"));
    scheduleDemo(1540, () => setHeadInternal(.05, -.04, .38, "demo-head"));
    scheduleDemo(2050, () => {
      setGazeInternal(0, 0, "demo");
      setHeadInternal(0, 0, 0, "demo-head");
      triggerBlink();
    });
    scheduleDemo(3000, () => {
      setStateInternal("listen", { automaticSpeech: false, internal: true });
    });
    scheduleDemo(4300, () => {
      playTrackInternal(italianTrack, { loop: false, internal: true });
    });
    scheduleDemo(7900, stopDemo);
  }

  function stopRuntimeTimers() {
    clearTimeoutId("blink");
    clearTimeoutId("doubleBlink");
    clearTimeoutId("saccade");
    clearTimeoutId("saccadeReturn");
    clearTimeoutId("headMotion");
    clearTimeoutId("headReturn");
    clearTimeoutId("speechLoop");
    clearTimeoutId("touchReturn");
    clearTimerList("visemeTrack");
    cancelDemo();
  }

  function refreshActivity() {
    const nextActive = !document.hidden && intersecting;
    if (active === nextActive) return;
    active = nextActive;
    if (!active) {
      if (animationFrame) cancelAnimationFrame(animationFrame);
      animationFrame = 0;
      stopRuntimeTimers();
      return;
    }
    lastDrawAt = 0;
    dirtyEyes = true;
    dirtyMouth = true;
    dirtyHead = true;
    requestRender({ head: true });
    scheduleBlink();
    scheduleSaccade();
    scheduleHeadMotion();
    if (core.state === "speak") {
      playTrackInternal(italianTrack, { loop: true });
    }
  }

  function setLowPower(enabled) {
    lowPower = Boolean(enabled);
    document.body.classList.toggle("low-power", lowPower);
    scheduleBlink();
    scheduleSaccade();
    scheduleHeadMotion();
    return lowPower;
  }

  function gazeFromPointer(event) {
    const rect = canvas.getBoundingClientRect();
    return {
      x: (((event.clientX - rect.left) / rect.width) * 2 - 1) * .48,
      y: (((event.clientY - rect.top) / rect.height) * 2 - 1) * .32,
    };
  }

  function handleCommand(data) {
    if (!data || data.type !== "aurea-command") return;
    try {
      if (data.command === "state") setStateInternal(data.value);
      if (data.command === "viseme") setVisemeInternal(data.value, data.transitionMs);
      if (data.command === "gaze") {
        cancelDemo();
        setGazeInternal(data.x, data.y, "message");
      }
      if (data.command === "blink") triggerBlink();
      if (data.command === "head") {
        setHeadInternal(data.x, data.y, data.roll, "message-head");
      }
      if (data.command === "headMotion") triggerHeadMotion();
      if (data.command === "track") playTrackInternal(data.value, { internal: false });
      if (data.command === "lowPower") setLowPower(data.value);
    } catch (error) {
      const detail = { message: error.message, command: data.command };
      window.dispatchEvent(new CustomEvent("aurea-error", { detail }));
      postParent("aurea-error", detail);
    }
  }

  stateButtons.forEach(button => {
    button.addEventListener("click", () => setStateInternal(button.dataset.stateButton));
  });
  visemeButtons.forEach(button => {
    button.addEventListener("click", () => setVisemeInternal(button.dataset.viseme));
  });
  gazeButtons.forEach(button => {
    button.addEventListener("click", () => {
      cancelDemo();
      const [x, y] = button.dataset.gaze.split(",").map(Number);
      setGazeInternal(x, y);
    });
  });
  blinkButton.addEventListener("click", () => {
    cancelDemo();
    triggerBlink();
  });
  headMotionButton.addEventListener("click", () => {
    cancelDemo();
    triggerHeadMotion();
  });
  demoButton.addEventListener("click", () => {
    if (demoRunning) stopDemo();
    else playDemo();
  });

  canvas.addEventListener("pointermove", event => {
    if (event.pointerType !== "mouse") return;
    const gaze = gazeFromPointer(event);
    setGazeInternal(gaze.x, gaze.y, "pointer");
  });
  canvas.addEventListener("pointerleave", event => {
    if (event.pointerType !== "mouse") return;
    const center = stateGaze(core.state);
    setGazeInternal(center.x, center.y, "pointer-leave");
  });
  canvas.addEventListener("pointerdown", event => {
    cancelDemo();
    const gaze = gazeFromPointer(event);
    setGazeInternal(gaze.x, gaze.y, "touch");
    clearTimeoutId("touchReturn");
    timers.touchReturn = window.setTimeout(() => {
      const center = stateGaze(core.state);
      setGazeInternal(center.x, center.y, "touch-return");
    }, 680);
  });

  document.addEventListener("visibilitychange", refreshActivity);
  if ("IntersectionObserver" in window) {
    const observer = new IntersectionObserver(entries => {
      intersecting = entries.some(entry => entry.isIntersecting);
      refreshActivity();
    }, { threshold: .02 });
    observer.observe(portrait);
  }

  reducedMotionQuery?.addEventListener?.("change", event => {
    reducedMotion = event.matches;
    scheduleBlink();
    scheduleSaccade();
    scheduleHeadMotion();
    if (reducedMotion) setHeadInternal(0, 0, 0, "reduced-motion");
  });

  window.addEventListener("message", event => {
    const sameOrigin = event.origin === window.location.origin;
    const localPreview = window.location.protocol === "file:" && event.origin === "null";
    if (!sameOrigin && !localPreview) return;
    handleCommand(event.data);
  });

  const params = new URLSearchParams(window.location.search);
  if (params.get("mode") === "avatar") document.body.classList.add("avatar-mode");
  setLowPower(params.get("lowPower") === "1");

  window.AUREA = Object.freeze({
    setState: next => setStateInternal(next),
    setGaze: (x, y) => {
      cancelDemo();
      return setGazeInternal(x, y);
    },
    setHeadPose: (x, y, roll) => {
      cancelDemo();
      clearTimeoutId("headMotion");
      clearTimeoutId("headReturn");
      return setHeadInternal(x, y, roll);
    },
    setViseme: (next, durationMs) => setVisemeInternal(next, durationMs),
    playVisemeTrack: track => playTrackInternal(track, { loop: false, internal: false }),
    blink: () => triggerBlink(),
    headMotion: () => triggerHeadMotion(),
    playDemo,
    stopDemo,
    setLowPower,
    getState: () => core.snapshot(),
    getMetrics: () => ({
      ...metrics,
      elapsedMs: performance.now() - metrics.startedAt,
      lowPower,
      demoRunning,
      active,
      targetFps: animationTargetFps(),
    }),
    resetMetrics: () => {
      metrics.startedAt = performance.now();
      metrics.drawCount = 0;
      metrics.eyeDraws = 0;
      metrics.mouthDraws = 0;
      metrics.headUpdates = 0;
    },
    states: AureaRigCore.STATES,
    visemes: AureaRigCore.VISEMES,
  });

  loadAssets()
    .then(() => {
      ready = true;
      stage.setAttribute("aria-busy", "false");
      allButtons.forEach(button => { button.disabled = false; });
      statusLabel.textContent = labels.idle;
      syncUi();
      requestRender({ eyes: true, mouth: true, head: true });
      scheduleBlink();
      scheduleSaccade();
      scheduleHeadMotion();
      const detail = core.snapshot();
      window.dispatchEvent(new CustomEvent("aurea-ready", { detail }));
      postParent("aurea-ready", detail);
    })
    .catch(error => {
      statusLabel.textContent = "ERRORE FILE";
      console.error(error);
    });
})();
