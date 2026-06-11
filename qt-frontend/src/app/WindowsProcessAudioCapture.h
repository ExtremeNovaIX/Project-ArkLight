#pragma once

#include <QByteArray>
#include <QList>
#include <QObject>
#include <QString>

#include <atomic>
#include <thread>

class WindowsProcessAudioCapture final : public QObject {
    Q_OBJECT

public:
    struct ProcessInfo {
        quint32 processId = 0;
        QString executableName;
        QString label;
    };

    explicit WindowsProcessAudioCapture(QObject *parent = nullptr);
    ~WindowsProcessAudioCapture() override;

    static QList<ProcessInfo> enumerateProcesses();

    bool running() const;
    bool start(quint32 processId, QString *errorMessage);
    void stop();

signals:
    void pcmReady(const QByteArray &pcm, qreal level);
    void stopped(const QString &errorMessage);

private:
    void captureLoop(quint32 processId);

    std::atomic_bool m_stopRequested{false};
    std::thread m_thread;
};
