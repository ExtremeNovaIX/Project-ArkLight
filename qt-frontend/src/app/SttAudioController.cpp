#include "SttAudioController.h"

#include "FrontendSettings.h"

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

namespace {

constexpr int TargetSampleRate = 16000;
constexpr int TargetChannelCount = 1;
constexpr int TargetBytesPerSample = 2;

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

QString csvEscape(QString value) {
    value.replace('"', "\"\"");
    return QStringLiteral("\"%1\"").arg(value);
}

} // namespace

SttAudioController::SttAudioController(FrontendSettings *settings, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
    , m_processCapture(new WindowsProcessAudioCapture(this)) {
    connect(&m_mediaDevices, &QMediaDevices::audioInputsChanged, this, &SttAudioController::refreshDevices);
    connect(&m_socket, &QTcpSocket::connected, this, &SttAudioController::handleSocketConnected);
    connect(&m_socket, &QTcpSocket::readyRead, this, &SttAudioController::handleSocketReadyRead);
    connect(&m_socket, &QTcpSocket::disconnected, this, [this]() {
        setBackendConnected(false);
        m_handshakeComplete = false;
        if (!m_stopRequested && m_running) {
            setStatusText(localListeningStatus() + QStringLiteral(" STT disconnected."));
        } else if (!m_stopRequested) {
            setStatusText(QStringLiteral("Voice stream disconnected."));
        }
    });
    connect(&m_socket, &QTcpSocket::errorOccurred, this, [this](QAbstractSocket::SocketError) {
        setBackendConnected(false);
        m_handshakeComplete = false;
        const QString errorText = m_socket.errorString().trimmed();
        if (m_running) {
            setStatusText(localListeningStatus() + QStringLiteral(" STT offline: ")
                          + (errorText.isEmpty() ? QStringLiteral("connection failed.") : errorText));
        } else {
            setStatusText(errorText.isEmpty() ? QStringLiteral("Voice stream error.") : errorText);
        }
    });
    connect(m_processCapture, &WindowsProcessAudioCapture::pcmReady, this, &SttAudioController::handleProcessPcmReady);
    connect(m_processCapture, &WindowsProcessAudioCapture::stopped, this, &SttAudioController::handleProcessCaptureStopped);
    refreshDevices();
    refreshProcesses();
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

int SttAudioController::sourceMode() const {
    return m_sourceMode;
}

QStringList SttAudioController::deviceNames() const {
    return m_deviceNames;
}

int SttAudioController::selectedDeviceIndex() const {
    return m_selectedDeviceIndex;
}

QStringList SttAudioController::processNames() const {
    return m_processNames;
}

int SttAudioController::selectedProcessIndex() const {
    return m_selectedProcessIndex;
}

QString SttAudioController::statusText() const {
    return m_statusText;
}

QString SttAudioController::transcriptText() const {
    return m_transcriptText;
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

void SttAudioController::refreshProcesses() {
    rebuildProcessList();
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
    setAudioLevel(0.0);
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

    file.write("localTime,receivedAt,phase,text,intent,confidence,instruction,consumed,triggered,routed,routeDurationMs,reason\n");
    for (const QString &row : m_debugCsvRows) {
        file.write(row.toUtf8());
        file.write("\n");
    }
    setLastDebugExportPath(filePath);
    setStatusText(QStringLiteral("Voice debug CSV exported: %1").arg(filePath));
}

void SttAudioController::setSourceMode(int value) {
    const int normalized = value == ProcessAudio ? ProcessAudio : Microphone;
    if (m_sourceMode == normalized) {
        return;
    }
    const bool wasRunning = m_running;
    if (wasRunning) {
        stop();
    }
    m_sourceMode = normalized;
    emit sourceModeChanged();
    if (m_sourceMode == ProcessAudio) {
        rebuildProcessList();
    } else {
        rebuildDeviceList();
    }
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
    emit selectedDeviceIndexChanged();
}

void SttAudioController::setSelectedProcessIndex(int value) {
    const int maxIndex = qMax(0, m_processes.size() - 1);
    const int normalized = qBound(0, value, maxIndex);
    if (m_selectedProcessIndex == normalized) {
        return;
    }
    const bool wasRunning = m_running;
    if (wasRunning) {
        stop();
    }
    m_selectedProcessIndex = normalized;
    emit selectedProcessIndexChanged();
}

void SttAudioController::rebuildDeviceList() {
    QList<QAudioDevice> microphoneCandidates;
    for (const QAudioDevice &device : m_allDevices) {
        if (!isSystemAudioCandidate(device)) {
            microphoneCandidates.append(device);
        }
    }
    m_filteredDevices = microphoneCandidates.isEmpty() ? m_allDevices : microphoneCandidates;

    QStringList nextNames;
    for (const QAudioDevice &device : m_filteredDevices) {
        nextNames.append(device.description());
    }
    if (nextNames.isEmpty()) {
        nextNames.append(QStringLiteral("No microphone devices"));
    }

    m_deviceNames = nextNames;
    if (m_selectedDeviceIndex >= m_filteredDevices.size()) {
        m_selectedDeviceIndex = 0;
        emit selectedDeviceIndexChanged();
    }
    emit devicesChanged();

    if (!m_running) {
        setStatusText(m_filteredDevices.isEmpty()
                          ? QStringLiteral("No microphone input device is available.")
                          : QStringLiteral("Voice input ready."));
    }
}

void SttAudioController::rebuildProcessList() {
    m_processes = WindowsProcessAudioCapture::enumerateProcesses();

    QStringList nextNames;
    for (const auto &process : m_processes) {
        nextNames.append(process.label);
    }
    if (nextNames.isEmpty()) {
        nextNames.append(QStringLiteral("No Windows processes"));
    }

    m_processNames = nextNames;
    if (m_selectedProcessIndex >= m_processes.size()) {
        m_selectedProcessIndex = 0;
        emit selectedProcessIndexChanged();
    }
    emit processesChanged();

    if (!m_running && m_sourceMode == ProcessAudio) {
        setStatusText(m_processes.isEmpty()
                          ? QStringLiteral("No process is available for audio capture.")
                          : QStringLiteral("Select the voice app process to capture."));
    }
}

bool SttAudioController::beginAudioCapture() {
    if (m_sourceMode == ProcessAudio) {
        return beginProcessAudioCapture();
    }
    return beginDeviceAudioCapture();
}

bool SttAudioController::beginDeviceAudioCapture() {
    if (m_filteredDevices.isEmpty()) {
        setStatusText(QStringLiteral("No microphone input device is available."));
        return false;
    }

    const QAudioDevice device = selectedDevice();
    if (device.isNull()) {
        setStatusText(QStringLiteral("Selected microphone is unavailable."));
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
        setStatusText(QStringLiteral("Could not start microphone input."));
        return false;
    }

    connect(m_audioDevice, &QIODevice::readyRead, this, &SttAudioController::handleAudioReadyRead);
    return true;
}

bool SttAudioController::beginProcessAudioCapture() {
    if (m_processes.isEmpty()) {
        rebuildProcessList();
    }
    const quint32 processId = selectedProcessId();
    if (processId == 0) {
        setStatusText(QStringLiteral("Select the voice app process to capture."));
        return false;
    }

    QString errorMessage;
    if (!m_processCapture->start(processId, &errorMessage)) {
        setStatusText(errorMessage.isEmpty() ? QStringLiteral("Could not start process audio capture.") : errorMessage);
        return false;
    }
    return true;
}

void SttAudioController::stopAudioCapture() {
    if (m_audioSource != nullptr) {
        m_audioSource->stop();
        m_audioSource->deleteLater();
        m_audioSource = nullptr;
    }
    m_audioDevice = nullptr;
    if (m_processCapture != nullptr && m_processCapture->running()) {
        m_processCapture->stop();
    }
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
    setBackendConnected(false);
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
    setBackendConnected(false);
    m_socketBuffer.clear();
}

void SttAudioController::sendSttConfig() {
    if (m_settings == nullptr) {
        return;
    }
    QJsonObject config;
    config.insert(QStringLiteral("rpSessionId"), m_settings->sessionId());
    config.insert(QStringLiteral("characterName"), m_settings->characterName());
    config.insert(QStringLiteral("debugOnly"), m_settings->voiceDebugEnabled());
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

void SttAudioController::handleProcessPcmReady(const QByteArray &pcm, qreal level) {
    setAudioLevel(level);
    sendPcmToBackend(pcm);
}

void SttAudioController::handleProcessCaptureStopped(const QString &errorMessage) {
    if (!errorMessage.isEmpty() && !m_stopRequested) {
        if (m_processCapture != nullptr) {
            m_processCapture->stop();
        }
        setRunning(false);
        setAudioLevel(0.0);
        closeSocket();
        setStatusText(errorMessage);
    }
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
        setBackendConnected(true);
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
            handleTextMessage(QString::fromUtf8(payload));
        } else if (opcode == 0x8) {
            closeSocket();
            return;
        } else if (opcode == 0x9) {
            sendFrame(0xA, payload);
        }
    }
}

void SttAudioController::handleTextMessage(const QString &message) {
    const QJsonDocument document = QJsonDocument::fromJson(message.toUtf8());
    if (!document.isObject()) {
        return;
    }

    const QJsonObject object = document.object();
    const QString type = object.value(QStringLiteral("type")).toString();
    if (type == QStringLiteral("result")) {
        const QString text = object.value(QStringLiteral("text")).toString().trimmed();
        const bool finalResult = object.value(QStringLiteral("final")).toBool(false);
        if (!text.isEmpty()) {
            setTranscriptText(text);
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
    }
    if (type == QStringLiteral("error")) {
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
    sendBinaryFrame(payload);
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
    const QString receivedAt = object.value(QStringLiteral("receivedAt")).toString();
    const bool finalResult = object.value(QStringLiteral("final")).toBool(false);
    const QString phase = finalResult ? QStringLiteral("FINAL") : QStringLiteral("PARTIAL");
    const QString text = object.value(QStringLiteral("text")).toString().trimmed();
    const QString intent = object.value(QStringLiteral("intent")).toString(QStringLiteral("CHAT"));
    const double confidence = object.value(QStringLiteral("confidence")).toDouble(0.0);
    const QString instruction = object.value(QStringLiteral("instruction")).toString().trimmed();
    const bool consumed = object.value(QStringLiteral("consumed")).toBool(false);
    const bool triggered = object.value(QStringLiteral("triggered")).toBool(false);
    const bool routed = object.value(QStringLiteral("routed")).toBool(false);
    const int routeDurationMs = object.value(QStringLiteral("routeDurationMs")).toInt(0);
    const QString reason = object.value(QStringLiteral("reason")).toString();

    const QString summary = QStringLiteral("%1  %2  %3  conf=%4  route=%5ms  consumed=%6  %7")
            .arg(localTime,
                 phase,
                 intent,
                 QString::number(confidence, 'f', 2),
                 QString::number(routeDurationMs),
                 consumed ? QStringLiteral("yes") : QStringLiteral("no"),
                 text);
    m_debugEvents.prepend(summary);
    while (m_debugEvents.size() > 200) {
        m_debugEvents.removeLast();
    }

    m_debugCsvRows.append(QStringList{
        csvEscape(localTime),
        csvEscape(receivedAt),
        csvEscape(phase),
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

    emit debugEventsChanged();
}

void SttAudioController::setLastDebugExportPath(const QString &value) {
    if (m_lastDebugExportPath == value) {
        return;
    }
    m_lastDebugExportPath = value;
    emit lastDebugExportPathChanged();
}

QString SttAudioController::localListeningStatus() const {
    if (m_sourceMode == ProcessAudio) {
        const int index = qBound(0, m_selectedProcessIndex, m_processes.size() - 1);
        if (!m_processes.isEmpty()) {
            return QStringLiteral("Local preview: %1.").arg(m_processes.at(index).label);
        }
        return QStringLiteral("Local preview: process audio.");
    }
    return QStringLiteral("Local preview: microphone.");
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
    double energy = 0.0;
    for (int frame = 0; frame < sourceFrames; ++frame) {
        const char *frameStart = raw.constData() + frame * sourceBytesPerFrame;
        float mixed = 0.0f;
        for (int channel = 0; channel < channels; ++channel) {
            mixed += sampleAsFloat(frameStart + channel * sourceBytesPerSample, m_captureFormat.sampleFormat());
        }
        mixed /= static_cast<float>(channels);
        mono.append(mixed);
        energy += mixed * mixed;
    }

    setAudioLevel(qSqrt(energy / qMax(1, sourceFrames)) * 4.0);

    const int targetFrames = qMax(1, static_cast<int>(std::llround(sourceFrames * (TargetSampleRate / static_cast<double>(sourceRate)))));
    QByteArray output;
    output.resize(targetFrames * TargetBytesPerSample);
    uchar *out = reinterpret_cast<uchar *>(output.data());
    for (int i = 0; i < targetFrames; ++i) {
        const int sourceIndex = qMin(sourceFrames - 1, static_cast<int>(std::floor(i * (sourceRate / static_cast<double>(TargetSampleRate)))));
        qToLittleEndian<qint16>(floatToPcm16(mono.at(sourceIndex)), out + i * TargetBytesPerSample);
    }
    return output;
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
    const int index = qBound(0, m_selectedDeviceIndex, m_filteredDevices.size() - 1);
    return m_filteredDevices.at(index);
}

quint32 SttAudioController::selectedProcessId() const {
    if (m_processes.isEmpty()) {
        return 0;
    }
    const int index = qBound(0, m_selectedProcessIndex, m_processes.size() - 1);
    return m_processes.at(index).processId;
}

bool SttAudioController::isSystemAudioCandidate(const QAudioDevice &device) const {
    const QString name = device.description().toLower();
    const QStringList keywords{
        QStringLiteral("stereo mix"),
        QStringLiteral("what u hear"),
        QStringLiteral("loopback"),
        QStringLiteral("monitor"),
        QStringLiteral("cable"),
        QStringLiteral("voicemeeter"),
        QStringLiteral("sonar"),
        QStringLiteral("virtual"),
        QStringLiteral("wave out"),
        QStringLiteral("立体声混音"),
        QStringLiteral("混音"),
        QStringLiteral("虚拟")
    };
    return std::any_of(keywords.cbegin(), keywords.cend(), [&name](const QString &keyword) {
        return name.contains(keyword);
    });
}
