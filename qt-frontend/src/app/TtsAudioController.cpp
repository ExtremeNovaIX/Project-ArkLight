#include "TtsAudioController.h"

#include "FrontendSettings.h"

#include <QAudioDevice>
#include <QAudioSink>
#include <QIODevice>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonValue>
#include <QMediaDevices>
#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QRandomGenerator>
#include <QUrl>
#include <QUrlQuery>
#include <QVariant>
#include <QtEndian>
#include <QtMath>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <utility>

namespace {

constexpr int kDrainIntervalMs = 12;
constexpr int kStartupBufferMs = 180;
constexpr int kSinkBufferMs = 500;
constexpr int kFadeMs = 6;
constexpr int kInterChunkGapMinMs = 500;
constexpr int kInterChunkGapMaxMs = 1000;

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

} // namespace

TtsAudioController::TtsAudioController(FrontendSettings *settings, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
    , m_network(new QNetworkAccessManager(this)) {
    m_reconnectTimer.setSingleShot(true);
    m_reconnectTimer.setInterval(2000);
    connect(&m_reconnectTimer, &QTimer::timeout, this, &TtsAudioController::refreshSubscription);

    m_drainTimer.setInterval(kDrainIntervalMs);
    connect(&m_drainTimer, &QTimer::timeout, this, &TtsAudioController::drainPlaybackQueue);

    if (m_settings != nullptr) {
        connect(m_settings, &FrontendSettings::settingsChanged, this, &TtsAudioController::refreshSubscription);
    }
    QTimer::singleShot(0, this, &TtsAudioController::refreshSubscription);
}

TtsAudioController::~TtsAudioController() {
    closeLiveAudio();
    resetPlayback();
}

bool TtsAudioController::connected() const {
    return m_connected;
}

QString TtsAudioController::statusText() const {
    return m_statusText;
}

void TtsAudioController::refreshSubscription() {
    const QString baseUrl = normalizedBaseUrl();
    const QString sessionId = currentSessionId();
    if (baseUrl.isEmpty() || sessionId.isEmpty()) {
        closeLiveAudio();
        setStatusText(QStringLiteral("TTS live audio is waiting for backend settings."));
        return;
    }
    if (m_reply != nullptr && m_liveBaseUrl == baseUrl && m_liveSessionId == sessionId) {
        return;
    }
    openLiveAudio(baseUrl, sessionId);
}

void TtsAudioController::openLiveAudio(const QString &baseUrl, const QString &sessionId) {
    closeLiveAudio();
    m_liveBaseUrl = baseUrl;
    m_liveSessionId = sessionId;
    m_sseBuffer.clear();
    m_pendingAudioMessages.clear();
    m_nextPlaybackSequence = 0;

    const QUrl url = liveUrl(baseUrl, sessionId);
    if (!url.isValid()) {
        setStatusText(QStringLiteral("TTS live audio URL is invalid."));
        return;
    }

    QNetworkRequest request(url);
    request.setRawHeader("Accept", "text/event-stream");
    m_reply = m_network->get(request);
    connect(m_reply, &QNetworkReply::readyRead, this, &TtsAudioController::handleReadyRead);
    connect(m_reply, &QNetworkReply::finished, this, &TtsAudioController::handleFinished);
    setStatusText(QStringLiteral("TTS live audio connecting."));
}

void TtsAudioController::closeLiveAudio() {
    m_pendingAudioMessages.clear();
    m_nextPlaybackSequence = 0;
    m_reconnectTimer.stop();
    if (m_reply == nullptr) {
        setConnected(false);
        return;
    }
    QNetworkReply *reply = m_reply;
    m_reply = nullptr;
    QObject::disconnect(reply, nullptr, this, nullptr);
    reply->abort();
    reply->deleteLater();
    m_sseBuffer.clear();
    setConnected(false);
}

void TtsAudioController::handleReadyRead() {
    if (m_reply == nullptr) {
        return;
    }
    m_sseBuffer.append(m_reply->readAll());
    parseSseFrames();
}

void TtsAudioController::handleFinished() {
    QNetworkReply *reply = qobject_cast<QNetworkReply *>(sender());
    if (reply == nullptr || reply != m_reply) {
        return;
    }
    const bool canceled = reply->error() == QNetworkReply::OperationCanceledError;
    if (!canceled) {
        parseSseFrames();
    }
    m_reply = nullptr;
    reply->deleteLater();
    setConnected(false);
    if (!canceled) {
        setStatusText(QStringLiteral("TTS live audio disconnected."));
        m_reconnectTimer.start();
    }
}

void TtsAudioController::parseSseFrames() {
    while (true) {
        int frameEnd = m_sseBuffer.indexOf("\n\n");
        int separatorLength = 2;
        if (frameEnd < 0) {
            frameEnd = m_sseBuffer.indexOf("\r\n\r\n");
            separatorLength = 4;
        }
        if (frameEnd < 0) {
            return;
        }

        const QByteArray frame = m_sseBuffer.left(frameEnd);
        m_sseBuffer.remove(0, frameEnd + separatorLength);
        handleSseFrame(frame);
    }
}

void TtsAudioController::handleSseFrame(const QByteArray &frame) {
    QList<QByteArray> lines = frame.split('\n');
    QByteArray eventName;
    QByteArray data;
    for (QByteArray line : lines) {
        line = line.trimmed();
        if (line.startsWith("event:")) {
            eventName = line.mid(6).trimmed();
        } else if (line.startsWith("data:")) {
            if (!data.isEmpty()) {
                data.append('\n');
            }
            data.append(line.mid(5).trimmed());
        }
    }

    if (eventName == "ready") {
        setConnected(true);
        setStatusText(QStringLiteral("TTS live audio connected."));
        return;
    }
    if (eventName != "tts-audio" || data.isEmpty()) {
        return;
    }

    const QJsonDocument document = QJsonDocument::fromJson(data);
    if (document.isObject()) {
        handleTtsAudioMessage(document.object());
    }
}

void TtsAudioController::handleTtsAudioMessage(const QJsonObject &object) {
    const qint64 playbackSequence = object.value(QStringLiteral("playbackSequence")).toVariant().toLongLong();
    if (playbackSequence <= 0) {
        return;
    }
    if (m_nextPlaybackSequence > 0 && playbackSequence < m_nextPlaybackSequence) {
        return;
    }

    m_pendingAudioMessages.insert(playbackSequence, object);
    if (m_nextPlaybackSequence <= 0) {
        m_nextPlaybackSequence = playbackSequence;
    }
    drainOrderedTtsAudioMessages();
}

void TtsAudioController::drainOrderedTtsAudioMessages() {
    while (m_nextPlaybackSequence > 0) {
        auto message = m_pendingAudioMessages.find(m_nextPlaybackSequence);
        if (message == m_pendingAudioMessages.end()) {
            return;
        }
        const QJsonObject object = message.value();
        m_pendingAudioMessages.erase(message);
        ++m_nextPlaybackSequence;
        processTtsAudioMessage(object);
    }
}

void TtsAudioController::processTtsAudioMessage(const QJsonObject &object) {
    if (object.value(QStringLiteral("finalChunk")).toBool(false)) {
        m_flushRequested = true;
        applyFadeOutToQueue();
        drainPlaybackQueue();
        return;
    }
    const QByteArray audioBytes = QByteArray::fromBase64(object.value(QStringLiteral("audioBase64")).toString().toUtf8());
    if (audioBytes.isEmpty()) {
        return;
    }
    enqueueAudio(
        audioBytes,
        object.value(QStringLiteral("mediaType")).toString(QStringLiteral("audio/wav")),
        object.value(QStringLiteral("sampleRate")).toInt(0));
}

void TtsAudioController::enqueueAudio(const QByteArray &audioBytes, const QString &mediaType, int sampleRate) {
    const DecodedAudio decoded = decodeAudio(audioBytes, mediaType, sampleRate);
    if (decoded.samples.isEmpty() || decoded.sampleRate <= 0 || decoded.channels <= 0) {
        setStatusText(QStringLiteral("TTS audio frame could not be decoded."));
        return;
    }

    if (!ensureOutputFormat()) {
        setStatusText(QStringLiteral("TTS audio output format is unavailable."));
        return;
    }
    QVector<float> samples = toMonoAndResample(decoded);
    if (samples.isEmpty()) {
        return;
    }
    if (m_flushRequested && m_playbackQueue.isEmpty()) {
        m_flushRequested = false;
        m_playbackStarted = false;
    }
    appendInterChunkSilence();
    appendPlaybackSamples(std::move(samples));
    m_audioChunkQueued = true;
    drainPlaybackQueue();
}

void TtsAudioController::drainPlaybackQueue() {
    if (m_playbackQueue.isEmpty()) {
        m_drainTimer.stop();
        m_playbackStarted = false;
        if (m_flushRequested) {
            m_flushRequested = false;
        }
        return;
    }
    if (!ensureOutputFormat()) {
        return;
    }
    if (!m_playbackStarted && !m_flushRequested && m_playbackQueue.size() < startupBufferFrames()) {
        if (!m_drainTimer.isActive()) {
            m_drainTimer.start();
        }
        return;
    }
    if (!ensurePlaybackSink() || m_sinkDevice == nullptr) {
        return;
    }

    const int frameBytes = bytesPerFrame(m_outputFormat);
    const qsizetype framesWritable = qMax<qsizetype>(0, m_sink->bytesFree()) / frameBytes;
    if (framesWritable <= 0) {
        if (!m_drainTimer.isActive()) {
            m_drainTimer.start();
        }
        return;
    }

    m_playbackStarted = true;
    const qsizetype frameCount = qMin<qsizetype>(framesWritable, m_playbackQueue.size());
    const QByteArray output = encodeQueuedFrames(frameCount);
    const qint64 written = m_sinkDevice->write(output.constData(), output.size());
    if (written > 0) {
        const qsizetype writtenFrames = qMin<qsizetype>(m_playbackQueue.size(), written / frameBytes);
        if (writtenFrames > 0) {
            m_playbackQueue.remove(0, writtenFrames);
        }
    }
    if (!m_playbackQueue.isEmpty() && !m_drainTimer.isActive()) {
        m_drainTimer.start();
    }
}

bool TtsAudioController::ensureOutputFormat() {
    if (m_outputFormat.isValid()) {
        return true;
    }

    QAudioDevice device = QMediaDevices::defaultAudioOutput();
    QAudioFormat preferred = device.preferredFormat();
    int sampleRate = preferred.isValid() && preferred.sampleRate() > 0 ? preferred.sampleRate() : 48000;
    int channelCount = preferred.isValid() && preferred.channelCount() > 0 ? preferred.channelCount() : 2;
    channelCount = qBound(1, channelCount, 2);

    QAudioFormat candidate;
    candidate.setSampleRate(sampleRate);
    candidate.setChannelCount(channelCount);
    candidate.setSampleFormat(QAudioFormat::Float);
    if (device.isFormatSupported(candidate)) {
        m_outputFormat = candidate;
        return true;
    }

    candidate.setSampleFormat(QAudioFormat::Int16);
    if (device.isFormatSupported(candidate)) {
        m_outputFormat = candidate;
        return true;
    }

    if (preferred.isValid()) {
        m_outputFormat = preferred;
        if (m_outputFormat.sampleRate() <= 0) {
            m_outputFormat.setSampleRate(sampleRate);
        }
        if (m_outputFormat.channelCount() <= 0) {
            m_outputFormat.setChannelCount(channelCount);
        }
        if (m_outputFormat.sampleFormat() == QAudioFormat::Unknown) {
            m_outputFormat.setSampleFormat(QAudioFormat::Int16);
        }
        return true;
    }

    m_outputFormat.setSampleRate(48000);
    m_outputFormat.setChannelCount(2);
    m_outputFormat.setSampleFormat(QAudioFormat::Int16);
    return m_outputFormat.isValid();
}

bool TtsAudioController::ensurePlaybackSink() {
    if (m_sink != nullptr && m_sinkDevice != nullptr) {
        return true;
    }
    if (!ensureOutputFormat()) {
        return false;
    }

    QAudioDevice device = QMediaDevices::defaultAudioOutput();
    m_sink = new QAudioSink(device, m_outputFormat, this);
    m_sink->setBufferSize(m_outputFormat.bytesForDuration(kSinkBufferMs * 1000));
    m_sinkDevice = m_sink->start();
    if (m_sinkDevice == nullptr) {
        resetPlayback();
        setStatusText(QStringLiteral("TTS audio output could not start."));
        return false;
    }
    setStatusText(QStringLiteral("TTS audio playing."));
    return true;
}

void TtsAudioController::resetPlayback() {
    m_drainTimer.stop();
    m_playbackQueue.clear();
    m_pendingAudioMessages.clear();
    m_nextPlaybackSequence = 0;
    m_playbackStarted = false;
    m_flushRequested = false;
    m_audioChunkQueued = false;
    if (m_sink != nullptr) {
        m_sink->stop();
        m_sink->deleteLater();
        m_sink = nullptr;
    }
    m_sinkDevice = nullptr;
    m_outputFormat = QAudioFormat();
}

void TtsAudioController::setConnected(bool value) {
    if (m_connected == value) {
        return;
    }
    m_connected = value;
    emit connectedChanged();
}

void TtsAudioController::setStatusText(const QString &value) {
    if (m_statusText == value) {
        return;
    }
    m_statusText = value;
    emit statusTextChanged();
}

QString TtsAudioController::normalizedBaseUrl() const {
    return normalizeBackendBaseUrl(m_settings == nullptr ? QString() : m_settings->backendBaseUrl());
}

QString TtsAudioController::currentSessionId() const {
    if (m_settings == nullptr) {
        return {};
    }
    const QString gameRpSessionId = m_settings->gameRpSessionId().trimmed();
    if (!gameRpSessionId.isEmpty()) {
        return gameRpSessionId;
    }
    return m_settings->sessionId().trimmed();
}

QUrl TtsAudioController::liveUrl(const QString &baseUrl, const QString &sessionId) const {
    QUrl url(baseUrl + QStringLiteral("/api/tts/live"));
    QUrlQuery query;
    query.addQueryItem(QStringLiteral("sessionId"), sessionId);
    url.setQuery(query);
    return url;
}

TtsAudioController::DecodedAudio TtsAudioController::decodeAudio(
    const QByteArray &audioBytes,
    const QString &mediaType,
    int sampleRate) const {
    const QString normalized = mediaType.toLower();
    if (normalized.contains(QStringLiteral("wav")) || audioBytes.startsWith("RIFF")) {
        return decodeWav(audioBytes);
    }
    return decodeRawPcm(audioBytes, normalized, sampleRate);
}

TtsAudioController::DecodedAudio TtsAudioController::decodeWav(const QByteArray &audioBytes) const {
    DecodedAudio output;
    if (audioBytes.size() < 44
        || audioBytes.mid(0, 4) != "RIFF"
        || audioBytes.mid(8, 4) != "WAVE") {
        return output;
    }

    int audioFormat = 0;
    int channels = 0;
    int sampleRate = 0;
    int bitsPerSample = 0;
    int dataOffset = -1;
    int dataSize = 0;

    int offset = 12;
    while (offset + 8 <= audioBytes.size()) {
        const QByteArray chunkId = audioBytes.mid(offset, 4);
        const int chunkSize = static_cast<int>(readLe32(audioBytes, offset + 4));
        const int payloadOffset = offset + 8;
        if (chunkSize < 0 || payloadOffset + chunkSize > audioBytes.size()) {
            break;
        }
        if (chunkId == "fmt " && chunkSize >= 16) {
            audioFormat = readLe16(audioBytes, payloadOffset);
            channels = readLe16(audioBytes, payloadOffset + 2);
            sampleRate = static_cast<int>(readLe32(audioBytes, payloadOffset + 4));
            bitsPerSample = readLe16(audioBytes, payloadOffset + 14);
        } else if (chunkId == "data") {
            dataOffset = payloadOffset;
            dataSize = chunkSize;
        }
        offset = payloadOffset + chunkSize + (chunkSize % 2);
    }

    if (dataOffset < 0 || dataSize <= 0 || channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0) {
        return {};
    }

    const bool floatFormat = audioFormat == 3;
    const int bytesPerSample = qMax(1, bitsPerSample / 8);
    const int bytesPerFrame = bytesPerSample * channels;
    const int frames = dataSize / bytesPerFrame;
    if (frames <= 0) {
        return {};
    }

    output.sampleRate = sampleRate;
    output.channels = channels;
    output.samples.reserve(frames * channels);
    const char *base = audioBytes.constData() + dataOffset;
    for (int frame = 0; frame < frames; ++frame) {
        const char *frameStart = base + frame * bytesPerFrame;
        for (int channel = 0; channel < channels; ++channel) {
            const char *sample = frameStart + channel * bytesPerSample;
            output.samples.append(floatFormat && bitsPerSample == 32
                                      ? floatSample32(sample)
                                      : pcmSampleToFloat(sample, bitsPerSample, true));
        }
    }
    return output;
}

TtsAudioController::DecodedAudio TtsAudioController::decodeRawPcm(
    const QByteArray &audioBytes,
    const QString &mediaType,
    int sampleRate) const {
    DecodedAudio output;
    const int channels = mediaTypeIntParameter(mediaType, QStringLiteral("channels"), 1);
    const int effectiveSampleRate = sampleRate > 0
        ? sampleRate
        : mediaTypeIntParameter(mediaType, QStringLiteral("rate"), 16000);
    if (channels <= 0 || effectiveSampleRate <= 0) {
        return output;
    }

    const bool float32 = mediaTypeHasToken(mediaType, QStringLiteral("f32"));
    const int bitsPerSample = float32 ? 32 : 16;
    const int bytesPerSample = bitsPerSample / 8;
    const int bytesPerFrame = bytesPerSample * channels;
    const int frames = audioBytes.size() / bytesPerFrame;
    if (frames <= 0) {
        return output;
    }

    output.sampleRate = effectiveSampleRate;
    output.channels = channels;
    output.samples.reserve(frames * channels);
    for (int frame = 0; frame < frames; ++frame) {
        const char *frameStart = audioBytes.constData() + frame * bytesPerFrame;
        for (int channel = 0; channel < channels; ++channel) {
            const char *sample = frameStart + channel * bytesPerSample;
            output.samples.append(float32
                                      ? floatSample32(sample)
                                      : pcmSampleToFloat(sample, bitsPerSample, true));
        }
    }
    return output;
}

QVector<float> TtsAudioController::toMonoAndResample(const DecodedAudio &audio) const {
    const int sourceChannels = qMax(1, audio.channels);
    const int sourceRate = qMax(1, audio.sampleRate);
    const int targetRate = qMax(1, m_outputFormat.sampleRate());
    const qsizetype sourceFrames = audio.samples.size() / sourceChannels;
    if (sourceFrames <= 0) {
        return {};
    }

    QVector<float> mono;
    mono.reserve(sourceFrames);
    for (qsizetype frame = 0; frame < sourceFrames; ++frame) {
        float value = 0.0f;
        for (int channel = 0; channel < sourceChannels; ++channel) {
            value += audio.samples.at(frame * sourceChannels + channel);
        }
        mono.append(qBound(-1.0f, value / sourceChannels, 1.0f));
    }
    if (sourceRate == targetRate || mono.size() <= 1) {
        return mono;
    }

    const qsizetype targetFrames = qMax<qsizetype>(
        1,
        static_cast<qsizetype>(std::llround(mono.size() * (targetRate / static_cast<double>(sourceRate)))));
    QVector<float> resampled;
    resampled.reserve(targetFrames);
    const double step = sourceRate / static_cast<double>(targetRate);
    for (qsizetype frame = 0; frame < targetFrames; ++frame) {
        const double sourcePosition = frame * step;
        const qsizetype left = qMin<qsizetype>(mono.size() - 1, static_cast<qsizetype>(std::floor(sourcePosition)));
        const qsizetype right = qMin<qsizetype>(mono.size() - 1, left + 1);
        const float ratio = static_cast<float>(sourcePosition - left);
        const float value = mono.at(left) + (mono.at(right) - mono.at(left)) * ratio;
        resampled.append(qBound(-1.0f, value, 1.0f));
    }
    return resampled;
}

void TtsAudioController::appendInterChunkSilence() {
    if (!m_audioChunkQueued || !m_outputFormat.isValid()) {
        return;
    }
    const int gapRangeMs = kInterChunkGapMaxMs - kInterChunkGapMinMs + 1;
    const int gapMs = kInterChunkGapMinMs
        + static_cast<int>(QRandomGenerator::global()->bounded(static_cast<quint32>(gapRangeMs)));
    const qsizetype frames = qMax<qsizetype>(
        1,
        (qMax(1, m_outputFormat.sampleRate()) * gapMs) / 1000);
    m_playbackQueue += QVector<float>(frames, 0.0f);
}

void TtsAudioController::appendPlaybackSamples(QVector<float> samples) {
    if (samples.isEmpty()) {
        return;
    }
    if (!m_playbackStarted && m_playbackQueue.isEmpty()) {
        applyFadeIn(samples);
    }

    const qsizetype overlap = qMin<qsizetype>(fadeFrames(), qMin<qsizetype>(m_playbackQueue.size(), samples.size()));
    if (overlap > 0) {
        const qsizetype start = m_playbackQueue.size() - overlap;
        for (qsizetype i = 0; i < overlap; ++i) {
            const float ratio = static_cast<float>(i + 1) / static_cast<float>(overlap + 1);
            m_playbackQueue[start + i] = m_playbackQueue.at(start + i) * (1.0f - ratio) + samples.at(i) * ratio;
        }
        samples.remove(0, overlap);
    }
    if (!samples.isEmpty()) {
        m_playbackQueue += samples;
    }
}

void TtsAudioController::applyFadeIn(QVector<float> &samples) const {
    const qsizetype frames = qMin<qsizetype>(fadeFrames(), samples.size());
    for (qsizetype i = 0; i < frames; ++i) {
        const float gain = static_cast<float>(i + 1) / static_cast<float>(frames);
        samples[i] *= gain;
    }
}

void TtsAudioController::applyFadeOutToQueue() {
    const qsizetype frames = qMin<qsizetype>(fadeFrames(), m_playbackQueue.size());
    for (qsizetype i = 0; i < frames; ++i) {
        const qsizetype index = m_playbackQueue.size() - frames + i;
        const float gain = 1.0f - (static_cast<float>(i + 1) / static_cast<float>(frames));
        m_playbackQueue[index] *= gain;
    }
}

QByteArray TtsAudioController::encodeQueuedFrames(qsizetype frameCount) const {
    const int targetChannels = qMax(1, m_outputFormat.channelCount());
    const int bytesPerSample = bytesForSampleFormat(m_outputFormat.sampleFormat());
    QByteArray output;
    output.resize(frameCount * targetChannels * bytesPerSample);
    uchar *write = reinterpret_cast<uchar *>(output.data());

    for (qsizetype frame = 0; frame < frameCount; ++frame) {
        const float value = qBound(-1.0f, m_playbackQueue.at(frame), 1.0f);
        for (int channel = 0; channel < targetChannels; ++channel) {
            const qsizetype offset = (frame * targetChannels + channel) * bytesPerSample;
            switch (m_outputFormat.sampleFormat()) {
            case QAudioFormat::UInt8:
                write[offset] = static_cast<uchar>(qBound(0, static_cast<int>(std::lround((value + 1.0f) * 127.5f)), 255));
                break;
            case QAudioFormat::Int32:
                qToLittleEndian<qint32>(floatToPcm32(value), write + offset);
                break;
            case QAudioFormat::Float: {
                std::memcpy(write + offset, &value, sizeof(float));
                break;
            }
            case QAudioFormat::Int16:
            default:
                qToLittleEndian<qint16>(floatToPcm16(value), write + offset);
                break;
            }
        }
    }
    return output;
}

qsizetype TtsAudioController::startupBufferFrames() const {
    return qMax<qsizetype>(1, (qMax(1, m_outputFormat.sampleRate()) * kStartupBufferMs) / 1000);
}

qsizetype TtsAudioController::fadeFrames() const {
    return qMax<qsizetype>(1, (qMax(1, m_outputFormat.sampleRate()) * kFadeMs) / 1000);
}
