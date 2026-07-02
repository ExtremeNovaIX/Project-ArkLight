#pragma once

#include <QList>
#include <QString>
#include <QStringList>
#include <QVariant>
#include <QVariantList>
#include <QVariantMap>

namespace configcatalog {

constexpr int MaxHistoryItems = 3;

struct FieldDefinition {
    QString key;
    QString path;
    QString label;
    QString description;
    QString type;
    QStringList options;
    bool sensitive = false;
    QString placeholder;
};

struct ConfigDefinition {
    QString fileName;
    QString title;
    QString description;
    QList<FieldDefinition> fields;
};

QList<ConfigDefinition> buildDefinitions();
const ConfigDefinition *findDefinition(const QList<ConfigDefinition> &definitions, const QString &fileName);
QString configTitle(const QString &fileName, const QList<ConfigDefinition> &definitions = {});
QString resolveLocalConfigDir();
bool readUtf8Lines(const QString &filePath, QStringList *lines, QString *errorMessage);
bool writeUtf8Lines(const QString &filePath, const QStringList &lines, QString *errorMessage);
QVariantMap renderConfigPage(const QString &configDir, const ConfigDefinition &definition, QString *errorMessage);
QVariantMap renderFallbackConfigPage(const QString &configDir, const QString &fileName, QString *errorMessage);
bool updateYamlScalar(QStringList *lines, const QString &path, const QVariant &value);
bool updateYamlList(QStringList *lines, const QString &path, const QVariant &value);
bool parseBoolean(const QString &value);

} // namespace configcatalog