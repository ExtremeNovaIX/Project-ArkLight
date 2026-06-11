#pragma once

#include "WindowsProcessAudioCapture.h"

#include <QAudioDevice>
#include <QAudioFormat>
#include <QList>
#include <QMediaDevices>
#include <QObject>
#include <QStringList>
#include <QTcpSocket>
#include <QUrl>

class FrontendSettings;
class QAudioSource;
class QIODevice;
class QJsonObject;

class SttAudioController final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool running READ running NOTIFY runningChanged)
    Q_PROPERTY(bool backendConnected READ backendConnected NOTIFY backendConnectedChanged)
    Q_PROPERTY(int sourceMode READ sourceMode WRITE setSourceMode NOTIFY sourceModeChanged)
    Q_PROPERTY(QStringList deviceNames READ deviceNames NOTIFY devicesChanged)
    Q_PROPERTY(int selectedDeviceIndex READ selectedDeviceIndex WRITE setSelectedDeviceIndex NOTIFY selectedDeviceIndexChanged)
    Q_PROPERTY(QStringList processNames READ processNames NOTIFY processesChanged)
    Q_PROPERTY(int selectedProcessIndex READ selectedProcessIndex WRITE setSelectedProcessIndex NOTIFY selectedProcessIndexChanged)
    Q_PROPERTY(QString statusText READ statusText NOTIFY statusTextChanged)
    Q_PROPERTY(QString transcriptText READ transcriptText NOTIFY transcriptTextChanged)
    Q_PROPERTY(qreal audioLevel READ audioLevel NOTIFY audioLevelChanged)
    Q_PROPERTY(QStringList debugEvents READ debugEvents NOTIFY debugEventsChanged)
    Q_PROPERTY(int debugEventCount READ debugEventCount NOTIFY debugEventsChanged)
    Q_PROPERTY(QString lastDebugExportPath READ lastDebugExportPath NOTIFY lastDebugExportPathChanged)

public:
    enum SourceMode {
        Microphone = 0,
        ProcessAudio = 1
    };
    Q_ENUM(SourceMode)

    explicit SttAudioController(FrontendSettings *settings, QObject *parent = nullptr);
    ~SttAudioController() override;

    bool running() const;
    bool backendConnected() const;
    int sourceMode() const;
    QStringList deviceNames() const;
    int selectedDeviceIndex() const;
    QStringList processNames() const;
    int selectedProcessIndex() const;
    QString statusText() const;
    QString transcriptText() const;
    qreal audioLevel() const;
    QStringList debugEvents() const;
    int debugEventCount() const;
    QString lastDebugExportPath() const;

    Q_INVOKABLE void refreshDevices();
    Q_INVOKABLE void refreshProcesses();
    Q_INVOKABLE void start();
    Q_INVOKABLE void stop();
    Q_INVOKABLE void clearDebugEvents();
    Q_INVOKABLE void exportDebugEvents();

public slots:
    void setSourceMode(int value);
    void setSelectedDeviceIndex(int value);
    void setSelectedProcessIndex(int value);

signals:
    void runningChanged();
    void backendConnectedChanged();
    void sourceModeChanged();
    void devicesChanged();
    void selectedDeviceIndexChanged();
    void processesChanged();
    void selectedProcessIndexChanged();
    void statusTextChanged();
    void transcriptTextChanged();
    void audioLevelChanged();
    void debugEventsChanged();
    void lastDebugExportPathChanged();

private:
    void rebuildDeviceList();
    void rebuildProcessList();
    bool beginAudioCapture();
    bool beginDeviceAudioCapture();
    bool beginProcessAudioCapture();
    void stopAudioCapture();
    void connectBackend();
    void closeSocket();
    void sendSttConfig();
    void handleAudioReadyRead();
    void handleProcessPcmReady(const QByteArray &pcm, qreal level);
    void handleProcessCaptureStopped(const QString &errorMessage);
    void handleSocketConnected();
    void handleSocketReadyRead();
    void parseSocketFrames();
    void handleTextMessage(const QString &message);
    void handleDebugMessage(const QJsonObject &object);
    void sendTextFrame(const QString &message);
    void sendBinaryFrame(const QByteArray &payload);
    void sendPcmToBackend(const QByteArray &payload);
    void sendFrame(quint8 opcode, const QByteArray &payload);
    void setRunning(bool value);
    void setBackendConnected(bool value);
    void setStatusText(const QString &value);
    void setTranscriptText(const QString &value);
    void setAudioLevel(qreal value);
    void appendDebugEvent(const QJsonObject &object);
    void setLastDebugExportPath(const QString &value);
    QString localListeningStatus() const;
    QAudioFormat captureFormatFor(const QAudioDevice &device) const;
    QByteArray convertToPcm16Mono16k(const QByteArray &raw);
    QUrl sttWebSocketUrl() const;
    QAudioDevice selectedDevice() const;
    quint32 selectedProcessId() const;
    bool isSystemAudioCandidate(const QAudioDevice &device) const;

    FrontendSettings *m_settings = nullptr;
    QMediaDevices m_mediaDevices;
    QTcpSocket m_socket;
    QUrl m_socketUrl;
    QByteArray m_socketBuffer;
    QList<QAudioDevice> m_allDevices;
    QList<QAudioDevice> m_filteredDevices;
    QStringList m_deviceNames;
    QList<WindowsProcessAudioCapture::ProcessInfo> m_processes;
    QStringList m_processNames;
    QAudioSource *m_audioSource = nullptr;
    QIODevice *m_audioDevice = nullptr;
    WindowsProcessAudioCapture *m_processCapture = nullptr;
    QAudioFormat m_captureFormat;
    bool m_running = false;
    bool m_stopRequested = false;
    bool m_backendConnected = false;
    bool m_handshakeComplete = false;
    int m_sourceMode = Microphone;
    int m_selectedDeviceIndex = 0;
    int m_selectedProcessIndex = 0;
    QString m_statusText;
    QString m_transcriptText;
    qreal m_audioLevel = 0.0;
    QStringList m_debugEvents;
    QStringList m_debugCsvRows;
    QString m_lastDebugExportPath;
};
