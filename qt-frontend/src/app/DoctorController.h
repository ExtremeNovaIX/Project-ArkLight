#pragma once

#include <QNetworkAccessManager>
#include <QObject>
#include <QString>
#include <QVariantList>

class QNetworkReply;

class DoctorController final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool checking READ checking NOTIFY statusChanged)
    Q_PROPERTY(bool hasIssues READ hasIssues NOTIFY statusChanged)
    Q_PROPERTY(QString status READ status NOTIFY statusChanged)
    Q_PROPERTY(QString summary READ summary NOTIFY statusChanged)
    Q_PROPERTY(QString checkedAt READ checkedAt NOTIFY statusChanged)
    Q_PROPERTY(QVariantList issues READ issues NOTIFY statusChanged)

public:
    explicit DoctorController(QObject *parent = nullptr);

    bool checking() const;
    bool hasIssues() const;
    QString status() const;
    QString summary() const;
    QString checkedAt() const;
    QVariantList issues() const;

    Q_INVOKABLE void run(const QString &baseUrl);

signals:
    void statusChanged();

private:
    QString normalizedBaseUrl(const QString &baseUrl) const;
    void handleReply(QNetworkReply *reply);
    void setChecking(bool value);
    void setSnapshot(const QString &status, const QString &checkedAt, const QVariantList &issues);

    QNetworkAccessManager m_network;
    bool m_checking = false;
    QString m_status = QStringLiteral("UNKNOWN");
    QString m_summary;
    QString m_checkedAt;
    QVariantList m_issues;
};
