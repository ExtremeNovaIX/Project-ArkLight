#pragma once

#include <QObject>
#include <QString>

// 本地前端配置对象。
// Local frontend settings object.
//
// Q_PROPERTY 会把 C++ 属性暴露给 QML，并支持绑定、读取、写入和变更通知。
// Q_PROPERTY exposes C++ properties to QML and supports binding, reading, writing, and change notifications.
class FrontendSettings final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString languageId READ languageId WRITE setLanguageId NOTIFY settingsChanged)
    Q_PROPERTY(QString themeId READ themeId WRITE setThemeId NOTIFY settingsChanged)
    Q_PROPERTY(QString characterName READ characterName WRITE setCharacterName NOTIFY settingsChanged)
    Q_PROPERTY(QString sessionId READ sessionId WRITE setSessionId NOTIFY settingsChanged)
    Q_PROPERTY(QString workspaceName READ workspaceName WRITE setWorkspaceName NOTIFY settingsChanged)
    Q_PROPERTY(QString operatorName READ operatorName WRITE setOperatorName NOTIFY settingsChanged)
    Q_PROPERTY(bool bootAnimationEnabled READ bootAnimationEnabled WRITE setBootAnimationEnabled NOTIFY settingsChanged)
    Q_PROPERTY(int bootDurationMs READ bootDurationMs WRITE setBootDurationMs NOTIFY settingsChanged)
    Q_PROPERTY(int responseDelayMs READ responseDelayMs WRITE setResponseDelayMs NOTIFY settingsChanged)
    Q_PROPERTY(int moteCount READ moteCount WRITE setMoteCount NOTIFY settingsChanged)
    Q_PROPERTY(int uiScalePercent READ uiScalePercent WRITE setUiScalePercent NOTIFY settingsChanged)
    Q_PROPERTY(QString backendBaseUrl READ backendBaseUrl WRITE setBackendBaseUrl NOTIFY settingsChanged)

public:
    explicit FrontendSettings(QObject *parent = nullptr);

    // READ 访问器：QML 读取 frontendSettings.xxx 时会调用这些函数。
    // READ accessors: QML calls these when reading frontendSettings.xxx.
    QString languageId() const;
    QString themeId() const;
    QString characterName() const;
    QString sessionId() const;
    QString workspaceName() const;
    QString operatorName() const;
    bool bootAnimationEnabled() const;
    int bootDurationMs() const;
    int responseDelayMs() const;
    int moteCount() const;
    int uiScalePercent() const;
    QString backendBaseUrl() const;

    // QML 可调用的方法：重置默认值和写入本机持久化配置。
    // Methods callable from QML: reset defaults and persist settings locally.
    Q_INVOKABLE void reset();
    Q_INVOKABLE void save();

public slots:
    // WRITE setter 同时也是 slot；QML 赋值和 C++ connect 都可以调用。
    // WRITE setters are also slots; they can be called by QML assignment or C++ connect.
    void setLanguageId(const QString &value);
    void setThemeId(const QString &value);
    void setCharacterName(const QString &value);
    void setSessionId(const QString &value);
    void setWorkspaceName(const QString &value);
    void setOperatorName(const QString &value);
    void setBootAnimationEnabled(bool value);
    void setBootDurationMs(int value);
    void setResponseDelayMs(int value);
    void setMoteCount(int value);
    void setUiScalePercent(int value);
    void setBackendBaseUrl(const QString &value);

signals:
    // 任何设置变化时发出；QML 绑定依赖它刷新界面。
    // Emitted whenever any setting changes; QML bindings use it to refresh UI.
    void settingsChanged();

private:
    // 从项目外部 config/application.yaml 读取默认值。
    void loadExternalDefaults();

    // 从 QSettings 读取本机保存过的配置。
    // Load previously saved local settings from QSettings.
    void load();

    // 成员变量保存当前值；m_ 前缀是 Qt/C++ 项目常见命名习惯。
    // Member variables store current values; the m_ prefix is common in Qt/C++ projects.
    QString m_languageId;
    QString m_themeId;
    QString m_characterName;
    QString m_sessionId;
    QString m_workspaceName;
    QString m_operatorName;
    bool m_bootAnimationEnabled = true;
    int m_bootDurationMs = 1200;
    int m_responseDelayMs = 800;
    int m_moteCount = 28;
    int m_uiScalePercent = 100;
    QString m_backendBaseUrl;
};
