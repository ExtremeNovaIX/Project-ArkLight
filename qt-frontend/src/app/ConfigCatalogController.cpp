#include "ConfigCatalogController.h"
#include "config/ConfigCatalogSupport.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSettings>
#include <QStringList>
#include <QTextStream>

#include <utility>

using namespace configcatalog;


ConfigCatalogController::ConfigCatalogController(QObject *parent)
    : QObject(parent) {
    loadHistories();
}

bool ConfigCatalogController::loading() const {
    return m_loading;
}

bool ConfigCatalogController::saving() const {
    return m_saving;
}

QString ConfigCatalogController::errorMessage() const {
    return m_errorMessage;
}

QString ConfigCatalogController::actionMessage() const {
    return m_actionMessage;
}

QString ConfigCatalogController::responseStatus() const {
    return m_responseStatus;
}

QString ConfigCatalogController::lastFetchedAt() const {
    return m_lastFetchedAt;
}

QString ConfigCatalogController::configDir() const {
    return m_configDir;
}

bool ConfigCatalogController::hasPendingChanges() const {
    return !m_dirtyFileNames.isEmpty();
}

int ConfigCatalogController::valuesRevision() const {
    return m_valuesRevision;
}

QString ConfigCatalogController::activeFileName() const {
    return m_activeFileName;
}

QVariantList ConfigCatalogController::configPages() const {
    return m_configPages;
}

QVariantMap ConfigCatalogController::activePage() const {
    return currentPage();
}

void ConfigCatalogController::fetchConfigs() {
    clearMessages();
    setLoading(true);

    const QString configDir = resolveLocalConfigDir();
    if (configDir.isEmpty()) {
        setErrorMessage(QStringLiteral("没有找到本地 config 目录。"));
        setResponseStatus(QString());
        setLoading(false);
        return;
    }

    QVariantList pages;
    QString error;
    const QList<ConfigDefinition> definitions = buildDefinitions();
    for (const ConfigDefinition &definition : definitions) {
        if (!QFileInfo(QDir(configDir).absoluteFilePath(definition.fileName)).isFile()) {
            continue;
        }
        QVariantMap page = renderConfigPage(configDir, definition, &error);
        if (!page.isEmpty()) {
            pages.append(page);
        }
    }

    m_configDir = configDir;
    m_configPages = pages;
    setValuesFromPages();
    if (m_activeFileName.isEmpty() || pageByFileName(m_activeFileName).isEmpty()) {
        m_activeFileName = m_configPages.isEmpty()
            ? QString()
            : m_configPages.first().toMap().value(QStringLiteral("fileName")).toString();
    }
    setResponseStatus(QStringLiteral("local file"));
    stampFetchedAt();
    emit catalogChanged();
    if (!error.isEmpty() && pages.isEmpty()) {
        setErrorMessage(error);
    }
    setLoading(false);
}

void ConfigCatalogController::saveCurrentConfig() {
    const QVariantMap page = currentPage();
    if (page.isEmpty()) {
        return;
    }

    clearMessages();
    setSaving(true);

    const QString fileName = page.value(QStringLiteral("fileName")).toString();
    if (m_configDir.isEmpty() || fileName.isEmpty()) {
        setErrorMessage(QStringLiteral("没有可写入的本地配置文件。"));
        setSaving(false);
        return;
    }

    const QString filePath = QDir(m_configDir).absoluteFilePath(fileName);
    QStringList lines;
    QString error;
    if (!readUtf8Lines(filePath, &lines, &error)) {
        setErrorMessage(error);
        setSaving(false);
        return;
    }

    const QVariantList fields = page.value(QStringLiteral("fields")).toList();
    for (const QVariant &item : fields) {
        const QVariantMap field = item.toMap();
        const QString key = field.value(QStringLiteral("key")).toString();
        QString path = field.value(QStringLiteral("path")).toString();
        if (path.isEmpty()) {
            path = key;
        }
        const QString type = field.value(QStringLiteral("type")).toString();
        const QVariant value = m_fieldValues.value(fieldStorageKey(fileName, key), fallbackFieldValue(field));
        if (type == QStringLiteral("list")) {
            updateYamlList(&lines, path, value);
        } else {
            updateYamlScalar(&lines, path, value);
        }
    }

    if (!writeUtf8Lines(filePath, lines, &error)) {
        setErrorMessage(error);
        setSaving(false);
        return;
    }

    rememberHistory(page);
    const QList<ConfigDefinition> definitions = buildDefinitions();
    const ConfigDefinition *definition = findDefinition(definitions, fileName);
    QVariantMap savedPage = definition == nullptr
        ? renderFallbackConfigPage(m_configDir, fileName, &error)
        : renderConfigPage(m_configDir, *definition, &error);
    if (!savedPage.isEmpty()) {
        for (int index = 0; index < m_configPages.size(); ++index) {
            if (m_configPages.at(index).toMap().value(QStringLiteral("fileName")).toString() == fileName) {
                m_configPages[index] = savedPage;
                break;
            }
        }
        setValuesFromPages();
        emit catalogChanged();
    }

    setResponseStatus(QStringLiteral("local file"));
    stampFetchedAt();
    setActionMessage(QStringLiteral("%1 已写入本地 config，重启后端后生效。").arg(configTitle(fileName, definitions)));
    setSaving(false);
}

void ConfigCatalogController::saveChangedConfigs() {
    if (m_dirtyFileNames.isEmpty() || m_saving) {
        return;
    }

    clearMessages();
    setSaving(true);

    QStringList savedTitles;
    const QStringList dirtyFileNames = m_dirtyFileNames.values();
    for (const QString &fileName : dirtyFileNames) {
        QString error;
        QString savedTitle;
        if (!saveConfigFile(fileName, &savedTitle, &error)) {
            setErrorMessage(error);
            setSaving(false);
            return;
        }
        if (!savedTitle.isEmpty()) {
            savedTitles.append(savedTitle);
        }
    }

    setValuesFromPages();
    setResponseStatus(QStringLiteral("local file"));
    stampFetchedAt();
    if (!savedTitles.isEmpty()) {
        setActionMessage(QStringLiteral("%1 saved to local config. Restart backend to apply.")
                             .arg(savedTitles.join(QStringLiteral(", "))));
    }
    setSaving(false);
}

QVariant ConfigCatalogController::fieldValue(const QString &fileName, const QString &fieldKey) const {
    const QVariantMap page = pageByFileName(fileName);
    const QVariantList fields = page.value(QStringLiteral("fields")).toList();
    for (const QVariant &item : fields) {
        const QVariantMap field = item.toMap();
        if (field.value(QStringLiteral("key")).toString() == fieldKey) {
            return m_fieldValues.value(fieldStorageKey(fileName, fieldKey), fallbackFieldValue(field));
        }
    }
    return m_fieldValues.value(fieldStorageKey(fileName, fieldKey));
}

void ConfigCatalogController::setFieldValue(const QString &fileName, const QString &fieldKey, const QVariant &value) {
    const QString storageKey = fieldStorageKey(fileName, fieldKey);
    if (m_fieldValues.value(storageKey) == value) {
        return;
    }
    m_fieldValues.insert(storageKey, value);
    refreshDirtyState(fileName);
    ++m_valuesRevision;
    emit valuesChanged();
}

QStringList ConfigCatalogController::historyFor(const QString &fileName, const QString &fieldKey) const {
    const QVariant rawHistory = m_fieldHistories.value(fieldStorageKey(fileName, fieldKey));
    QStringList result;
    for (const QVariant &item : rawHistory.toList()) {
        const QString value = item.toString();
        if (!value.isEmpty()) {
            result.append(value);
        }
    }
    return result;
}

void ConfigCatalogController::applyHistory(const QString &fileName, const QString &fieldKey, const QString &value) {
    if (value.trimmed().isEmpty()) {
        return;
    }
    setFieldValue(fileName, fieldKey, value);
}

bool ConfigCatalogController::fieldSupportsHistory(const QVariantMap &field) const {
    const QString type = field.value(QStringLiteral("type")).toString();
    return type != QStringLiteral("boolean") && type != QStringLiteral("select");
}

void ConfigCatalogController::setActiveFileName(const QString &value) {
    if (m_activeFileName == value) {
        return;
    }
    m_activeFileName = value;
    emit catalogChanged();
}

QString ConfigCatalogController::fieldStorageKey(const QString &fileName, const QString &fieldKey) const {
    return fileName + QStringLiteral(":") + fieldKey;
}

QVariant ConfigCatalogController::fallbackFieldValue(const QVariantMap &field) const {
    const QString type = field.value(QStringLiteral("type")).toString();
    const QString value = field.value(QStringLiteral("value")).toString();
    return type == QStringLiteral("boolean") ? QVariant(parseBoolean(value)) : QVariant(value);
}

QVariantMap ConfigCatalogController::pageByFileName(const QString &fileName) const {
    for (const QVariant &item : m_configPages) {
        const QVariantMap page = item.toMap();
        if (page.value(QStringLiteral("fileName")).toString() == fileName) {
            return page;
        }
    }
    return {};
}

QVariantMap ConfigCatalogController::currentPage() const {
    QVariantMap page = pageByFileName(m_activeFileName);
    if (!page.isEmpty()) {
        return page;
    }
    return m_configPages.isEmpty() ? QVariantMap{} : m_configPages.first().toMap();
}

bool ConfigCatalogController::pageHasChanges(const QString &fileName) const {
    const QVariantMap page = pageByFileName(fileName);
    if (page.isEmpty()) {
        return false;
    }

    const QVariantList fields = page.value(QStringLiteral("fields")).toList();
    for (const QVariant &fieldItem : fields) {
        const QVariantMap field = fieldItem.toMap();
        const QString key = field.value(QStringLiteral("key")).toString();
        const QVariant fallback = fallbackFieldValue(field);
        const QVariant current = m_fieldValues.value(fieldStorageKey(fileName, key), fallback);
        if (fallback.typeId() == QMetaType::Bool || current.typeId() == QMetaType::Bool) {
            if (fallback.toBool() != current.toBool()) {
                return true;
            }
        } else if (fallback.toString() != current.toString()) {
            return true;
        }
    }
    return false;
}

bool ConfigCatalogController::saveConfigFile(const QString &fileName, QString *savedTitle, QString *error) {
    if (m_configDir.isEmpty() || fileName.isEmpty()) {
        if (error != nullptr) {
            *error = QStringLiteral("No writable local config file.");
        }
        return false;
    }

    const QVariantMap page = pageByFileName(fileName);
    if (page.isEmpty()) {
        if (error != nullptr) {
            *error = QStringLiteral("Config page is not loaded: %1").arg(fileName);
        }
        return false;
    }

    const QString filePath = QDir(m_configDir).absoluteFilePath(fileName);
    QStringList lines;
    if (!readUtf8Lines(filePath, &lines, error)) {
        return false;
    }

    const QVariantList fields = page.value(QStringLiteral("fields")).toList();
    for (const QVariant &item : fields) {
        const QVariantMap field = item.toMap();
        const QString key = field.value(QStringLiteral("key")).toString();
        QString path = field.value(QStringLiteral("path")).toString();
        if (path.isEmpty()) {
            path = key;
        }
        const QString type = field.value(QStringLiteral("type")).toString();
        const QVariant value = m_fieldValues.value(fieldStorageKey(fileName, key), fallbackFieldValue(field));
        if (type == QStringLiteral("list")) {
            updateYamlList(&lines, path, value);
        } else {
            updateYamlScalar(&lines, path, value);
        }
    }

    if (!writeUtf8Lines(filePath, lines, error)) {
        return false;
    }

    rememberHistory(page);
    const QList<ConfigDefinition> definitions = buildDefinitions();
    const ConfigDefinition *definition = findDefinition(definitions, fileName);
    QVariantMap savedPage = definition == nullptr
        ? renderFallbackConfigPage(m_configDir, fileName, error)
        : renderConfigPage(m_configDir, *definition, error);
    if (!savedPage.isEmpty()) {
        for (int index = 0; index < m_configPages.size(); ++index) {
            if (m_configPages.at(index).toMap().value(QStringLiteral("fileName")).toString() == fileName) {
                m_configPages[index] = savedPage;
                break;
            }
        }
        emit catalogChanged();
    }

    m_dirtyFileNames.remove(fileName);
    if (savedTitle != nullptr) {
        *savedTitle = configTitle(fileName, definitions);
    }
    return true;
}

void ConfigCatalogController::refreshDirtyState(const QString &fileName) {
    if (fileName.isEmpty()) {
        return;
    }
    if (pageHasChanges(fileName)) {
        m_dirtyFileNames.insert(fileName);
    } else {
        m_dirtyFileNames.remove(fileName);
    }
}

void ConfigCatalogController::setValuesFromPages() {
    QVariantMap nextValues;
    for (const QVariant &pageItem : m_configPages) {
        const QVariantMap page = pageItem.toMap();
        const QString fileName = page.value(QStringLiteral("fileName")).toString();
        const QVariantList fields = page.value(QStringLiteral("fields")).toList();
        for (const QVariant &fieldItem : fields) {
            const QVariantMap field = fieldItem.toMap();
            nextValues.insert(
                fieldStorageKey(fileName, field.value(QStringLiteral("key")).toString()),
                fallbackFieldValue(field));
        }
    }
    m_fieldValues = nextValues;
    m_dirtyFileNames.clear();
    ++m_valuesRevision;
    emit valuesChanged();
}

void ConfigCatalogController::rememberHistory(const QVariantMap &page) {
    bool changed = false;
    const QString fileName = page.value(QStringLiteral("fileName")).toString();
    const QVariantList fields = page.value(QStringLiteral("fields")).toList();
    for (const QVariant &fieldItem : fields) {
        const QVariantMap field = fieldItem.toMap();
        if (!fieldSupportsHistory(field)) {
            continue;
        }
        const QString key = fieldStorageKey(fileName, field.value(QStringLiteral("key")).toString());
        const QString value = m_fieldValues.value(key).toString().trimmed();
        if (value.isEmpty()) {
            continue;
        }

        QVariantList nextHistory;
        nextHistory.append(value);
        for (const QVariant &item : m_fieldHistories.value(key).toList()) {
            const QString existing = item.toString();
            if (!existing.isEmpty() && existing != value && nextHistory.size() < MaxHistoryItems) {
                nextHistory.append(existing);
            }
        }
        if (m_fieldHistories.value(key).toList() != nextHistory) {
            m_fieldHistories.insert(key, nextHistory);
            changed = true;
        }
    }

    if (changed) {
        saveHistories();
    }
}

void ConfigCatalogController::loadHistories() {
    QSettings settings;
    const QByteArray raw = settings.value(QStringLiteral("configFieldHistories")).toByteArray();
    if (raw.isEmpty()) {
        return;
    }

    QJsonParseError parseError;
    const QJsonDocument document = QJsonDocument::fromJson(raw, &parseError);
    if (parseError.error == QJsonParseError::NoError && document.isObject()) {
        m_fieldHistories = document.object().toVariantMap();
    }
}

void ConfigCatalogController::saveHistories() const {
    QJsonObject object = QJsonObject::fromVariantMap(m_fieldHistories);
    QSettings settings;
    settings.setValue(QStringLiteral("configFieldHistories"), QJsonDocument(object).toJson(QJsonDocument::Compact));
}

void ConfigCatalogController::clearMessages() {
    setErrorMessage(QString());
    setActionMessage(QString());
}

void ConfigCatalogController::setLoading(bool value) {
    if (m_loading == value) {
        return;
    }
    m_loading = value;
    emit busyChanged();
}

void ConfigCatalogController::setSaving(bool value) {
    if (m_saving == value) {
        return;
    }
    m_saving = value;
    emit busyChanged();
}

void ConfigCatalogController::setErrorMessage(const QString &value) {
    if (m_errorMessage == value) {
        return;
    }
    m_errorMessage = value;
    emit messageChanged();
}

void ConfigCatalogController::setActionMessage(const QString &value) {
    if (m_actionMessage == value) {
        return;
    }
    m_actionMessage = value;
    emit messageChanged();
}

void ConfigCatalogController::setResponseStatus(const QString &value) {
    if (m_responseStatus == value) {
        return;
    }
    m_responseStatus = value;
    emit messageChanged();
}

void ConfigCatalogController::stampFetchedAt() {
    m_lastFetchedAt = QDateTime::currentDateTime().toString(QStringLiteral("yyyy-MM-dd HH:mm:ss"));
    emit messageChanged();
}
