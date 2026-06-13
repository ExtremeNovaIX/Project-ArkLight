#pragma once

#include <QAudioDevice>
#include <QAudioFormat>
#include <QByteArray>
#include <QJsonObject>
#include <QList>
#include <QMap>
#include <QNetworkReply>
#include <QObject>
#include <QTimer>
#include <QUrl>
#include <QVector>

class FrontendSettings;
class QAudioSink;
class QIODevice;
class QNetworkAccessManager;

class TtsAudioController final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool connected READ connected NOTIFY connectedChanged)
    Q_PROPERTY(QString statusText READ statusText NOTIFY statusTextChanged)

public:
    explicit TtsAudioController(FrontendSettings *settings, QObject *parent = nullptr);
    ~TtsAudioController() override;

    bool connected() const;
    QString statusText() const;

public slots:
    void refreshSubscription();

signals:
    void connectedChanged();
    void statusTextChanged();

private:
    struct DecodedAudio {
        QVector<float> samples;
        int sampleRate = 0;
        int channels = 0;
    };

    void openLiveAudio(const QString &baseUrl, const QString &sessionId);
    void closeLiveAudio();
    void handleReadyRead();
    void handleFinished();
    void parseSseFrames();
    void handleSseFrame(const QByteArray &frame);
    void handleTtsAudioMessage(const QJsonObject &object);
    void drainOrderedTtsAudioMessages();
    void processTtsAudioMessage(const QJsonObject &object);
    void enqueueAudio(const QByteArray &audioBytes, const QString &mediaType, int sampleRate);
    void drainPlaybackQueue();
    bool ensureOutputFormat();
    bool ensurePlaybackSink();
    void resetPlayback();
    void setConnected(bool value);
    void setStatusText(const QString &value);
    QString normalizedBaseUrl() const;
    QString currentSessionId() const;
    QUrl liveUrl(const QString &baseUrl, const QString &sessionId) const;
    DecodedAudio decodeAudio(const QByteArray &audioBytes, const QString &mediaType, int sampleRate) const;
    DecodedAudio decodeWav(const QByteArray &audioBytes) const;
    DecodedAudio decodeRawPcm(const QByteArray &audioBytes, const QString &mediaType, int sampleRate) const;
    QVector<float> toMonoAndResample(const DecodedAudio &audio) const;
    void appendInterChunkSilence();
    void appendPlaybackSamples(QVector<float> samples);
    void applyFadeIn(QVector<float> &samples) const;
    void applyFadeOutToQueue();
    QByteArray encodeQueuedFrames(qsizetype frameCount) const;
    qsizetype startupBufferFrames() const;
    qsizetype fadeFrames() const;

    FrontendSettings *m_settings = nullptr;
    QNetworkAccessManager *m_network = nullptr;
    QNetworkReply *m_reply = nullptr;
    QByteArray m_sseBuffer;
    QTimer m_reconnectTimer;
    QTimer m_drainTimer;
    QAudioSink *m_sink = nullptr;
    QIODevice *m_sinkDevice = nullptr;
    QAudioFormat m_outputFormat;
    QVector<float> m_playbackQueue;
    QMap<qint64, QJsonObject> m_pendingAudioMessages;
    qint64 m_nextPlaybackSequence = 0;
    bool m_playbackStarted = false;
    bool m_flushRequested = false;
    bool m_audioChunkQueued = false;
    bool m_connected = false;
    QString m_statusText;
    QString m_liveBaseUrl;
    QString m_liveSessionId;
};
