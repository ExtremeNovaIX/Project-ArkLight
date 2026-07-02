#pragma once

#include <QAudioFormat>
#include <QByteArray>
#include <QString>
#include <QVector>

namespace sttaudio {

constexpr int TargetSampleRate = 16000;
constexpr int TargetChannelCount = 1;
constexpr int TargetBytesPerSample = 2;
constexpr int PcmFlushBytes = TargetSampleRate * TargetBytesPerSample / 10;

QString normalizeBackendBaseUrl(const QString &value);
int bytesPerSample(QAudioFormat::SampleFormat format);
float sampleAsFloat(const char *data, QAudioFormat::SampleFormat format);
QByteArray resampleMonoToPcm16k(const QVector<float> &samples, int sourceRate);
void filterVoiceBandPcm16InPlace(QByteArray *pcm,
                                 double *highPassPreviousInput,
                                 double *highPassPreviousOutput,
                                 double *lowPassPreviousOutput);
qreal pcm16Level(const QByteArray &pcm);
QString csvEscape(QString value);

} // namespace sttaudio