#include "ChatClient.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QUrl>
#include <QUrlQuery>

namespace {

QString extractSegmentText(const QJsonValue &value) {
    // 后端可能直接返回字符串数组：[ "hello", "world" ]。
    // The backend may return a plain string array: [ "hello", "world" ].
    if (value.isString()) {
        return value.toString().trimmed();
    }

    // 如果不是字符串也不是对象，就不是我们能显示的聊天片段。
    // If it is neither a string nor an object, it is not a displayable chat segment.
    if (!value.isObject()) {
        return QString();
    }

    const QJsonObject object = value.toObject();

    // 兼容几种常见字段名，降低前后端返回格式变动带来的影响。
    // Accept several common field names to tolerate small backend response shape changes.
    const QStringList preferredKeys{
        QStringLiteral("content"),
        QStringLiteral("text"),
        QStringLiteral("message"),
        QStringLiteral("sentence")
    };

    // 优先读取语义明确的字段。
    // Prefer fields with clear semantic meaning.
    for (const QString &key : preferredKeys) {
        const QJsonValue field = object.value(key);
        if (field.isString()) {
            return field.toString().trimmed();
        }
    }

    // 如果没有命中常见字段，退一步取第一个字符串字段。
    // If no common field exists, fall back to the first string field.
    for (auto it = object.begin(); it != object.end(); ++it) {
        if (it.value().isString()) {
            return it.value().toString().trimmed();
        }
    }

    return QString();
}

} // namespace

ChatClient::ChatClient(QObject *parent)
    : QObject(parent)
    // parent-child 机制让 Qt 自动释放 m_network，不需要手写 delete。
    // Qt's parent-child ownership deletes m_network automatically; no manual delete needed.
    , m_network(new QNetworkAccessManager(this)) {
}

QString errorMessageFromBody(const QByteArray &body, const QString &fallback) {
    QJsonParseError parseError;
    const QJsonDocument document = QJsonDocument::fromJson(body, &parseError);
    if (parseError.error == QJsonParseError::NoError && document.isObject()) {
        const QJsonObject object = document.object();
        const QJsonValue message = object.value(QStringLiteral("message"));
        if (message.isString() && !message.toString().trimmed().isEmpty()) {
            return message.toString().trimmed();
        }
        const QJsonValue error = object.value(QStringLiteral("error"));
        if (error.isString() && !error.toString().trimmed().isEmpty()) {
            return error.toString().trimmed();
        }
    }

    const QString rawText = QString::fromUtf8(body).trimmed();
    return rawText.isEmpty() ? fallback : rawText;
}

QStringList liveSegmentsFromData(const QByteArray &data) {
    QJsonParseError parseError;
    const QJsonDocument document = QJsonDocument::fromJson(data, &parseError);
    if (parseError.error != QJsonParseError::NoError || !document.isObject()) {
        return {};
    }

    const QJsonObject object = document.object();
    QStringList segments;
    const QJsonValue reply = object.value(QStringLiteral("reply"));
    if (reply.isArray()) {
        const QJsonArray replyArray = reply.toArray();
        for (const QJsonValue &value : replyArray) {
            const QString segment = extractSegmentText(value);
            if (!segment.isEmpty()) {
                segments.append(segment);
            }
        }
    }
    if (!segments.isEmpty()) {
        return segments;
    }

    const QString content = object.value(QStringLiteral("content")).toString().trimmed();
    return content.isEmpty() ? QStringList{} : QStringList{content};
}

void ChatClient::sendMessage(const QString &baseUrl,
                             const QString &message,
                             const QString &sessionId,
                             const QString &characterName,
                             bool shortMode) {
    // 规范化 baseUrl，方便之后拼接固定 API 路径。
    // Normalize baseUrl before appending the fixed API path.
    QString normalizedBaseUrl = baseUrl.trimmed();
    while (normalizedBaseUrl.endsWith('/')) {
        normalizedBaseUrl.chop(1);
    }

    // 配置错误直接用信号返回给控制器；不要继续创建无效请求。
    // Report configuration errors via signal and avoid creating invalid requests.
    if (normalizedBaseUrl.isEmpty()) {
        emit requestFailed(QStringLiteral("Backend base URL is empty."));
        return;
    }

    const QUrl url(normalizedBaseUrl + QStringLiteral("/api/chat/send"));
    if (!url.isValid()) {
        emit requestFailed(QStringLiteral("Backend URL is invalid."));
        return;
    }

    // QNetworkRequest 保存 URL、Header 等请求元数据。
    // QNetworkRequest stores request metadata such as URL and headers.
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));

    // 这里的字段名要和 Spring 后端 ChatRequestDTO 对齐。
    // These field names should match the Spring backend ChatRequestDTO.
    QJsonObject payload;
    payload.insert(QStringLiteral("message"), message);
    payload.insert(QStringLiteral("sessionId"), sessionId);
    payload.insert(QStringLiteral("characterName"), characterName);
    payload.insert(QStringLiteral("shortMode"), shortMode);

    // post() 立即返回 QNetworkReply；真正完成时会发 finished 信号。
    // post() returns QNetworkReply immediately; finished is emitted when the request completes.
    QNetworkReply *reply = m_network->post(request, QJsonDocument(payload).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        handleReply(reply);
    });
}

void ChatClient::openLiveMessages(const QString &baseUrl,
                                  const QString &sessionId,
                                  const QString &characterName,
                                  bool shortMode) {
    QString normalizedBaseUrl = baseUrl.trimmed();
    while (normalizedBaseUrl.endsWith('/')) {
        normalizedBaseUrl.chop(1);
    }

    if (m_liveReply != nullptr) {
        QNetworkReply *previousReply = m_liveReply;
        m_liveReply = nullptr;
        QObject::disconnect(previousReply, nullptr, this, nullptr);
        previousReply->abort();
        previousReply->deleteLater();
    }
    m_liveBuffer.clear();

    if (normalizedBaseUrl.isEmpty()) {
        return;
    }

    QUrl url(normalizedBaseUrl + QStringLiteral("/api/chat/live"));
    if (!url.isValid()) {
        return;
    }
    QUrlQuery query;
    query.addQueryItem(QStringLiteral("sessionId"), sessionId);
    query.addQueryItem(QStringLiteral("characterName"), characterName);
    query.addQueryItem(QStringLiteral("shortMode"), shortMode ? QStringLiteral("true") : QStringLiteral("false"));
    url.setQuery(query);

    QNetworkRequest request(url);
    request.setRawHeader("Accept", "text/event-stream");
    m_liveReply = m_network->get(request);
    QNetworkReply *reply = m_liveReply;
    connect(reply, &QNetworkReply::readyRead, this, [this, reply]() {
        if (reply == m_liveReply) {
            handleLiveReadyRead(reply);
        }
    });
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        handleLiveFinished(reply);
    });
}

void ChatClient::startStoryReplay(const QString &baseUrl,
                                  const QString &sessionId,
                                  const QString &characterName,
                                  int targetLength) {
    QString normalizedBaseUrl = baseUrl.trimmed();
    while (normalizedBaseUrl.endsWith('/')) {
        normalizedBaseUrl.chop(1);
    }

    if (normalizedBaseUrl.isEmpty()) {
        emit storyReplayFailed(QStringLiteral("Backend base URL is empty."));
        return;
    }

    const QUrl url(normalizedBaseUrl + QStringLiteral("/api/test/story-replay/start"));
    if (!url.isValid()) {
        emit storyReplayFailed(QStringLiteral("Backend URL is invalid."));
        return;
    }

    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));

    QJsonObject payload;
    payload.insert(QStringLiteral("sessionId"), sessionId);
    payload.insert(QStringLiteral("characterName"), characterName);
    payload.insert(QStringLiteral("targetLength"), targetLength);

    QNetworkReply *reply = m_network->post(request, QJsonDocument(payload).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        handleStoryReplayReply(reply);
    });
}

void ChatClient::handleReply(QNetworkReply *reply) {
    // readAll() 只能可靠读取一次，所以先保存响应体。
    // readAll() should be treated as one-shot, so store the response body first.
    const QByteArray body = reply->readAll();

    // Qt 把网络错误和 HTTP 层错误都通过 error() 暴露。
    // Qt exposes network and many HTTP-level failures through error().
    if (reply->error() != QNetworkReply::NoError) {
        emit requestFailed(errorMessageFromBody(body, reply->errorString()));
        reply->deleteLater();
        return;
    }

    QJsonParseError parseError;
    const QJsonDocument document = QJsonDocument::fromJson(body, &parseError);
    QStringList segments;

    // 支持后端直接返回数组。
    // Support a backend response that is directly an array.
    if (parseError.error == QJsonParseError::NoError && document.isArray()) {
        const QJsonArray array = document.array();
        for (const QJsonValue &value : array) {
            const QString segment = extractSegmentText(value);
            if (!segment.isEmpty()) {
                segments.append(segment);
            }
        }
    // 也支持返回对象，例如 { "reply": [...] } 或 { "message": "..." }。
    // Also support an object response, such as { "reply": [...] } or { "message": "..." }.
    } else if (parseError.error == QJsonParseError::NoError && document.isObject()) {
        const QJsonObject object = document.object();
        const QJsonValue replyField = object.value(QStringLiteral("reply"));

        if (replyField.isArray()) {
            const QJsonArray array = replyField.toArray();
            for (const QJsonValue &value : array) {
                const QString segment = extractSegmentText(value);
                if (!segment.isEmpty()) {
                    segments.append(segment);
                }
            }
        } else {
            const QString segment = extractSegmentText(replyField);
            if (!segment.isEmpty()) {
                segments.append(segment);
            }
        }

        // reply 字段没解析出内容时，再尝试 message 字段。
        // If reply produced no content, try the message field.
        if (segments.isEmpty()) {
            const QString messageText = extractSegmentText(object.value(QStringLiteral("message")));
            if (!messageText.isEmpty()) {
                segments.append(messageText);
            }
        }
    }

    // 如果不是 JSON，但响应体本身有文本，也显示出来，方便调试后端。
    // If the body is not JSON but contains text, display it to make backend debugging easier.
    if (segments.isEmpty()) {
        const QString rawText = QString::fromUtf8(body).trimmed();
        if (!rawText.isEmpty()) {
            segments.append(rawText);
        }
    }

    if (segments.isEmpty()) {
        emit requestFailed(QStringLiteral("Backend returned an empty reply."));
    } else {
        emit replyReady(segments);
    }

    // QNetworkReply 用 deleteLater()，让 Qt 在安全的事件循环时机释放它。
    // Use deleteLater() so Qt destroys QNetworkReply at a safe point in the event loop.
    reply->deleteLater();
}

void ChatClient::handleStoryReplayReply(QNetworkReply *reply) {
    const QByteArray body = reply->readAll();

    if (reply->error() != QNetworkReply::NoError) {
        emit storyReplayFailed(errorMessageFromBody(body, reply->errorString()));
        reply->deleteLater();
        return;
    }

    QJsonParseError parseError;
    const QJsonDocument document = QJsonDocument::fromJson(body, &parseError);
    if (parseError.error != QJsonParseError::NoError || !document.isObject()) {
        emit storyReplayFailed(QStringLiteral("Backend returned an invalid story replay response."));
        reply->deleteLater();
        return;
    }

    const QJsonObject object = document.object();
    const QString sessionId = object.value(QStringLiteral("sessionId")).toString();
    const QString sourcePath = object.value(QStringLiteral("sourcePath")).toString();
    const int chunkCount = object.value(QStringLiteral("chunkCount")).toInt();
    const int assistantCount = object.value(QStringLiteral("assistantMessageCount")).toInt();
    const int effectiveLength = object.value(QStringLiteral("effectiveSourceLength")).toInt();

    emit storyReplayReady(QStringLiteral("Story replay finished. session=%1, chunks=%2, replies=%3, length=%4, source=%5")
                              .arg(sessionId)
                              .arg(chunkCount)
                              .arg(assistantCount)
                              .arg(effectiveLength)
                              .arg(sourcePath));
    reply->deleteLater();
}

void ChatClient::handleLiveReadyRead(QNetworkReply *reply) {
    if (reply == nullptr || !reply->isReadable()) {
        return;
    }

    m_liveBuffer.append(reply->readAll());
    while (true) {
        int frameEnd = m_liveBuffer.indexOf("\n\n");
        int separatorLength = 2;
        if (frameEnd < 0) {
            frameEnd = m_liveBuffer.indexOf("\r\n\r\n");
            separatorLength = 4;
        }
        if (frameEnd < 0) {
            return;
        }

        const QByteArray frame = m_liveBuffer.left(frameEnd);
        m_liveBuffer.remove(0, frameEnd + separatorLength);
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

        if (eventName == "rp-message") {
            const QStringList segments = liveSegmentsFromData(data);
            if (!segments.isEmpty()) {
                emit liveReplyReady(segments);
            }
        }
    }
}

void ChatClient::handleLiveFinished(QNetworkReply *reply) {
    if (reply != m_liveReply) {
        reply->deleteLater();
        return;
    }

    if (reply->error() != QNetworkReply::OperationCanceledError) {
        handleLiveReadyRead(reply);
    }
    m_liveReply = nullptr;
    m_liveBuffer.clear();
    reply->deleteLater();
}
