#pragma once

#include <QObject>
#include <QStringList>

#include "ChatClient.h"
#include "ChatMessageModel.h"

class CharacterCatalog;
class FrontendSettings;

// 聊天会话控制器：连接 QML、设置、角色目录、网络客户端和消息模型。
// Chat session controller: connects QML, settings, character catalog, network client, and message model.
class ChatSessionController final : public QObject {
    Q_OBJECT

    // Q_PROPERTY 让 QML 可以像访问普通属性一样访问 C++ 状态。
    // Q_PROPERTY lets QML access C++ state like normal properties.
    Q_PROPERTY(ChatMessageModel* messages READ messages CONSTANT)
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
    Q_PROPERTY(QString statusText READ statusText NOTIFY statusTextChanged)
    Q_PROPERTY(QString activeEmotion READ activeEmotion NOTIFY activeCharacterChanged)
    Q_PROPERTY(QString activeCharacterImagePath READ activeCharacterImagePath NOTIFY activeCharacterChanged)

public:
    ChatSessionController(FrontendSettings *settings,
                          CharacterCatalog *catalog,
                          QObject *parent = nullptr);

    // 给 QML 读取的属性访问器。
    // Property accessors read by QML.
    ChatMessageModel *messages();
    bool busy() const;
    QString statusText() const;
    QString activeEmotion() const;
    QString activeCharacterImagePath() const;

    // Q_INVOKABLE 表示 QML 可以调用这些 C++ 方法。
    // Q_INVOKABLE means QML can call these C++ methods.
    Q_INVOKABLE void sendMessage(const QString &content);
    Q_INVOKABLE void reportTypingActivity();
    Q_INVOKABLE void startStoryReplay();
    Q_INVOKABLE void clearMessages();
    Q_INVOKABLE void selectCharacter(const QString &characterName);

signals:
    // NOTIFY 信号必须在属性变化后发出，QML 绑定才会重新计算。
    // NOTIFY signals must be emitted after property changes so QML bindings recalculate.
    void busyChanged();
    void statusTextChanged();
    void activeCharacterChanged();

private:
    // 私有辅助函数封装会话内部逻辑，避免 QML 直接操作底层细节。
    // Private helpers keep session logic inside C++ instead of exposing low-level details to QML.
    void appendLocalMessage(const QString &role, const QString &content, const QString &emotion = QString());
    void scheduleAssistantSegments(const QStringList &segments);
    int humanDelayMs(const QString &content) const;
    void setBusy(bool value);
    void setStatusText(const QString &value);
    void syncCharacterState();
    void refreshLiveMessages();

    // settings/catalog 由 main.cpp 创建，本类只保存指针，不拥有它们。
    // settings/catalog are created in main.cpp; this class stores pointers but does not own them.
    FrontendSettings *m_settings;
    CharacterCatalog *m_catalog;

    // 这些成员由控制器拥有，生命周期跟随控制器。
    // These members are owned by the controller and share its lifetime.
    ChatClient m_chatClient;
    ChatMessageModel m_messages;
    bool m_busy = false;
    QString m_statusText;
    QString m_activeEmotion;
};
