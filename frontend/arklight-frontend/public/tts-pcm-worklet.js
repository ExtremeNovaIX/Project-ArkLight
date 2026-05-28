class TtsPcmPlayerProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.queue = [];
    this.current = null;
    this.currentOffset = 0;
    this.bufferedFrames = 0;
    this.playing = false;
    this.gain = 0;
    this.fadeStep = 1 / Math.max(1, Math.round(sampleRate * 0.005));
    this.startThresholdFrames = Math.max(128, Math.round(sampleRate * 0.2));
    this.resumeThresholdFrames = Math.max(128, Math.round(sampleRate * 0.1));

    this.port.onmessage = (event) => {
      const message = event.data || {};
      if (message.type === 'push' && message.samples instanceof Float32Array && message.samples.length > 0) {
        this.queue.push(message.samples);
        this.bufferedFrames += message.samples.length;
        return;
      }
      if (message.type === 'reset') {
        this.reset();
      }
    };
  }

  reset() {
    this.queue = [];
    this.current = null;
    this.currentOffset = 0;
    this.bufferedFrames = 0;
    this.playing = false;
    this.gain = 0;
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
