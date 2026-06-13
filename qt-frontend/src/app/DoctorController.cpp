#include "DoctorController.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QUrl>
#include <QVariantMap>

namespace {

QVariantList issuesFromChecks(const QJsonArray &checks) {
    QVariantList issues;
    for (const QJsonValue &value : checks) {
        const QJsonObject object = value.toObject();
        const QString status = object.value(QStringLiteral("status")).toString();
        if (status == QStringLiteral("OK")) {
            continue;
        }
        issues.append(object.toVariantMap());
    }
    return issues;
}

QVariantMap backendUnavailableIssue(const QString &message) {
    QVariantMap issue;
    issue.insert(QStringLiteral("id"), QStringLiteral("backend.unavailable"));
    issue.insert(QStringLiteral("label"), QStringLiteral("后端连接"));
    issue.insert(QStringLiteral("status"), QStringLiteral("ERROR"));
    issue.insert(QStringLiteral("detail"), QStringLiteral("无法连接后端 doctor 接口: %1").arg(message));
    issue.insert(QStringLiteral("action"), QStringLiteral("先启动 Java 后端，再重新打开 Qt 前端或刷新 doctor。"));
    issue.insert(QStringLiteral("path"), QString());
    return issue;
}

} // namespace

DoctorController::DoctorController(QObject *parent)
    : QObject(parent) {
}

bool DoctorController::checking() const {
    return m_checking;
}

bool DoctorController::hasIssues() const {
    return !m_issues.isEmpty();
}

QString DoctorController::status() const {
    return m_status;
}

QString DoctorController::summary() const {
    return m_summary;
}

QString DoctorController::checkedAt() const {
    return m_checkedAt;
}

QVariantList DoctorController::issues() const {
    return m_issues;
}

void DoctorController::run(const QString &baseUrl) {
    setChecking(true);

    QNetworkRequest request{QUrl(normalizedBaseUrl(baseUrl) + QStringLiteral("/api/doctor/status"))};
    request.setRawHeader("Accept", "application/json");
    request.setTransferTimeout(8000);

    QNetworkReply *reply = m_network.get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        handleReply(reply);
    });
}

QString DoctorController::normalizedBaseUrl(const QString &baseUrl) const {
    QString normalized = baseUrl.trimmed();
    while (normalized.endsWith('/')) {
        normalized.chop(1);
    }
    return normalized.isEmpty() ? QStringLiteral("http://localhost:8080") : normalized;
}

void DoctorController::handleReply(QNetworkReply *reply) {
    const QByteArray body = reply->readAll();
    if (reply->error() != QNetworkReply::NoError) {
        QVariantList issues;
        issues.append(backendUnavailableIssue(reply->errorString()));
        setSnapshot(QStringLiteral("ERROR"), QString(), issues);
        setChecking(false);
        reply->deleteLater();
        return;
    }

    QJsonParseError parseError;
    const QJsonDocument document = QJsonDocument::fromJson(body, &parseError);
    if (parseError.error != QJsonParseError::NoError || !document.isObject()) {
        QVariantList issues;
        issues.append(backendUnavailableIssue(QStringLiteral("doctor 返回了不可解析的响应。")));
        setSnapshot(QStringLiteral("ERROR"), QString(), issues);
        setChecking(false);
        reply->deleteLater();
        return;
    }

    const QJsonObject object = document.object();
    setSnapshot(
        object.value(QStringLiteral("status")).toString(QStringLiteral("UNKNOWN")),
        object.value(QStringLiteral("checkedAt")).toString(),
        issuesFromChecks(object.value(QStringLiteral("checks")).toArray()));
    setChecking(false);
    reply->deleteLater();
}

void DoctorController::setChecking(bool value) {
    if (m_checking == value) {
        return;
    }
    m_checking = value;
    emit statusChanged();
}

void DoctorController::setSnapshot(const QString &status, const QString &checkedAt, const QVariantList &issues) {
    m_status = status;
    m_checkedAt = checkedAt;
    m_issues = issues;
    if (m_issues.isEmpty()) {
        m_summary = QStringLiteral("运行时依赖检查通过。");
    } else {
        m_summary = QStringLiteral("运行时依赖检查发现 %1 项需要处理。").arg(m_issues.size());
    }
    emit statusChanged();
}
