class TtsPcmPlayerProcessor extends AudioWorkletProcessor {
  constructor(options = {}) {
    super();
    this.queue = [];
    this.current = null;
    this.currentOffset = 0;
    this.bufferedFrames = 0;
    this.inputBuffer = new Float32Array(0);
    this.pendingTail = null;
    this.nextInputPosition = 0;
    this.lastFrameEnd = 0;
    this.stretchStarted = false;
    this.playing = false;
    this.gain = 0;
    this.fadeStep = 1 / Math.max(1, Math.round(sampleRate * 0.005));
    this.startThresholdFrames = Math.max(128, Math.round(sampleRate * 0.2));
    this.resumeThresholdFrames = Math.max(128, Math.round(sampleRate * 0.1));
    this.tempo = this.normalizeTempo(options.processorOptions?.tempo);
    this.segmentLength = Math.max(512, Math.round(sampleRate * 0.04));
    this.overlapLength = Math.max(128, Math.round(sampleRate * 0.01));
    this.outputHop = Math.max(1, this.segmentLength - this.overlapLength);
    this.inputHop = this.outputHop * this.tempo;
    this.searchRadius = Math.max(32, Math.round(sampleRate * 0.006));

    this.port.onmessage = (event) => {
      const message = event.data || {};
      if (message.type === 'push' && message.samples instanceof Float32Array && message.samples.length > 0) {
        this.pushInput(message.samples);
        return;
      }
      if (message.type === 'flush') {
        this.pumpTimeStretch(true, Number.MAX_SAFE_INTEGER);
        return;
      }
      if (message.type === 'reset') {
        this.reset();
      }
    };
  }

  normalizeTempo(value) {
    const tempo = Number(value);
    if (!Number.isFinite(tempo) || tempo <= 1) {
      return 1;
    }
    return Math.min(1.5, tempo);
  }

  reset() {
    this.queue = [];
    this.current = null;
    this.currentOffset = 0;
    this.bufferedFrames = 0;
    this.inputBuffer = new Float32Array(0);
    this.pendingTail = null;
    this.nextInputPosition = 0;
    this.lastFrameEnd = 0;
    this.stretchStarted = false;
    this.playing = false;
    this.gain = 0;
  }

  pushInput(samples) {
    if (this.tempo <= 1.001) {
      this.enqueueOutput(samples);
      return;
    }

    const input = new Float32Array(this.inputBuffer.length + samples.length);
    input.set(this.inputBuffer);
    input.set(samples, this.inputBuffer.length);
    this.inputBuffer = input;
    this.pumpTimeStretch(false, 64);
  }

  enqueueOutput(samples) {
    if (!samples || samples.length === 0) {
      return;
    }
    this.queue.push(samples);
    this.bufferedFrames += samples.length;
  }

  copySamples(samples) {
    const copy = new Float32Array(samples.length);
    copy.set(samples);
    return copy;
  }

  pumpTimeStretch(forceFlush, maxSegments) {
    if (this.tempo <= 1.001) {
      return;
    }

    let segments = 0;
    while (segments < maxSegments) {
      if (!this.stretchStarted) {
        if (this.inputBuffer.length >= this.segmentLength) {
          const frame = this.inputBuffer.subarray(0, this.segmentLength);
          this.emitFirstFrame(frame, 0);
          this.stretchStarted = true;
          this.nextInputPosition = this.inputHop;
          this.trimInputBuffer();
          segments += 1;
          continue;
        }
        if (forceFlush && this.inputBuffer.length > 0) {
          this.enqueueOutput(this.copySamples(this.inputBuffer));
          this.clearStretchState();
        }
        return;
      }

      const predicted = Math.round(this.nextInputPosition);
      const candidateMin = Math.max(0, predicted - this.searchRadius);
      const candidateMax = Math.min(this.inputBuffer.length - this.segmentLength, predicted + this.searchRadius);
      if (candidateMax < candidateMin) {
        if (forceFlush) {
          this.flushRemainder();
        }
        return;
      }

      const start = this.findBestOverlapPosition(candidateMin, candidateMax);
      const frame = this.inputBuffer.subarray(start, start + this.segmentLength);
      this.emitNextFrame(frame, start);
      this.nextInputPosition = start + this.inputHop;
      this.trimInputBuffer();
      segments += 1;
    }
  }

  clearStretchState() {
    this.inputBuffer = new Float32Array(0);
    this.pendingTail = null;
    this.nextInputPosition = 0;
    this.lastFrameEnd = 0;
    this.stretchStarted = false;
  }

  emitFirstFrame(frame, start) {
    const tailStart = Math.min(this.outputHop, frame.length);
    this.enqueueOutput(this.copySamples(frame.subarray(0, tailStart)));
    this.pendingTail = this.copySamples(frame.subarray(tailStart));
    this.lastFrameEnd = start + frame.length;
  }

  emitNextFrame(frame, start) {
    if (!this.pendingTail || this.pendingTail.length === 0) {
      this.emitFirstFrame(frame, start);
      return;
    }

    const overlap = Math.min(this.overlapLength, this.pendingTail.length, frame.length);
    const stableEnd = Math.min(this.outputHop, frame.length);
    const stableLength = Math.max(0, stableEnd - overlap);
    const output = new Float32Array(overlap + stableLength);

    for (let index = 0; index < overlap; index += 1) {
      const fadeIn = (index + 1) / (overlap + 1);
      output[index] = this.pendingTail[index] * (1 - fadeIn) + frame[index] * fadeIn;
    }
    if (stableLength > 0) {
      output.set(frame.subarray(overlap, stableEnd), overlap);
    }

    this.enqueueOutput(output);
    this.pendingTail = this.copySamples(frame.subarray(stableEnd));
    this.lastFrameEnd = start + frame.length;
  }

  flushRemainder() {
    if (this.pendingTail && this.pendingTail.length > 0) {
      this.enqueueOutput(this.pendingTail);
    }

    const remainderStart = Math.min(
      this.inputBuffer.length,
      Math.max(this.lastFrameEnd, Math.floor(this.nextInputPosition))
    );
    if (remainderStart < this.inputBuffer.length) {
      this.enqueueOutput(this.copySamples(this.inputBuffer.subarray(remainderStart)));
    }

    this.clearStretchState();
  }

  findBestOverlapPosition(candidateMin, candidateMax) {
    if (!this.pendingTail || this.pendingTail.length === 0) {
      return Math.round(this.nextInputPosition);
    }

    const overlap = Math.min(this.overlapLength, this.pendingTail.length);
    const sampleStride = 4;
    const candidateStride = 2;
    let bestStart = candidateMin;
    let bestScore = -Infinity;

    for (let candidate = candidateMin; candidate <= candidateMax; candidate += candidateStride) {
      const score = this.normalizedCorrelation(candidate, overlap, sampleStride);
      if (score > bestScore) {
        bestScore = score;
        bestStart = candidate;
      }
    }

    if (!Number.isFinite(bestScore)) {
      return Math.min(candidateMax, Math.max(candidateMin, Math.round(this.nextInputPosition)));
    }
    return bestStart;
  }

  normalizedCorrelation(candidate, overlap, stride) {
    let dot = 0;
    let leftEnergy = 0;
    let rightEnergy = 0;

    for (let index = 0; index < overlap; index += stride) {
      const left = this.pendingTail[index];
      const right = this.inputBuffer[candidate + index];
      dot += left * right;
      leftEnergy += left * left;
      rightEnergy += right * right;
    }

    if (leftEnergy <= 1e-9 || rightEnergy <= 1e-9) {
      return -Infinity;
    }
    return dot / Math.sqrt(leftEnergy * rightEnergy);
  }

  trimInputBuffer() {
    const keepFrom = Math.max(0, Math.floor(this.nextInputPosition - this.searchRadius - this.overlapLength));
    if (keepFrom < sampleRate) {
      return;
    }

    this.inputBuffer = this.copySamples(this.inputBuffer.subarray(keepFrom));
    this.nextInputPosition = Math.max(0, this.nextInputPosition - keepFrom);
    this.lastFrameEnd = Math.max(0, this.lastFrameEnd - keepFrom);
  }

  readSample() {
    if (!this.current || this.currentOffset >= this.current.length) {
      this.current = this.queue.shift() || null;
      this.currentOffset = 0;
    }
    if (!this.current) {
      return null;
    }
    const sample = this.current[this.currentOffset];
    this.currentOffset += 1;
    this.bufferedFrames = Math.max(0, this.bufferedFrames - 1);
    return sample;
  }

  process(_inputs, outputs) {
    const output = outputs[0];
    if (!output || output.length === 0) {
      return true;
    }

    const frames = output[0].length;
    if (this.bufferedFrames < this.startThresholdFrames * 2) {
      this.pumpTimeStretch(false, 8);
    }

    for (let frame = 0; frame < frames; frame += 1) {
      if (!this.playing && this.bufferedFrames >= this.startThresholdFrames) {
        this.playing = true;
      }

      const sample = this.playing ? this.readSample() : null;
      const hasSample = sample !== null;
      if (!hasSample) {
        this.playing = false;
        this.startThresholdFrames = this.resumeThresholdFrames;
      }

      const targetGain = hasSample ? 1 : 0;
      if (this.gain < targetGain) {
        this.gain = Math.min(targetGain, this.gain + this.fadeStep);
      } else if (this.gain > targetGain) {
        this.gain = Math.max(targetGain, this.gain - this.fadeStep);
      }

      const value = (hasSample ? sample : 0) * this.gain;
      for (let channel = 0; channel < output.length; channel += 1) {
        output[channel][frame] = value;
      }
    }

    return true;
  }
}

registerProcessor('tts-pcm-player', TtsPcmPlayerProcessor);
