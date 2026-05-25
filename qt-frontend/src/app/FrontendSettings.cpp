#include "FrontendSettings.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QHash>
#include <QRegularExpression>
#include <QSettings>
#include <QtGlobal>

namespace {

int clampValue(int value, int min, int max) {
    // 把数值限制在允许范围内，防止 QML 滑块或配置文件写入异常值。
    // Clamp values into an allowed range to protect against odd QML slider or config values.
    return qMin(max, qMax(min, value));
}

QString normalizeYamlKey(const QString &value) {
    return QString(value).trimmed().replace('_', '-').toLower();
}

QString stripYamlComment(const QString &value) {
    QChar quote;
    for (int i = 0; i < value.size(); ++i) {
        const QChar ch = value.at(i);
        if ((ch == QLatin1Char('"') || ch == QLatin1Char('\'')) && (i == 0 || value.at(i - 1) != QLatin1Char('\\'))) {
            quote = quote == ch ? QChar() : (quote.isNull() ? ch : quote);
            continue;
        }
        if (ch == QLatin1Char('#') && quote.isNull() && (i == 0 || value.at(i - 1).isSpace())) {
            return value.left(i).trimmed();
        }
    }
    return value.trimmed();
}

QString normalizeYamlValue(const QString &value) {
    const QString trimmed = stripYamlComment(value);
    if (trimmed.size() >= 2
        && ((trimmed.startsWith(QLatin1Char('"')) && trimmed.endsWith(QLatin1Char('"')))
            || (trimmed.startsWith(QLatin1Char('\'')) && trimmed.endsWith(QLatin1Char('\''))))) {
        return trimmed.mid(1, trimmed.size() - 2);
    }
    return trimmed;
}

QHash<QString, QString> parseYamlScalars(const QString &content) {
    QHash<QString, QString> result;
    QList<QPair<int, QString>> stack;
    const QStringList lines = content.split(QRegularExpression(QStringLiteral("\\r?\\n")));

    for (const QString &line : lines) {
        const QString trimmedLine = line.trimmed();
        if (trimmedLine.isEmpty() || trimmedLine.startsWith(QLatin1Char('#')) || trimmedLine.startsWith(QLatin1Char('-'))) {
            continue;
        }

        int indent = 0;
        while (indent < line.size() && line.at(indent).isSpace()) {
            ++indent;
        }

        const int colonIndex = line.indexOf(QLatin1Char(':'));
        if (colonIndex <= 0) {
            continue;
        }

        const QString rawKey = line.left(colonIndex).trimmed();
        if (rawKey.contains(QLatin1Char(' '))) {
            continue;
        }

        while (!stack.isEmpty() && stack.last().first >= indent) {
            stack.removeLast();
        }

        const QString key = normalizeYamlKey(rawKey);
        QStringList pathParts;
        for (const auto &entry : stack) {
            pathParts << entry.second;
        }
        pathParts << key;

        const QString value = normalizeYamlValue(line.mid(colonIndex + 1));
        if (value.isEmpty()) {
            stack.append(qMakePair(indent, key));
            continue;
        }
        result.insert(pathParts.join(QLatin1Char('.')), value);
    }

    return result;
}

QString resolveExternalConfigFile() {
    const QString envConfigDir = qEnvironmentVariable("ARCLIGHT_CONFIG_DIR").trimmed();
    if (!envConfigDir.isEmpty()) {
        const QString candidate = QDir(envConfigDir).absoluteFilePath(QStringLiteral("application.yaml"));
        if (QFileInfo::isRegularFile(candidate)) {
            return candidate;
        }
    }

    QStringList searchRoots;
    searchRoots << QDir::currentPath() << QCoreApplication::applicationDirPath();
    for (const QString &root : searchRoots) {
        QDir dir(root);
        for (int depth = 0; depth < 6; ++depth) {
            const QString candidate = dir.absoluteFilePath(QStringLiteral("config/application.yaml"));
            if (QFileInfo::isRegularFile(candidate)) {
                return candidate;
            }
            if (!dir.cdUp()) {
                break;
            }
        }
    }

    return QString();
}

QString configValue(const QHash<QString, QString> &config, const QStringList &paths) {
    for (const QString &path : paths) {
        const QString value = config.value(path).trimmed();
        if (!value.isEmpty()) {
            return value;
        }
    }
    return QString();
}

void assignStringFromConfig(QString &target, const QHash<QString, QString> &config, const QStringList &paths) {
    const QString value = configValue(config, paths);
    if (!value.isEmpty()) {
        target = value;
    }
}

void assignUrlFromConfig(QString &target, const QHash<QString, QString> &config, const QStringList &paths) {
    QString value = configValue(config, paths);
    while (value.endsWith(QLatin1Char('/'))) {
        value.chop(1);
    }
    if (!value.isEmpty()) {
        target = value;
    }
}

void assignBoolFromConfig(bool &target, const QHash<QString, QString> &config, const QStringList &paths) {
    const QString value = configValue(config, paths).toLower();
    if (value == QStringLiteral("true") || value == QStringLiteral("yes") || value == QStringLiteral("1") || value == QStringLiteral("on")) {
        target = true;
    } else if (value == QStringLiteral("false") || value == QStringLiteral("no") || value == QStringLiteral("0") || value == QStringLiteral("off")) {
        target = false;
    }
}

void assignIntFromConfig(int &target, const QHash<QString, QString> &config, const QStringList &paths, int min, int max) {
    bool ok = false;
    const int value = configValue(config, paths).toInt(&ok);
    if (ok) {
        target = clampValue(value, min, max);
    }
}

} // namespace

FrontendSettings::FrontendSettings(QObject *parent)
    : QObject(parent) {
    // QSettings 默认用组织名/应用名决定保存位置。
    // QSettings uses organization/application names to decide where settings are stored.
    QCoreApplication::setOrganizationName(QStringLiteral("Arklight"));
    QCoreApplication::setApplicationName(QStringLiteral("ArklightQtFrontend"));

    // 先放入默认值，再用本地保存值覆盖。
    // Fill defaults first, then override them with saved local values.
    reset();
    load();
}

QString FrontendSettings::themeId() const {
    return m_themeId;
}

QString FrontendSettings::languageId() const {
    return m_languageId;
}

QString FrontendSettings::characterName() const {
    return m_characterName;
}

QString FrontendSettings::sessionId() const {
    return m_sessionId;
}

QString FrontendSettings::workspaceName() const {
    return m_workspaceName;
}

QString FrontendSettings::operatorName() const {
    return m_operatorName;
}

bool FrontendSettings::bootAnimationEnabled() const {
    return m_bootAnimationEnabled;
}

int FrontendSettings::bootDurationMs() const {
    return m_bootDurationMs;
}

int FrontendSettings::responseDelayMs() const {
    return m_responseDelayMs;
}

int FrontendSettings::moteCount() const {
    return m_moteCount;
}

int FrontendSettings::uiScalePercent() const {
    return m_uiScalePercent;
}

QString FrontendSettings::backendBaseUrl() const {
    return m_backendBaseUrl;
}

void FrontendSettings::reset() {
    // reset 只改内存中的当前值；真正写入磁盘由 save() 完成。
    // reset only changes in-memory values; save() is responsible for writing to disk.
    m_languageId = QStringLiteral("en");
    m_themeId = QStringLiteral("system");
    m_characterName.clear();
    m_sessionId = QStringLiteral("qt-session");
    m_workspaceName = QStringLiteral("ArkLight Pioneer");
    m_operatorName = QStringLiteral("Local");
    m_bootAnimationEnabled = true;
    m_bootDurationMs = 1200;
    m_responseDelayMs = 800;
    m_moteCount = 28;
    m_uiScalePercent = 100;
    m_backendBaseUrl = QStringLiteral("http://localhost:8080");
    loadExternalDefaults();
    emit settingsChanged();
}

void FrontendSettings::loadExternalDefaults() {
    const QString configFilePath = resolveExternalConfigFile();
    if (configFilePath.isEmpty()) {
        return;
    }

    QFile file(configFilePath);
    if (!file.open(QIODevice::ReadOnly | QIODevice::Text)) {
        return;
    }

    const QHash<QString, QString> config = parseYamlScalars(QString::fromUtf8(file.readAll()));

    // 公共前端配置先作为默认值，Qt 专用配置可以覆盖它。
    assignStringFromConfig(m_characterName, config, {
        QStringLiteral("frontend.qt.settings.character-name"),
        QStringLiteral("frontend.settings.character-name")
    });
    assignStringFromConfig(m_sessionId, config, {
        QStringLiteral("frontend.qt.settings.session-id"),
        QStringLiteral("frontend.settings.session-id")
    });
    assignStringFromConfig(m_workspaceName, config, {
        QStringLiteral("frontend.qt.settings.workspace-name"),
        QStringLiteral("frontend.settings.workspace-name")
    });
    assignStringFromConfig(m_operatorName, config, {
        QStringLiteral("frontend.qt.settings.operator-name"),
        QStringLiteral("frontend.settings.operator-name")
    });
    assignUrlFromConfig(m_backendBaseUrl, config, {
        QStringLiteral("frontend.qt.settings.backend-base-url"),
        QStringLiteral("frontend.settings.backend-base-url")
    });

    // Qt 独有设置不影响 Web 前端。
    assignStringFromConfig(m_languageId, config, {QStringLiteral("frontend.qt.settings.language-id")});
    assignStringFromConfig(m_themeId, config, {QStringLiteral("frontend.qt.settings.theme-id")});
    assignBoolFromConfig(m_bootAnimationEnabled, config, {
        QStringLiteral("frontend.qt.settings.boot-animation-enabled"),
        QStringLiteral("frontend.settings.boot-animation-enabled")
    });
    assignIntFromConfig(m_bootDurationMs, config, {
        QStringLiteral("frontend.qt.settings.boot-duration-ms"),
        QStringLiteral("frontend.settings.boot-duration-ms")
    }, 0, 10000);
    assignIntFromConfig(m_responseDelayMs, config, {
        QStringLiteral("frontend.qt.settings.response-delay-ms"),
        QStringLiteral("frontend.settings.response-delay-ms")
    }, 0, 10000);
    assignIntFromConfig(m_moteCount, config, {
        QStringLiteral("frontend.qt.settings.mote-count"),
        QStringLiteral("frontend.settings.mote-count")
    }, 0, 120);
    assignIntFromConfig(m_uiScalePercent, config, {QStringLiteral("frontend.qt.settings.ui-scale-percent")}, 80, 140);

}

void FrontendSettings::save() {
    // QSettings 会在不同平台使用不同后端：Windows 通常是注册表，macOS/Linux 使用配置文件。
    // QSettings uses platform-native storage: usually Registry on Windows and config files on macOS/Linux.
    QSettings settings;
    settings.setValue(QStringLiteral("languageId"), m_languageId);
    settings.setValue(QStringLiteral("themeId"), m_themeId);
    settings.setValue(QStringLiteral("characterName"), m_characterName);
    settings.setValue(QStringLiteral("sessionId"), m_sessionId);
    settings.setValue(QStringLiteral("workspaceName"), m_workspaceName);
    settings.setValue(QStringLiteral("operatorName"), m_operatorName);
    settings.setValue(QStringLiteral("bootAnimationEnabled"), m_bootAnimationEnabled);
    settings.setValue(QStringLiteral("bootDurationMs"), m_bootDurationMs);
    settings.setValue(QStringLiteral("responseDelayMs"), m_responseDelayMs);
    settings.setValue(QStringLiteral("moteCount"), m_moteCount);
    settings.setValue(QStringLiteral("uiScalePercent"), m_uiScalePercent);
    settings.setValue(QStringLiteral("backendBaseUrl"), m_backendBaseUrl);
}

void FrontendSettings::setLanguageId(const QString &value) {
    QString normalized = value.trimmed().toLower();
    if (normalized != QStringLiteral("zh")) {
        normalized = QStringLiteral("en");
    }
    if (m_languageId == normalized) {
        return;
    }
    m_languageId = normalized;
    emit settingsChanged();
}

void FrontendSettings::setThemeId(const QString &value) {
    // setter 统一做规范化和“值没变就不发信号”，避免 QML 重复刷新。
    // Setters normalize input and skip unchanged values to avoid unnecessary QML refreshes.
    QString normalized = value.trimmed().toLower();
    if (normalized.isEmpty() || normalized == QStringLiteral("qt-default")) {
        normalized = QStringLiteral("system");
    }
    if (normalized != QStringLiteral("system") && normalized != QStringLiteral("light") && normalized != QStringLiteral("dark")) {
        normalized = QStringLiteral("system");
    }
    if (m_themeId == normalized) {
        return;
    }
    m_themeId = normalized;
    emit settingsChanged();
}

void FrontendSettings::setCharacterName(const QString &value) {
    const QString normalized = value.trimmed();
    if (m_characterName == normalized) {
        return;
    }
    m_characterName = normalized;
    emit settingsChanged();
}

void FrontendSettings::setSessionId(const QString &value) {
    const QString normalized = value.trimmed().isEmpty() ? QStringLiteral("qt-session") : value.trimmed();
    if (m_sessionId == normalized) {
        return;
    }
    m_sessionId = normalized;
    emit settingsChanged();
}

void FrontendSettings::setWorkspaceName(const QString &value) {
    const QString normalized = value.trimmed().isEmpty() ? QStringLiteral("ArkLight Pioneer") : value.trimmed();
    if (m_workspaceName == normalized) {
        return;
    }
    m_workspaceName = normalized;
    emit settingsChanged();
}

void FrontendSettings::setOperatorName(const QString &value) {
    const QString normalized = value.trimmed().isEmpty() ? QStringLiteral("Local") : value.trimmed();
    if (m_operatorName == normalized) {
        return;
    }
    m_operatorName = normalized;
    emit settingsChanged();
}

void FrontendSettings::setBootAnimationEnabled(bool value) {
    if (m_bootAnimationEnabled == value) {
        return;
    }
    m_bootAnimationEnabled = value;
    emit settingsChanged();
}

void FrontendSettings::setBootDurationMs(int value) {
    const int normalized = clampValue(value, 0, 10000);
    if (m_bootDurationMs == normalized) {
        return;
    }
    m_bootDurationMs = normalized;
    emit settingsChanged();
}

void FrontendSettings::setResponseDelayMs(int value) {
    const int normalized = clampValue(value, 0, 10000);
    if (m_responseDelayMs == normalized) {
        return;
    }
    m_responseDelayMs = normalized;
    emit settingsChanged();
}

void FrontendSettings::setMoteCount(int value) {
    const int normalized = clampValue(value, 0, 120);
    if (m_moteCount == normalized) {
        return;
    }
    m_moteCount = normalized;
    emit settingsChanged();
}

void FrontendSettings::setUiScalePercent(int value) {
    const int normalized = clampValue(value, 80, 140);
    if (m_uiScalePercent == normalized) {
        return;
    }
    m_uiScalePercent = normalized;
    emit settingsChanged();
}

void FrontendSettings::setBackendBaseUrl(const QString &value) {
    QString normalized = value.trimmed();
    // 去掉末尾斜杠，后续拼接 /api/chat/send 时不会变成双斜杠。
    // Remove trailing slashes so appending /api/chat/send does not create double slashes.
    while (normalized.endsWith('/')) {
        normalized.chop(1);
    }
    if (normalized.isEmpty()) {
        normalized = QStringLiteral("http://localhost:8080");
    }
    if (m_backendBaseUrl == normalized) {
        return;
    }
    m_backendBaseUrl = normalized;
    emit settingsChanged();
}

void FrontendSettings::load() {
    // 通过 setter 读取配置，这样读取到的旧值也会走同一套默认值和范围校验逻辑。
    // Load through setters so old saved values still pass through defaulting and validation logic.
    QSettings settings;
    setLanguageId(settings.value(QStringLiteral("languageId"), m_languageId).toString());
    setThemeId(settings.value(QStringLiteral("themeId"), m_themeId).toString());
    setCharacterName(settings.value(QStringLiteral("characterName"), m_characterName).toString());
    setSessionId(settings.value(QStringLiteral("sessionId"), m_sessionId).toString());
    setWorkspaceName(settings.value(QStringLiteral("workspaceName"), m_workspaceName).toString());
    setOperatorName(settings.value(QStringLiteral("operatorName"), m_operatorName).toString());
    setBootAnimationEnabled(settings.value(QStringLiteral("bootAnimationEnabled"), m_bootAnimationEnabled).toBool());
    setBootDurationMs(settings.value(QStringLiteral("bootDurationMs"), m_bootDurationMs).toInt());
    setResponseDelayMs(settings.value(QStringLiteral("responseDelayMs"), m_responseDelayMs).toInt());
    setMoteCount(settings.value(QStringLiteral("moteCount"), m_moteCount).toInt());
    setUiScalePercent(settings.value(QStringLiteral("uiScalePercent"), m_uiScalePercent).toInt());
    setBackendBaseUrl(settings.value(QStringLiteral("backendBaseUrl"), m_backendBaseUrl).toString());
}
