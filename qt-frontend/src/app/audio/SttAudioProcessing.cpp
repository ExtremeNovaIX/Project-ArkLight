#include "audio/SttAudioProcessing.h"

#include <QtEndian>
#include <QtMath>

#include <cmath>
#include <cstring>

namespace sttaudio {
constexpr double Pi = 3.14159265358979323846;
constexpr double VoiceBandLowHz = 80.0;
constexpr double VoiceBandHighHz = 3800.0;

QString normalizeBackendBaseUrl(const QString &value) {
    QString normalized = value.trimmed();
    while (normalized.endsWith(QLatin1Char('/'))) {
        normalized.chop(1);
    }
    return normalized;
}

int bytesPerSample(QAudioFormat::SampleFormat format) {
    switch (format) {
    case QAudioFormat::UInt8:
        return 1;
    case QAudioFormat::Int16:
        return 2;
    case QAudioFormat::Int32:
    case QAudioFormat::Float:
        return 4;
    default:
        return 0;
    }
}

float sampleAsFloat(const char *data, QAudioFormat::SampleFormat format) {
    switch (format) {
    case QAudioFormat::UInt8:
        return (static_cast<int>(*reinterpret_cast<const quint8 *>(data)) - 128) / 128.0f;
    case QAudioFormat::Int16:
        return qFromLittleEndian<qint16>(reinterpret_cast<const uchar *>(data)) / 32768.0f;
    case QAudioFormat::Int32:
        return qFromLittleEndian<qint32>(reinterpret_cast<const uchar *>(data)) / 2147483648.0f;
    case QAudioFormat::Float: {
        float value = 0.0f;
        static_assert(sizeof(float) == 4);
        std::memcpy(&value, data, sizeof(float));
        return qBound(-1.0f, value, 1.0f);
    }
    default:
        return 0.0f;
    }
}

qint16 floatToPcm16(float value) {
    const float clamped = qBound(-1.0f, value, 1.0f);
    return static_cast<qint16>(std::lround(clamped * 32767.0f));
}

qreal pcm16Level(const QByteArray &pcm) {
    const int frames = pcm.size() / TargetBytesPerSample;
    if (frames <= 0) {
        return 0.0;
    }
    const auto *data = reinterpret_cast<const uchar *>(pcm.constData());
    double energy = 0.0;
    for (int index = 0; index < frames; ++index) {
        const qint16 sample = qFromLittleEndian<qint16>(data + index * TargetBytesPerSample);
        const double value = sample / 32768.0;
        energy += value * value;
    }
    return qBound<qreal>(0.0, std::sqrt(energy / frames) * 4.0, 1.0);
}

float linearSampleAt(const QVector<float> &samples, double position) {
    if (samples.isEmpty()) {
        return 0.0f;
    }
    const int last = samples.size() - 1;
    if (position <= 0.0) {
        return samples.first();
    }
    if (position >= last) {
        return samples.last();
    }
    const int left = static_cast<int>(std::floor(position));
    const int right = qMin(left + 1, last);
    const float fraction = static_cast<float>(position - left);
    return samples.at(left) + (samples.at(right) - samples.at(left)) * fraction;
}

float averagedSampleWindow(const QVector<float> &samples, double start, double end) {
    if (samples.isEmpty()) {
        return 0.0f;
    }
    if (end <= start) {
        return linearSampleAt(samples, start);
    }
    const int first = qMax(0, static_cast<int>(std::floor(start)));
    const int last = qMin(samples.size() - 1, static_cast<int>(std::ceil(end)) - 1);
    double weighted = 0.0;
    double weight = 0.0;
    for (int index = first; index <= last; ++index) {
        const double left = qMax(start, static_cast<double>(index));
        const double right = qMin(end, static_cast<double>(index + 1));
        const double sampleWeight = qMax(0.0, right - left);
        weighted += static_cast<double>(samples.at(index)) * sampleWeight;
        weight += sampleWeight;
    }
    if (weight <= 0.0) {
        return linearSampleAt(samples, start);
    }
    return static_cast<float>(weighted / weight);
}

QByteArray resampleMonoToPcm16k(const QVector<float> &samples, int sourceRate) {
    if (samples.isEmpty() || sourceRate <= 0) {
        return {};
    }
    const int targetFrames = qMax(
        1,
        static_cast<int>(std::llround(samples.size() * (TargetSampleRate / static_cast<double>(sourceRate)))));
    const double sourceFramesPerTarget = sourceRate / static_cast<double>(TargetSampleRate);
    QByteArray output;
    output.resize(targetFrames * TargetBytesPerSample);
    uchar *out = reinterpret_cast<uchar *>(output.data());
    for (int index = 0; index < targetFrames; ++index) {
        const double start = index * sourceFramesPerTarget;
        const double end = (index + 1) * sourceFramesPerTarget;
        const float value = sourceFramesPerTarget > 1.0
            ? averagedSampleWindow(samples, start, end)
            : linearSampleAt(samples, start);
        qToLittleEndian<qint16>(floatToPcm16(value), out + index * TargetBytesPerSample);
    }
    return output;
}

void filterVoiceBandPcm16InPlace(QByteArray *pcm,
                                 double *highPassPreviousInput,
                                 double *highPassPreviousOutput,
                                 double *lowPassPreviousOutput) {
    if (pcm == nullptr
        || highPassPreviousInput == nullptr
        || highPassPreviousOutput == nullptr
        || lowPassPreviousOutput == nullptr
        || pcm->isEmpty()) {
        return;
    }
    const int frames = pcm->size() / TargetBytesPerSample;
    if (frames <= 0) {
        return;
    }

    const double highPassRc = 1.0 / (2.0 * Pi * VoiceBandLowHz);
    const double lowPassRc = 1.0 / (2.0 * Pi * VoiceBandHighHz);
    const double dt = 1.0 / TargetSampleRate;
    const double highPassAlpha = highPassRc / (highPassRc + dt);
    const double lowPassAlpha = dt / (lowPassRc + dt);

    auto *data = reinterpret_cast<uchar *>(pcm->data());
    for (int index = 0; index < frames; ++index) {
        const qint16 sample = qFromLittleEndian<qint16>(data + index * TargetBytesPerSample);
        const double input = sample / 32768.0;
        const double highPassed = highPassAlpha * (*highPassPreviousOutput + input - *highPassPreviousInput);
        *highPassPreviousInput = input;
        *highPassPreviousOutput = highPassed;
        *lowPassPreviousOutput += lowPassAlpha * (highPassed - *lowPassPreviousOutput);
        qToLittleEndian<qint16>(floatToPcm16(static_cast<float>(*lowPassPreviousOutput)),
                                data + index * TargetBytesPerSample);
    }
}

QString csvEscape(QString value) {
    value.replace('"', "\"\"");
    return QStringLiteral("\"%1\"").arg(value);
}
} // namespace sttaudio