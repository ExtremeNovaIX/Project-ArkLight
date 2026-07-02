#include "tts/TtsAudioCodecSupport.h"

#include <QtEndian>
#include <QtMath>

#include <cmath>
#include <cstring>

namespace ttsaudio {

QString normalizeBackendBaseUrl(const QString &value) {
    QString normalized = value.trimmed();
    while (normalized.endsWith(QLatin1Char('/'))) {
        normalized.chop(1);
    }
    return normalized;
}

quint16 readLe16(const QByteArray &data, int offset) {
    return qFromLittleEndian<quint16>(reinterpret_cast<const uchar *>(data.constData() + offset));
}

quint32 readLe32(const QByteArray &data, int offset) {
    return qFromLittleEndian<quint32>(reinterpret_cast<const uchar *>(data.constData() + offset));
}

qint32 signExtend24(quint32 value) {
    if ((value & 0x00800000U) != 0U) {
        value |= 0xff000000U;
    }
    return static_cast<qint32>(value);
}

float pcmSampleToFloat(const char *data, int bitsPerSample, bool littleEndian) {
    if (bitsPerSample == 8) {
        return (static_cast<int>(*reinterpret_cast<const quint8 *>(data)) - 128) / 128.0f;
    }
    if (bitsPerSample == 16) {
        const auto *bytes = reinterpret_cast<const uchar *>(data);
        const qint16 value = littleEndian ? qFromLittleEndian<qint16>(bytes) : qFromBigEndian<qint16>(bytes);
        return value / 32768.0f;
    }
    if (bitsPerSample == 24) {
        const auto *bytes = reinterpret_cast<const uchar *>(data);
        quint32 raw = littleEndian
            ? (static_cast<quint32>(bytes[0]) | (static_cast<quint32>(bytes[1]) << 8) | (static_cast<quint32>(bytes[2]) << 16))
            : (static_cast<quint32>(bytes[2]) | (static_cast<quint32>(bytes[1]) << 8) | (static_cast<quint32>(bytes[0]) << 16));
        return signExtend24(raw) / 8388608.0f;
    }
    if (bitsPerSample >= 32) {
        const auto *bytes = reinterpret_cast<const uchar *>(data);
        const qint32 value = littleEndian ? qFromLittleEndian<qint32>(bytes) : qFromBigEndian<qint32>(bytes);
        return value / 2147483648.0f;
    }
    return 0.0f;
}

float floatSample32(const char *data) {
    float value = 0.0f;
    std::memcpy(&value, data, sizeof(float));
    return qBound(-1.0f, value, 1.0f);
}

qint16 floatToPcm16(float value) {
    return static_cast<qint16>(std::lround(qBound(-1.0f, value, 1.0f) * 32767.0f));
}

qint32 floatToPcm32(float value) {
    return static_cast<qint32>(std::lround(qBound(-1.0f, value, 1.0f) * 2147483647.0f));
}

int bytesForSampleFormat(QAudioFormat::SampleFormat format) {
    switch (format) {
    case QAudioFormat::UInt8:
        return 1;
    case QAudioFormat::Int16:
        return 2;
    case QAudioFormat::Int32:
    case QAudioFormat::Float:
        return 4;
    default:
        return 2;
    }
}

int bytesPerFrame(const QAudioFormat &format) {
    return qMax(1, bytesForSampleFormat(format.sampleFormat()) * qMax(1, format.channelCount()));
}

int mediaTypeIntParameter(const QString &mediaType, const QString &name, int fallback) {
    const QStringList parts = mediaType.toLower().split(QLatin1Char(';'));
    const QString prefix = name.toLower() + QLatin1Char('=');
    for (QString part : parts) {
        part = part.trimmed();
        if (!part.startsWith(prefix)) {
            continue;
        }
        bool ok = false;
        const int value = part.mid(prefix.size()).toInt(&ok);
        if (ok && value > 0) {
            return value;
        }
    }
    return fallback;
}

bool mediaTypeHasToken(const QString &mediaType, const QString &token) {
    return mediaType.toLower().contains(token.toLower());
}
} // namespace ttsaudio