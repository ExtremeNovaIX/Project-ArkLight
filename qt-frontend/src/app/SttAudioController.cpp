#include "SttAudioController.h"

#include "FrontendSettings.h"
#include "audio/SttAudioProcessing.h"

#include <QAbstractSocket>
#include <QAudioSource>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QIODevice>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRandomGenerator>
#include <QStandardPaths>
#include <QUrl>
#include <QVector>
#include <QtEndian>
#include <QtMath>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

using namespace sttaudio;


SttAudioController::SttAudioController(FrontendSettings *settings, QObject *parent)
    : QObject(parent)
    , m_settings(settings) {
    connect(&m_mediaDevices, &QMediaDevices::audioInputsChanged, this, &SttAudioController::refreshDevices);
    connect(&m_socket, &QTcpSocket::connected, this, &SttAudioController::handleSocketConnected);
    connect(&m_socket, &QTcpSocket::readyRead, this, &SttAudioController::handleSocketReadyRead);
    connect(&m_socket, &QTcpSocket::disconnected, this, [this]() {
        m_primaryBackendConnected = false;
        updateBackendConnected();
        m_handshakeComplete = false;
        if (!m_stopRequested && m_running) {
            setStatusText(localListeningStatus() + QStringLiteral(" STT disconnected."));
        } else if (!m_stopRequested) {
            setStatusText(QStringLiteral("Voice stream disconnected."));
        }
    });
    connect(&m_socket, &QTcpSocket::errorOccurred, this, [this](QAbstractSocket::SocketError) {
        m_primaryBackendConnected = false;
        updateBackendConnected();
        m_handshakeComplete = false;
        const QString errorText = m_socket.errorString().trimmed();
        if (m_running) {
            setStatusText(localListeningStatus() + QStringLiteral(" STT offline: ")
                          + (errorText.isEmpty() ? QStringLiteral("connection failed.") : errorText));
        } else {
            setStatusText(errorText.isEmpty() ? QStringLiteral("Voice stream error.") : errorText);
        }
    });
    refreshDevices();
}

SttAudioController::~SttAudioController() {
    stopAudioCapture();
    closeSocket();
}

bool SttAudioController::running() const {
    return m_running;
}

bool SttAudioController::backendConnected() const {
    return m_backendConnected;
}

QStringList SttAudioController::deviceNames() const {
    return m_deviceNames;
}

int SttAudioController::selectedDeviceIndex() const {
    return m_selectedDeviceIndex;
}

QString SttAudioController::statusText() const {
    return m_statusText;
}

QString SttAudioController::transcriptText() const {
    return m_transcriptText;
}

QString SttAudioController::partialTranscriptText() const {
    return m_partialTranscriptText;
}

QString SttAudioController::finalTranscriptText() const {
    return m_finalTranscriptText;
}

qreal SttAudioController::audioLevel() const {
    return m_audioLevel;
}

QStringList SttAudioController::debugEvents() const {
    return m_debugEvents;
}

int SttAudioController::debugEventCount() const {
    return m_debugEvents.size();
}

QString SttAudioController::lastDebugExportPath() const {
    return m_lastDebugExportPath;
}

void SttAudioController::refreshDevices() {
    m_allDevices = QMediaDevices::audioInputs();
    rebuildDeviceList();
}

void SttAudioController::start() {
    if (m_running) {
        return;
    }
    if (m_settings == nullptr) {
        setStatusText(QStringLiteral("Voice settings are unavailable."));
        return;
    }

    closeSocket();
    setTranscriptText(QString());
    setPartialTranscriptText(QString());
    setFinalTranscriptText(QString());
    setAudioLevel(0.0);
    resetVoiceBandFilter();
    m_stopRequested = false;

    if (!beginAudioCapture()) {
        setRunning(false);
        return;
    }

    setRunning(true);
    setStatusText(localListeningStatus() + QStringLiteral(" Connecting STT..."));
    connectBackend();
}

void SttAudioController::stop() {
    if (!m_running && m_socket.state() == QAbstractSocket::UnconnectedState) {
        return;
    }

    m_stopRequested = true;
    stopAudioCapture();
    setRunning(false);
    setAudioLevel(0.0);

    if (m_socket.state() == QAbstractSocket::ConnectedState && m_handshakeComplete) {
        flushPendingPcm(true);
        sendTextFrame(QStringLiteral("done"));
        setStatusText(QStringLiteral("Stopping voice stream..."));
    } else {
        closeSocket();
        setStatusText(QStringLiteral("Voice input stopped."));
    }
}

void SttAudioController::clearDebugEvents() {
    if (m_debugEvents.isEmpty() && m_debugCsvRows.isEmpty()) {
        return;
    }
    m_debugEvents.clear();
    m_debugCsvRows.clear();
    m_lastDebugSignature.clear();
    m_lastDebugEventAt = 0;
    emit debugEventsChanged();
    setLastDebugExportPath(QString());
}

void SttAudioController::exportDebugEvents() {
    if (m_debugCsvRows.isEmpty()) {
        setStatusText(QStringLiteral("No voice debug events to export."));
        return;
    }

    QString directory = QStandardPaths::writableLocation(QStandardPaths::DocumentsLocation);
    if (directory.isEmpty()) {
        directory = QDir::currentPath();
    }
    QDir dir(directory);
    const QString fileName = QStringLiteral("arklight-voice-debug-%1.csv")
            .arg(QDateTime::currentDateTime().toString(QStringLiteral("yyyyMMdd-HHmmss")));
    const QString filePath = dir.absoluteFilePath(fileName);

    QFile file(filePath);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Text)) {
        setStatusText(QStringLiteral("Could not export voice debug CSV."));
        return;
    }

    file.write("localTime,receivedAt,stream,asrType,phase,speakerId,speakerConfidence,quality,overlap,noiseLevel,stable,revision,latencyMs,startMs,endMs,text,intent,confidence,instruction,consumed,triggered,routed,routeDurationMs,reason\n");
    for (const QString &row : m_debugCsvRows) {
        file.write(row.toUtf8());
        file.write("\n");
    }
    setLastDebugExportPath(filePath);
    setStatusText(QStringLiteral("Voice debug CSV exported: %1").arg(filePath));
}

void SttAudioController::setSelectedDeviceIndex(int value) {
    const int maxIndex = qMax(0, m_filteredDevices.size() - 1);
    const int normalized = qBound(0, value, maxIndex);
    if (m_selectedDeviceIndex == normalized) {
        return;
    }
    const bool wasRunning = m_running;
    if (wasRunning) {
        stop();
    }
    m_selectedDeviceIndex = normalized;
    m_selectedDeviceId = m_filteredDevices.isEmpty() ? QString() : deviceId(m_filteredDevices.at(m_selectedDeviceIndex));
    emit selectedDeviceIndexChanged();
}

void SttAudioController::rebuildDeviceList() {
    const QString previousDeviceId = m_selectedDeviceId.isEmpty() ? deviceId(selectedDevice()) : m_selectedDeviceId;
    m_filteredDevices = m_allDevices;

    QStringList nextNames;
    for (const QAudioDevice &device : m_filteredDevices) {
        nextNames.append(device.description());
    }
    if (nextNames.isEmpty()) {
        nextNames.append(QStringLiteral("No audio input devices"));
    }

    m_deviceNames = nextNames;
    const int previousIndex = m_selectedDeviceIndex;
    m_selectedDeviceIndex = 0;
    if (!previousDeviceId.isEmpty()) {
        for (int index = 0; index < m_filteredDevices.size(); ++index) {
            if (deviceId(m_filteredDevices.at(index)) == previousDeviceId) {
                m_selectedDeviceIndex = index;
                break;
            }
        }
    }
    m_selectedDeviceId = m_filteredDevices.isEmpty() ? QString() : deviceId(m_filteredDevices.at(m_selectedDeviceIndex));
    if (previousIndex != m_selectedDeviceIndex) {
        emit selectedDeviceIndexChanged();
    }
    emit devicesChanged();

    if (!m_running) {
        setStatusText(m_filteredDevices.isEmpty()
                          ? QStringLiteral("No audio input device is available.")
                          : QStringLiteral("Voice input ready."));
    }
}

bool SttAudioController::beginAudioCapture() {
    return beginDeviceAudioCapture();
}

bool SttAudioController::beginDeviceAudioCapture() {
    if (m_filteredDevices.isEmpty()) {
        setStatusText(QStringLiteral("No audio input device is available."));
        return false;
    }

    const QAudioDevice device = selectedDevice();
    if (device.isNull()) {
        setStatusText(QStringLiteral("Selected audio input is unavailable."));
        return false;
    }

    m_captureFormat = captureFormatFor(device);
    if (!m_captureFormat.isValid()) {
        setStatusText(QStringLiteral("Selected audio format is unavailable."));
        return false;
    }

    m_audioSource = new QAudioSource(device, m_captureFormat, this);
    m_audioSource->setBufferSize(m_captureFormat.bytesForDuration(120000));
    m_audioDevice = m_audioSource->start();
    if (m_audioDevice == nullptr) {
        m_audioSource->deleteLater();
        m_audioSource = nullptr;
        setStatusText(QStringLiteral("Could not start audio input."));
        return false;
    }

    connect(m_audioDevice, &QIODevice::readyRead, this, &SttAudioController::handleAudioReadyRead);
    return true;
}

void SttAudioController::stopAudioCapture() {
    if (m_audioSource != nullptr) {
        m_audioSource->stop();
        m_audioSource->deleteLater();
        m_audioSource = nullptr;
    }
    m_audioDevice = nullptr;
}

void SttAudioController::connectBackend() {
    const QUrl url = sttWebSocketUrl();
    if (!url.isValid() || url.host().isEmpty()) {
        setStatusText(localListeningStatus() + QStringLiteral(" STT disabled: backend URL is invalid."));
        return;
    }
    if (url.scheme() != QStringLiteral("ws")) {
        setStatusText(localListeningStatus() + QStringLiteral(" STT disabled: use an http backend URL."));
        return;
    }

    m_socketUrl = url;
    m_socketBuffer.clear();
    m_handshakeComplete = false;
    m_primaryBackendConnected = false;
    updateBackendConnected();
    m_socket.connectToHost(url.host(), url.port(80));
}

void SttAudioController::closeSocket() {
    if (m_socket.state() == QAbstractSocket::ConnectedState) {
        if (m_handshakeComplete) {
            sendFrame(0x8, QByteArray());
        }
        m_socket.disconnectFromHost();
    } else if (m_socket.state() != QAbstractSocket::UnconnectedState) {
        m_socket.abort();
    }
    m_handshakeComplete = false;
    m_primaryBackendConnected = false;
    updateBackendConnected();
    m_socketBuffer.clear();
    m_pendingPcm.clear();
}

void SttAudioController::sendSttConfig() {
    if (m_settings == nullptr) {
        return;
    }
    QString sessionId = m_settings->sessionId().trimmed();
    QString gameSessionId = m_settings->gameSessionId().trimmed();
    QString gameRpSessionId = m_settings->gameRpSessionId().trimmed();
    if (gameSessionId.isEmpty()) {
        gameSessionId = sessionId;
    }
    if (gameRpSessionId.isEmpty()) {
        gameRpSessionId = gameSessionId;
    }
    QJsonObject config;
    config.insert(QStringLiteral("rpSessionId"), sessionId);
    config.insert(QStringLiteral("gameRpSessionId"), gameRpSessionId);
    config.insert(QStringLiteral("characterName"), m_settings->characterName());
    config.insert(QStringLiteral("debugOnly"), m_settings->voiceDebugEnabled());
    config.insert(QStringLiteral("source"), QStringLiteral("microphone"));
    sendTextFrame(QString::fromUtf8(QJsonDocument(config).toJson(QJsonDocument::Compact)));
}

void SttAudioController::handleAudioReadyRead() {
    if (m_audioDevice == nullptr) {
        return;
    }

    const QByteArray raw = m_audioDevice->readAll();
    const QByteArray pcm = convertToPcm16Mono16k(raw);
    sendPcmToBackend(pcm);
}

void SttAudioController::handleSocketConnected() {
    QByteArray keyBytes;
    keyBytes.resize(16);
    for (char &byte : keyBytes) {
        byte = static_cast<char>(QRandomGenerator::global()->generate() & 0xff);
    }
    const QByteArray key = keyBytes.toBase64();
    QString path = m_socketUrl.path().isEmpty() ? QStringLiteral("/") : m_socketUrl.path();
    if (m_socketUrl.hasQuery()) {
        path += QLatin1Char('?') + m_socketUrl.query();
    }
    QString hostHeader = m_socketUrl.host();
    const int port = m_socketUrl.port(80);
    if (port != 80) {
        hostHeader += QStringLiteral(":%1").arg(port);
    }

    QByteArray request;
    request += "GET " + path.toUtf8() + " HTTP/1.1\r\n";
    request += "Host: " + hostHeader.toUtf8() + "\r\n";
    request += "Upgrade: websocket\r\n";
    request += "Connection: Upgrade\r\n";
    request += "Sec-WebSocket-Key: " + key + "\r\n";
    request += "Sec-WebSocket-Version: 13\r\n";
    request += "\r\n";
    m_socket.write(request);
}

void SttAudioController::handleSocketReadyRead() {
    m_socketBuffer.append(m_socket.readAll());
    if (!m_handshakeComplete) {
        const int headerEnd = m_socketBuffer.indexOf("\r\n\r\n");
        if (headerEnd < 0) {
            return;
        }
        const QByteArray header = m_socketBuffer.left(headerEnd);
        m_socketBuffer.remove(0, headerEnd + 4);
        if (!header.startsWith("HTTP/1.1 101") && !header.startsWith("HTTP/1.0 101")) {
            setStatusText(localListeningStatus() + QStringLiteral(" STT refused WebSocket."));
            closeSocket();
            return;
        }
        m_handshakeComplete = true;
        m_primaryBackendConnected = true;
        updateBackendConnected();
        sendSttConfig();
        setStatusText(localListeningStatus() + QStringLiteral(" STT connected."));
    }
    parseSocketFrames();
}

void SttAudioController::parseSocketFrames() {
    while (m_socketBuffer.size() >= 2) {
        const uchar first = static_cast<uchar>(m_socketBuffer.at(0));
        const uchar second = static_cast<uchar>(m_socketBuffer.at(1));
        const quint8 opcode = first & 0x0f;
        const bool masked = (second & 0x80) != 0;
        quint64 payloadLength = second & 0x7f;
        int offset = 2;

        if (payloadLength == 126) {
            if (m_socketBuffer.size() < offset + 2) {
                return;
            }
            payloadLength = qFromBigEndian<quint16>(reinterpret_cast<const uchar *>(m_socketBuffer.constData() + offset));
            offset += 2;
        } else if (payloadLength == 127) {
            if (m_socketBuffer.size() < offset + 8) {
                return;
            }
            payloadLength = qFromBigEndian<quint64>(reinterpret_cast<const uchar *>(m_socketBuffer.constData() + offset));
            offset += 8;
        }

        QByteArray maskKey;
        if (masked) {
            if (m_socketBuffer.size() < offset + 4) {
                return;
            }
            maskKey = m_socketBuffer.mid(offset, 4);
            offset += 4;
        }

        if (payloadLength > static_cast<quint64>(std::numeric_limits<int>::max())
            || m_socketBuffer.size() < offset + static_cast<int>(payloadLength)) {
            return;
        }

        QByteArray payload = m_socketBuffer.mid(offset, static_cast<int>(payloadLength));
        m_socketBuffer.remove(0, offset + static_cast<int>(payloadLength));
        if (masked) {
            for (int i = 0; i < payload.size(); ++i) {
                payload[i] = static_cast<char>(payload.at(i) ^ maskKey.at(i % 4));
            }
        }

        if (opcode == 0x1) {
            handleTextMessage(QString::fromUtf8(payload), QStringLiteral("microphone"));
        } else if (opcode == 0x8) {
            closeSocket();
            return;
        } else if (opcode == 0x9) {
            sendFrame(0xA, payload);
        }
    }
}

void SttAudioController::handleTextMessage(const QString &message, const QString &streamName) {
    const QJsonDocument document = QJsonDocument::fromJson(message.toUtf8());
    if (!document.isObject()) {
        return;
    }

    QJsonObject object = document.object();
    if (!streamName.isEmpty() && !object.contains(QStringLiteral("stream"))) {
        object.insert(QStringLiteral("stream"), streamName);
    }
    const QString type = object.value(QStringLiteral("type")).toString();
    if (type == QStringLiteral("result")) {
        const QString text = object.value(QStringLiteral("text")).toString().trimmed();
        const bool finalResult = object.value(QStringLiteral("final")).toBool(false);
        if (!text.isEmpty()) {
            setTranscriptText(text);
            if (finalResult) {
                setFinalTranscriptText(text);
            } else {
                setPartialTranscriptText(text);
            }
            if (m_settings != nullptr && m_settings->voiceDebugEnabled()) {
                appendDebugEvent(object);
            }
            setStatusText(finalResult
                              ? QStringLiteral("%1: %2").arg(m_settings != nullptr && m_settings->voiceDebugEnabled()
                                                             ? QStringLiteral("Voice debug final")
                                                             : QStringLiteral("Voice recognized"), text)
                              : QStringLiteral("%1: %2").arg(m_settings != nullptr && m_settings->voiceDebugEnabled()
                                                             ? QStringLiteral("Voice debug partial")
                                                             : QStringLiteral("Voice partial"), text));
        }
        return;
    }
    if (type == QStringLiteral("debug")) {
        handleDebugMessage(object);
        return;
    }
    if (type == QStringLiteral("end")) {
        closeSocket();
        setStatusText(QStringLiteral("Voice stream stopped."));
        return;
    }    if (type == QStringLiteral("error")) {
        setStatusText(object.value(QStringLiteral("message")).toString(QStringLiteral("Voice stream error.")));
        closeSocket();
    }
}

void SttAudioController::handleDebugMessage(const QJsonObject &object) {
    appendDebugEvent(object);
}

void SttAudioController::sendTextFrame(const QString &message) {
    sendFrame(0x1, message.toUtf8());
}

void SttAudioController::sendBinaryFrame(const QByteArray &payload) {
    sendFrame(0x2, payload);
}

void SttAudioController::sendPcmToBackend(const QByteArray &payload) {
    if (payload.isEmpty() || m_socket.state() != QAbstractSocket::ConnectedState || !m_handshakeComplete) {
        return;
    }
    m_pendingPcm.append(payload);
    flushPendingPcm(false);
}

void SttAudioController::flushPendingPcm(bool force) {
    if (m_socket.state() != QAbstractSocket::ConnectedState || !m_handshakeComplete) {
        m_pendingPcm.clear();
        return;
    }
    while (m_pendingPcm.size() >= PcmFlushBytes) {
        const QByteArray chunk = m_pendingPcm.left(PcmFlushBytes);
        m_pendingPcm.remove(0, PcmFlushBytes);
        sendBinaryFrame(chunk);
    }
    if (force && !m_pendingPcm.isEmpty()) {
        sendBinaryFrame(m_pendingPcm);
        m_pendingPcm.clear();
    }
}

void SttAudioController::sendFrame(quint8 opcode, const QByteArray &payload) {
    if (m_socket.state() != QAbstractSocket::ConnectedState) {
        return;
    }

    QByteArray frame;
    frame.append(static_cast<char>(0x80 | (opcode & 0x0f)));
    const quint64 size = static_cast<quint64>(payload.size());
    if (size <= 125) {
        frame.append(static_cast<char>(0x80 | size));
    } else if (size <= 0xffff) {
        frame.append(static_cast<char>(0x80 | 126));
        char lengthBytes[2];
        qToBigEndian<quint16>(static_cast<quint16>(size), reinterpret_cast<uchar *>(lengthBytes));
        frame.append(lengthBytes, 2);
    } else {
        frame.append(static_cast<char>(0x80 | 127));
        char lengthBytes[8];
        qToBigEndian<quint64>(size, reinterpret_cast<uchar *>(lengthBytes));
        frame.append(lengthBytes, 8);
    }

    QByteArray maskKey;
    maskKey.resize(4);
    for (char &byte : maskKey) {
        byte = static_cast<char>(QRandomGenerator::global()->generate() & 0xff);
    }
    frame.append(maskKey);

    QByteArray maskedPayload = payload;
    for (int i = 0; i < maskedPayload.size(); ++i) {
        maskedPayload[i] = static_cast<char>(maskedPayload.at(i) ^ maskKey.at(i % 4));
    }
    frame.append(maskedPayload);
    m_socket.write(frame);
}

void SttAudioController::setRunning(bool value) {
    if (m_running == value) {
        return;
    }
    m_running = value;
    emit runningChanged();
}

void SttAudioController::setBackendConnected(bool value) {
    if (m_backendConnected == value) {
        return;
    }
    m_backendConnected = value;
    emit backendConnectedChanged();
}

void SttAudioController::updateBackendConnected() {
    setBackendConnected(m_primaryBackendConnected);
}

void SttAudioController::setStatusText(const QString &value) {
    if (m_statusText == value) {
        return;
    }
    m_statusText = value;
    emit statusTextChanged();
}

void SttAudioController::setTranscriptText(const QString &value) {
    if (m_transcriptText == value) {
        return;
    }
    m_transcriptText = value;
    emit transcriptTextChanged();
}

void SttAudioController::setPartialTranscriptText(const QString &value) {
    if (m_partialTranscriptText == value) {
        return;
    }
    m_partialTranscriptText = value;
    emit transcriptTextChanged();
}

void SttAudioController::setFinalTranscriptText(const QString &value) {
    if (m_finalTranscriptText == value) {
        return;
    }
    m_finalTranscriptText = value;
    emit transcriptTextChanged();
}

void SttAudioController::setAudioLevel(qreal value) {
    const qreal normalized = qBound<qreal>(0.0, value, 1.0);
    if (qAbs(m_audioLevel - normalized) < 0.01) {
        return;
    }
    m_audioLevel = normalized;
    emit audioLevelChanged();
}

void SttAudioController::appendDebugEvent(const QJsonObject &object) {
    const QString localTime = QDateTime::currentDateTime().toString(QStringLiteral("HH:mm:ss.zzz"));
    const QString eventType = object.value(QStringLiteral("type")).toString();
    const QString stream = object.value(QStringLiteral("stream")).toString(QStringLiteral("stt"));
    const bool finalResult = object.value(QStringLiteral("final")).toBool(false);
    if (!finalResult) {
        return;
    }
    const QString asrType = object.value(QStringLiteral("asrType")).toString(
        eventType == QStringLiteral("result")
            ? (finalResult ? QStringLiteral("final") : QStringLiteral("partial"))
            : QStringLiteral("debug"));
    const QString receivedAt = object.value(QStringLiteral("receivedAt")).toString();
    const QString phase = finalResult ? QStringLiteral("FINAL") : QStringLiteral("PARTIAL");
    const QString text = object.value(QStringLiteral("text")).toString().trimmed();
    const QString intent = object.value(QStringLiteral("intent")).toString(
        eventType == QStringLiteral("result") ? QStringLiteral("ASR") : QStringLiteral("CHAT"));
    const QString speakerId = object.value(QStringLiteral("speakerId")).toString(QStringLiteral("UNKNOWN"));
    const double speakerConfidence = object.value(QStringLiteral("speakerConfidence")).toDouble(0.0);
    const QString quality = object.value(QStringLiteral("quality")).toString(QStringLiteral("uncertain"));
    const bool overlap = object.value(QStringLiteral("overlap")).toBool(false);
    const double noiseLevel = object.value(QStringLiteral("noiseLevel")).toDouble(0.0);
    const bool stable = object.value(QStringLiteral("stable")).toBool(finalResult);
    const int revision = object.value(QStringLiteral("revision")).toInt(-1);
    const int latencyMs = object.value(QStringLiteral("latencyMs")).toInt(-1);
    const int startMs = object.value(QStringLiteral("startMs")).toInt(-1);
    const int endMs = object.value(QStringLiteral("endMs")).toInt(-1);
    const double confidence = object.value(QStringLiteral("confidence")).toDouble(0.0);
    const QString instruction = object.value(QStringLiteral("instruction")).toString().trimmed();
    const bool consumed = object.value(QStringLiteral("consumed")).toBool(false);
    const bool triggered = object.value(QStringLiteral("triggered")).toBool(false);
    const bool routed = object.value(QStringLiteral("routed")).toBool(false);
    const int routeDurationMs = object.value(QStringLiteral("routeDurationMs")).toInt(0);
    const QString reason = object.value(QStringLiteral("reason")).toString();
    const QString signature = QStringList{
        phase,
        stream,
        speakerId,
        text,
        intent,
        quality,
        stable ? QStringLiteral("1") : QStringLiteral("0"),
        QString::number(revision),
        consumed ? QStringLiteral("1") : QStringLiteral("0"),
        triggered ? QStringLiteral("1") : QStringLiteral("0"),
        routed ? QStringLiteral("1") : QStringLiteral("0"),
        reason
    }.join(QLatin1Char('|'));
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (signature == m_lastDebugSignature && now - m_lastDebugEventAt < 1500) {
        return;
    }
    m_lastDebugSignature = signature;
    m_lastDebugEventAt = now;

    const QString latencyText = latencyMs >= 0 ? QString::number(latencyMs) + QStringLiteral("ms") : QStringLiteral("-");
    const QString summary = QStringList{
        localTime,
        stream,
        asrType,
        phase,
        QStringLiteral("speaker=%1(%2)").arg(speakerId, QString::number(speakerConfidence, 'f', 2)),
        QStringLiteral("quality=%1").arg(quality),
        QStringLiteral("overlap=%1").arg(overlap ? QStringLiteral("yes") : QStringLiteral("no")),
        QStringLiteral("stable=%1").arg(stable ? QStringLiteral("yes") : QStringLiteral("no")),
        QStringLiteral("rev=%1").arg(revision >= 0 ? QString::number(revision) : QStringLiteral("-")),
        QStringLiteral("latency=%1").arg(latencyText),
        QStringLiteral("route=%1ms").arg(routeDurationMs),
        QStringLiteral("consumed=%1").arg(consumed ? QStringLiteral("yes") : QStringLiteral("no")),
        text
    }.join(QStringLiteral("  "));
    m_debugEvents.prepend(summary);
    while (m_debugEvents.size() > 200) {
        m_debugEvents.removeLast();
    }

    if (finalResult) {
        m_debugCsvRows.append(QStringList{
            csvEscape(localTime),
            csvEscape(receivedAt),
            csvEscape(stream),
            csvEscape(asrType),
            csvEscape(phase),
            csvEscape(speakerId),
            QString::number(speakerConfidence, 'f', 4),
            csvEscape(quality),
            overlap ? QStringLiteral("true") : QStringLiteral("false"),
            QString::number(noiseLevel, 'f', 4),
            stable ? QStringLiteral("true") : QStringLiteral("false"),
            revision >= 0 ? QString::number(revision) : QString(),
            latencyMs >= 0 ? QString::number(latencyMs) : QString(),
            startMs >= 0 ? QString::number(startMs) : QString(),
            endMs >= 0 ? QString::number(endMs) : QString(),
            csvEscape(text),
            csvEscape(intent),
            QString::number(confidence, 'f', 4),
            csvEscape(instruction),
            consumed ? QStringLiteral("true") : QStringLiteral("false"),
            triggered ? QStringLiteral("true") : QStringLiteral("false"),
            routed ? QStringLiteral("true") : QStringLiteral("false"),
            QString::number(routeDurationMs),
            csvEscape(reason)
        }.join(QLatin1Char(',')));
    }

    emit debugEventsChanged();
}

void SttAudioController::setLastDebugExportPath(const QString &value) {
    if (m_lastDebugExportPath == value) {
        return;
    }
    m_lastDebugExportPath = value;
    emit lastDebugExportPathChanged();
}

void SttAudioController::resetVoiceBandFilter() {
    m_voiceBandHighPassPreviousInput = 0.0;
    m_voiceBandHighPassPreviousOutput = 0.0;
    m_voiceBandLowPassPreviousOutput = 0.0;
}

QString SttAudioController::localListeningStatus() const {
    return QStringLiteral("Local preview: audio input.");
}

QAudioFormat SttAudioController::captureFormatFor(const QAudioDevice &device) const {
    QAudioFormat target;
    target.setSampleRate(TargetSampleRate);
    target.setChannelCount(TargetChannelCount);
    target.setSampleFormat(QAudioFormat::Int16);
    if (device.isFormatSupported(target)) {
        return target;
    }
    return device.preferredFormat();
}

QByteArray SttAudioController::convertToPcm16Mono16k(const QByteArray &raw) {
    if (raw.isEmpty() || !m_captureFormat.isValid()) {
        return {};
    }

    const int channels = qMax(1, m_captureFormat.channelCount());
    const int sourceRate = qMax(1, m_captureFormat.sampleRate());
    const int sourceBytesPerSample = bytesPerSample(m_captureFormat.sampleFormat());
    if (sourceBytesPerSample <= 0) {
        return {};
    }

    const int sourceBytesPerFrame = sourceBytesPerSample * channels;
    const int sourceFrames = raw.size() / sourceBytesPerFrame;
    if (sourceFrames <= 0) {
        return {};
    }

    QVector<float> mono;
    mono.reserve(sourceFrames);
    for (int frame = 0; frame < sourceFrames; ++frame) {
        const char *frameStart = raw.constData() + frame * sourceBytesPerFrame;
        float mixed = 0.0f;
        for (int channel = 0; channel < channels; ++channel) {
            mixed += sampleAsFloat(frameStart + channel * sourceBytesPerSample, m_captureFormat.sampleFormat());
        }
        mixed /= static_cast<float>(channels);
        mono.append(mixed);
    }

    QByteArray pcm = resampleMonoToPcm16k(mono, sourceRate);
    filterVoiceBandPcm16InPlace(&pcm,
                                &m_voiceBandHighPassPreviousInput,
                                &m_voiceBandHighPassPreviousOutput,
                                &m_voiceBandLowPassPreviousOutput);
    setAudioLevel(pcm16Level(pcm));
    return pcm;
}

QUrl SttAudioController::sttWebSocketUrl() const {
    QString baseUrl = normalizeBackendBaseUrl(m_settings == nullptr ? QString() : m_settings->backendBaseUrl());
    if (baseUrl.startsWith(QStringLiteral("https://"), Qt::CaseInsensitive)) {
        baseUrl.replace(0, 5, QStringLiteral("wss"));
    } else if (baseUrl.startsWith(QStringLiteral("http://"), Qt::CaseInsensitive)) {
        baseUrl.replace(0, 4, QStringLiteral("ws"));
    }
    return QUrl(baseUrl + QStringLiteral("/stt/stream"));
}

QAudioDevice SttAudioController::selectedDevice() const {
    if (m_filteredDevices.isEmpty()) {
        return {};
    }
    if (!m_selectedDeviceId.isEmpty()) {
        for (const QAudioDevice &device : m_filteredDevices) {
            if (deviceId(device) == m_selectedDeviceId) {
                return device;
            }
        }
    }
    const int index = qBound(0, m_selectedDeviceIndex, m_filteredDevices.size() - 1);
    return m_filteredDevices.at(index);
}

QString SttAudioController::deviceId(const QAudioDevice &device) const {
    if (device.isNull()) {
        return {};
    }
    const QByteArray id = device.id();
    if (!id.isEmpty()) {
        return QString::fromUtf8(id);
    }
    return device.description();
}

