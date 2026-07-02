#pragma once

#include <QAudioFormat>
#include <QByteArray>
#include <QString>

namespace ttsaudio {

constexpr int kDrainIntervalMs = 12;
constexpr int kStartupBufferMs = 180;
constexpr int kSinkBufferMs = 500;
constexpr int kFadeMs = 6;
constexpr int kInterChunkGapMinMs = 500;
constexpr int kInterChunkGapMaxMs = 1000;

QString normalizeBackendBaseUrl(const QString &value);
quint16 readLe16(const QByteArray &data, int offset);
quint32 readLe32(const QByteArray &data, int offset);
float pcmSampleToFloat(const char *data, int bitsPerSample, bool littleEndian);
float floatSample32(const char *data);
qint16 floatToPcm16(float value);
qint32 floatToPcm32(float value);
int bytesForSampleFormat(QAudioFormat::SampleFormat format);
int bytesPerFrame(const QAudioFormat &format);
int mediaTypeIntParameter(const QString &mediaType, const QString &name, int fallback);
bool mediaTypeHasToken(const QString &mediaType, const QString &token);

} // namespace ttsaudio