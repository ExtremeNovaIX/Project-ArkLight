#include "ChatSessionController.h"

#include "CharacterCatalog.h"
#include "FrontendSettings.h"
#include "MessageParser.h"

#include <QDateTime>
#include <QRandomGenerator>
#include <QTimer>
#include <QtGlobal>

ChatSessionController::ChatSessionController(FrontendSettings *settings,
                                             CharacterCatalog *catalog,
                                             QObject *parent)
    : QObject(parent)
    , m_settings(settings)
    , m_catalog(catalog) {
    // 网络客户端返回文本段落后，交给控制器按“人类打字节奏”分批显示。
    // When the network client returns text segments, the controller displays them with human-like timing.
    connect(&m_chatClient, &ChatClient::replyReady, this, &ChatSessionController::scheduleAssistantSegments);
    connect(&m_chatClient, &ChatClient::liveReplyReady, this, &ChatSessionController::scheduleAssistantSegments);

    // 请求失败也写入聊天列表，这样用户能在界面上看到错误原因。
    // Request failures are appended to the chat list so the user can see the reason in the UI.
    connect(&m_chatClient, &ChatClient::requestFailed, this, [this](const QString &message) {
        appendLocalMessage(QStringLiteral("ai"), QStringLiteral("Request failed: %1").arg(message));
        setStatusText(message);
        setBusy(false);
    });

    connect(&m_chatClient, &ChatClient::storyReplayReady, this, [this](const QString &summary) {
        appendLocalMessage(QStringLiteral("ai"), summary);
        setStatusText(QStringLiteral("Story replay finished."));
        setBusy(false);
    });

    connect(&m_chatClient, &ChatClient::storyReplayFailed, this, [this](const QString &message) {
        appendLocalMessage(QStringLiteral("ai"), QStringLiteral("Story replay failed: %1").arg(message));
        setStatusText(message);
        setBusy(false);
    });

    // 角色目录刷新后，如果当前没有选中角色，就自动选择第一个可用角色。
    // After the character catalog reloads, auto-select the first character if none is selected.
    connect(m_catalog, &CharacterCatalog::charactersChanged, this, [this]() {
        if (m_settings->characterName().isEmpty()) {
            const QStringList names = m_catalog->characterNames();
            if (!names.isEmpty()) {
                m_settings->setCharacterName(names.first());
                m_settings->save();
            }
        }
        syncCharacterState();
    });

    // 设置变化时保存到本地，并同步当前角色立绘状态。
    // When settings change, save them locally and synchronize the active portrait state.
    connect(m_settings, &FrontendSettings::settingsChanged, this, [this]() {
        m_settings->save();
        syncCharacterState();
        refreshLiveMessages();
    });

    // 构造时也做一次默认角色选择，保证 QML 初始界面有合理状态。
    // Do the same default-character selection during construction for a sensible initial QML state.
    if (m_settings->characterName().isEmpty()) {
        const QStringList names = m_catalog->characterNames();
        if (!names.isEmpty()) {
            m_settings->setCharacterName(names.first());
            m_settings->save();
        }
    }

    syncCharacterState();
    refreshLiveMessages();
}

ChatMessageModel *ChatSessionController::messages() {
    // 返回模型指针给 QML；模型本身由控制器持有。
    // Return the model pointer to QML; the controller owns the model.
    return &m_messages;
}

bool ChatSessionController::busy() const {
    return m_busy;
}

QString ChatSessionController::statusText() const {
    return m_statusText;
}

QString ChatSessionController::activeEmotion() const {
    return m_activeEmotion;
}

QString ChatSessionController::activeCharacterImagePath() const {
    // 让角色目录根据“角色名 + 当前表情”决定实际图片 URL。
    // Let the catalog resolve the actual image URL from "character name + active emotion".
    return m_catalog->imageFor(m_settings->characterName(), m_activeEmotion);
}

void ChatSessionController::sendMessage(const QString &content) {
    // trim 后再判断，避免用户只输入空格也发请求。
    // Trim before validation so whitespace-only input is not sent.
    const QString trimmed = content.trimmed();
    if (trimmed.isEmpty() || m_busy) {
        return;
    }

    // 没有角色时不调用后端，因为后端需要 characterName 组织提示词。
    // Do not call the backend without a character because it needs characterName for prompting.
    if (m_settings->characterName().trimmed().isEmpty()) {
        appendLocalMessage(QStringLiteral("ai"), QStringLiteral("No character is selected. Load a character and try again."));
        setStatusText(QStringLiteral("Character selection is required before sending."));
        return;
    }

    // 先把用户消息加入本地模型，让界面立即反馈。
    // Add the user message locally first so the UI responds immediately.
    appendLocalMessage(QStringLiteral("user"), trimmed);
    setBusy(true);
    setStatusText(QStringLiteral("Waiting for backend reply..."));

    // 真正的网络调用由 ChatClient 负责；控制器只传入当前设置和消息。
    // ChatClient owns the real network call; the controller only passes current settings and text.
    m_chatClient.sendMessage(
        m_settings->backendBaseUrl(),
        trimmed,
        m_settings->sessionId(),
        m_settings->characterName(),
        m_settings->shortModeEnabled());
}

void ChatSessionController::reportTypingActivity() {
    // 输入变化不应进入聊天记忆；这里只上报一个短期交互占用心跳。
    if (m_settings == nullptr) {
        return;
    }
    m_chatClient.sendTypingActivity(
        m_settings->backendBaseUrl(),
        m_settings->sessionId());
}

void ChatSessionController::startStoryReplay() {
    if (m_busy) {
        return;
    }

    setBusy(true);
    setStatusText(QStringLiteral("Starting story replay..."));
    appendLocalMessage(QStringLiteral("ai"), QStringLiteral("Story replay started. This can take a while."));

    m_chatClient.startStoryReplay(
        m_settings->backendBaseUrl(),
        m_settings->sessionId().trimmed().isEmpty() ? QStringLiteral("story-replay") : m_settings->sessionId(),
        m_settings->characterName(),
        300000);
}

void ChatSessionController::clearMessages() {
    // 清空模型会通知 ListView 重新渲染。
    // Clearing the model notifies ListView to rerender.
    m_messages.clear();
    setStatusText(QStringLiteral("Chat history cleared."));
}

void ChatSessionController::selectCharacter(const QString &characterName) {
    // 角色选择保存在设置中，方便下次启动恢复。
    // Store character selection in settings so it is restored next launch.
    m_settings->setCharacterName(characterName);
    m_settings->save();
    syncCharacterState();
}

void ChatSessionController::refreshLiveMessages() {
    if (m_settings == nullptr) {
        return;
    }
    m_chatClient.openLiveMessages(
        m_settings->backendBaseUrl(),
        m_settings->sessionId(),
        m_settings->characterName(),
        m_settings->shortModeEnabled());
}

void ChatSessionController::appendLocalMessage(const QString &role, const QString &content, const QString &emotion) {
    // 用当前毫秒时间 + 小随机数生成一个足够稳定的本地 id。
    // Use current milliseconds plus a small random number as a stable-enough local id.
    MessageItem item;
    item.id = QDateTime::currentMSecsSinceEpoch() + QRandomGenerator::global()->bounded(1000);
    item.role = role;
    item.content = content;
    item.emotion = emotion;
    m_messages.appendMessage(item);
}

void ChatSessionController::scheduleAssistantSegments(const QStringList &segments) {
    // 后端正常返回但没有可见文本时，给用户一个明确提示。
    // If the backend succeeds but produces no visible text, show a clear message to the user.
    if (segments.isEmpty()) {
        appendLocalMessage(QStringLiteral("ai"), QStringLiteral("No valid reply received."));
        setStatusText(QStringLiteral("Backend returned no visible reply."));
        setBusy(false);
        return;
    }

    // 多段回复按累计延迟排队，模拟“逐句回复”的体验。
    // Queue multi-segment replies with accumulated delays to simulate sentence-by-sentence responses.
    int accumulatedDelay = 0;
    for (int index = 0; index < segments.size(); ++index) {
        const QString segment = segments.at(index);
        accumulatedDelay += humanDelayMs(segment);

        QTimer::singleShot(accumulatedDelay, this, [this, segment, index, lastIndex = segments.size() - 1]() {
            // 每段都可以带表情前缀，例如 [happy]Nice to meet you。
            // Each segment may carry an emotion prefix, such as [happy]Nice to meet you.
            const ParsedEmotionMessage parsed = MessageParser::parseEmotionPrefix(segment);
            if (!parsed.emotion.isEmpty()) {
                m_activeEmotion = parsed.emotion;
                emit activeCharacterChanged();
            }

            appendLocalMessage(
                QStringLiteral("ai"),
                parsed.content.isEmpty() ? segment : parsed.content,
                parsed.emotion);

            // 最后一段显示完才把 busy 置回 false，按钮才能再次发送。
            // Mark busy false only after the last segment is displayed, enabling Send again.
            if (index == lastIndex) {
                setStatusText(QStringLiteral("Reply received."));
                setBusy(false);
            }
        });
    }
}

int ChatSessionController::humanDelayMs(const QString &content) const {
    // 文本越长延迟越长；同时用随机范围避免每次回复节奏完全一样。
    // Longer text gets more delay; random bounds prevent every reply from feeling identical.
    const int textLength = qMax(content.size(), 1);
    const int baseDelay = qMax(m_settings->responseDelayMs(), 350);
    const int lowerBound = qBound(500, baseDelay + textLength * 18, 2600);
    const int upperBound = qBound(lowerBound + 120, lowerBound + 220 + textLength * 12, 3600);
    return QRandomGenerator::global()->bounded(lowerBound, upperBound + 1);
}

void ChatSessionController::setBusy(bool value) {
    // Qt 属性 setter 常见写法：值没变就直接返回，不发多余信号。
    // Common Qt property setter pattern: return early if unchanged, avoiding extra signals.
    if (m_busy == value) {
        return;
    }
    m_busy = value;
    emit busyChanged();
}

void ChatSessionController::setStatusText(const QString &value) {
    // statusTextChanged 发出后，QML 中依赖 statusText 的绑定会刷新。
    // After statusTextChanged is emitted, QML bindings depending on statusText refresh.
    if (m_statusText == value) {
        return;
    }
    m_statusText = value;
    emit statusTextChanged();
}

void ChatSessionController::syncCharacterState() {
    // 切换角色或刷新目录时，把当前表情回到角色默认表情。
    // When switching characters or reloading the catalog, reset to the character's default emotion.
    QString nextEmotion = m_catalog->defaultEmotion(m_settings->characterName());
    if (nextEmotion.isEmpty()) {
        nextEmotion.clear();
    }

    // 即使表情名没变，也发信号让图片路径等派生属性有机会刷新。
    // Even if the emotion did not change, emit so derived properties like image path can refresh.
    if (m_activeEmotion == nextEmotion) {
        emit activeCharacterChanged();
        return;
    }

    m_activeEmotion = nextEmotion;
    emit activeCharacterChanged();
}
