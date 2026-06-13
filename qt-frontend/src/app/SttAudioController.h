#pragma once

#include <QAudioDevice>
#include <QAudioFormat>
#include <QList>
#include <QMediaDevices>
#include <QObject>
#include <QStringList>
#include <QTcpSocket>
#include <QUrl>
#include <QtGlobal>

class FrontendSettings;
class QAudioSource;
class QIODevice;
class QJsonObject;

class SttAudioController final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool running READ running NOTIFY runningChanged)
    Q_PROPERTY(bool backendConnected READ backendConnected NOTIFY backendConnectedChanged)
    Q_PROPERTY(QStringList deviceNames READ deviceNames NOTIFY devicesChanged)
    Q_PROPERTY(int selectedDeviceIndex READ selectedDeviceIndex WRITE setSelectedDeviceIndex NOTIFY selectedDeviceIndexChanged)
    Q_PROPERTY(QString statusText READ statusText NOTIFY statusTextChanged)
    Q_PROPERTY(QString transcriptText READ transcriptText NOTIFY transcriptTextChanged)
    Q_PROPERTY(QString partialTranscriptText READ partialTranscriptText NOTIFY transcriptTextChanged)
    Q_PROPERTY(QString finalTranscriptText READ finalTranscriptText NOTIFY transcriptTextChanged)
    Q_PROPERTY(qreal audioLevel READ audioLevel NOTIFY audioLevelChanged)
    Q_PROPERTY(QStringList debugEvents READ debugEvents NOTIFY debugEventsChanged)
    Q_PROPERTY(int debugEventCount READ debugEventCount NOTIFY debugEventsChanged)
    Q_PROPERTY(QString lastDebugExportPath READ lastDebugExportPath NOTIFY lastDebugExportPathChanged)

public:
    explicit SttAudioController(FrontendSettings *settings, QObject *parent = nullptr);
    ~SttAudioController() override;

    bool running() const;
    bool backendConnected() const;
    QStringList deviceNames() const;
    int selectedDeviceIndex() const;
    QString statusText() const;
    QString transcriptText() const;
    QString partialTranscriptText() const;
    QString finalTranscriptText() const;
    qreal audioLevel() const;
    QStringList debugEvents() const;
    int debugEventCount() const;
    QString lastDebugExportPath() const;

    Q_INVOKABLE void refreshDevices();
    Q_INVOKABLE void start();
    Q_INVOKABLE void stop();
    Q_INVOKABLE void clearDebugEvents();
    Q_INVOKABLE void exportDebugEvents();

public slots:
    void setSelectedDeviceIndex(int value);

signals:
    void runningChanged();
    void backendConnectedChanged();
    void devicesChanged();
    void selectedDeviceIndexChanged();
    void statusTextChanged();
    void transcriptTextChanged();
    void audioLevelChanged();
    void debugEventsChanged();
    void lastDebugExportPathChanged();

private:
    void rebuildDeviceList();
    bool beginAudioCapture();
    bool beginDeviceAudioCapture();
    void stopAudioCapture();
    void connectBackend();
    void closeSocket();
    void sendSttConfig();
    void handleAudioReadyRead();
    void handleSocketConnected();
    void handleSocketReadyRead();
    void parseSocketFrames();
    void handleTextMessage(const QString &message, const QString &streamName);
    void handleDebugMessage(const QJsonObject &object);
    void sendTextFrame(const QString &message);
    void sendBinaryFrame(const QByteArray &payload);
    void sendPcmToBackend(const QByteArray &payload);
    void flushPendingPcm(bool force);
    void sendFrame(quint8 opcode, const QByteArray &payload);
    void setRunning(bool value);
    void setBackendConnected(bool value);
    void updateBackendConnected();
    void setStatusText(const QString &value);
    void setTranscriptText(const QString &value);
    void setPartialTranscriptText(const QString &value);
    void setFinalTranscriptText(const QString &value);
    void setAudioLevel(qreal value);
    void appendDebugEvent(const QJsonObject &object);
    void setLastDebugExportPath(const QString &value);
    void resetVoiceBandFilter();
    QString localListeningStatus() const;
    QAudioFormat captureFormatFor(const QAudioDevice &device) const;
    QByteArray convertToPcm16Mono16k(const QByteArray &raw);
    QUrl sttWebSocketUrl() const;
    QAudioDevice selectedDevice() const;
    QString deviceId(const QAudioDevice &device) const;

    FrontendSettings *m_settings = nullptr;
    QMediaDevices m_mediaDevices;
    QTcpSocket m_socket;
    QUrl m_socketUrl;
    QByteArray m_socketBuffer;
    QList<QAudioDevice> m_allDevices;
    QList<QAudioDevice> m_filteredDevices;
    QStringList m_deviceNames;
    QAudioSource *m_audioSource = nullptr;
    QIODevice *m_audioDevice = nullptr;
    QAudioFormat m_captureFormat;
    bool m_running = false;
    bool m_stopRequested = false;
    bool m_backendConnected = false;
    bool m_primaryBackendConnected = false;
    bool m_handshakeComplete = false;
    int m_selectedDeviceIndex = 0;
    QString m_selectedDeviceId;
    QByteArray m_pendingPcm;
    QString m_statusText;
    QString m_transcriptText;
    QString m_partialTranscriptText;
    QString m_finalTranscriptText;
    qreal m_audioLevel = 0.0;
    QStringList m_debugEvents;
    QStringList m_debugCsvRows;
    QString m_lastDebugSignature;
    qint64 m_lastDebugEventAt = 0;
    QString m_lastDebugExportPath;
    double m_voiceBandHighPassPreviousInput = 0.0;
    double m_voiceBandHighPassPreviousOutput = 0.0;
    double m_voiceBandLowPassPreviousOutput = 0.0;
};
