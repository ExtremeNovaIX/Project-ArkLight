#include "config/ConfigCatalogSupport.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QMetaType>

#include <utility>

namespace configcatalog {

struct YamlStackEntry {
    int indent = 0;
    QString path;
};

struct YamlScalar {
    QString path;
    QString key;
    QString value;
    int lineIndex = -1;
    int indent = 0;
};

struct YamlNode {
    QString path;
    QString key;
    QString value;
    int lineIndex = -1;
    int indent = 0;
    bool hasValue = false;
};



bool parseBoolean(const QString &value);

int leadingSpaces(const QString &line) {
    int count = 0;
    while (count < line.size() && line.at(count) == QLatin1Char(' ')) {
        ++count;
    }
    return count;
}

int findYamlColon(const QString &line) {
    bool quoted = false;
    QChar quoteChar;
    for (int index = 0; index < line.size(); ++index) {
        const QChar ch = line.at(index);
        if ((ch == QLatin1Char('"') || ch == QLatin1Char('\'')) && (index == 0 || line.at(index - 1) != QLatin1Char('\\'))) {
            if (!quoted) {
                quoted = true;
                quoteChar = ch;
            } else if (quoteChar == ch) {
                quoted = false;
            }
            continue;
        }
        if (ch == QLatin1Char(':') && !quoted) {
            return index;
        }
    }
    return -1;
}

QString normalizeYamlKey(QString value) {
    value = value.trimmed();
    if ((value.startsWith(QLatin1Char('"')) && value.endsWith(QLatin1Char('"')))
        || (value.startsWith(QLatin1Char('\'')) && value.endsWith(QLatin1Char('\'')))) {
        value = value.mid(1, value.size() - 2);
    }
    return value.replace(QLatin1Char('_'), QLatin1Char('-'));
}

QString stripInlineComment(const QString &value) {
    bool quoted = false;
    QChar quoteChar;
    for (int index = 0; index < value.size(); ++index) {
        const QChar ch = value.at(index);
        if ((ch == QLatin1Char('"') || ch == QLatin1Char('\'')) && (index == 0 || value.at(index - 1) != QLatin1Char('\\'))) {
            if (!quoted) {
                quoted = true;
                quoteChar = ch;
            } else if (quoteChar == ch) {
                quoted = false;
            }
            continue;
        }
        if (ch == QLatin1Char('#') && !quoted && (index == 0 || value.at(index - 1).isSpace())) {
            return value.left(index);
        }
    }
    return value;
}

QString normalizeYamlScalar(const QString &value) {
    QString trimmed = stripInlineComment(value).trimmed();
    if ((trimmed.startsWith(QLatin1Char('"')) && trimmed.endsWith(QLatin1Char('"')))
        || (trimmed.startsWith(QLatin1Char('\'')) && trimmed.endsWith(QLatin1Char('\'')))) {
        trimmed = trimmed.mid(1, trimmed.size() - 2);
    }
    return trimmed;
}

QString joinYamlPath(const QList<YamlStackEntry> &stack, const QString &key) {
    return stack.isEmpty() ? key : stack.last().path + QLatin1Char('.') + key;
}

QList<YamlScalar> parseYamlScalars(const QStringList &lines) {
    QList<YamlScalar> scalars;
    QList<YamlStackEntry> stack;
    for (int lineIndex = 0; lineIndex < lines.size(); ++lineIndex) {
        const QString line = lines.at(lineIndex);
        const int indent = leadingSpaces(line);
        const QString stripped = line.mid(indent);
        if (stripped.trimmed().isEmpty()
            || stripped.trimmed().startsWith(QLatin1Char('#'))
            || stripped.trimmed().startsWith(QStringLiteral("- "))) {
            continue;
        }
        const int colonIndex = findYamlColon(stripped);
        if (colonIndex <= 0) {
            continue;
        }
        while (!stack.isEmpty() && stack.last().indent >= indent) {
            stack.removeLast();
        }
        const QString key = normalizeYamlKey(stripped.left(colonIndex));
        if (key.isEmpty()) {
            continue;
        }
        const QString rawValue = stripped.mid(colonIndex + 1);
        const QString path = joinYamlPath(stack, key);
        if (rawValue.trimmed().isEmpty()) {
            stack.append(YamlStackEntry{indent, path});
            continue;
        }
        scalars.append(YamlScalar{
            path,
            stripped.left(colonIndex).trimmed(),
            normalizeYamlScalar(rawValue),
            lineIndex,
            indent
        });
    }
    return scalars;
}

QList<YamlNode> parseYamlNodes(const QStringList &lines) {
    QList<YamlNode> nodes;
    QList<YamlStackEntry> stack;
    for (int lineIndex = 0; lineIndex < lines.size(); ++lineIndex) {
        const QString line = lines.at(lineIndex);
        const int indent = leadingSpaces(line);
        const QString stripped = line.mid(indent);
        if (stripped.trimmed().isEmpty()
            || stripped.trimmed().startsWith(QLatin1Char('#'))
            || stripped.trimmed().startsWith(QStringLiteral("- "))) {
            continue;
        }
        const int colonIndex = findYamlColon(stripped);
        if (colonIndex <= 0) {
            continue;
        }
        while (!stack.isEmpty() && stack.last().indent >= indent) {
            stack.removeLast();
        }
        const QString key = normalizeYamlKey(stripped.left(colonIndex));
        if (key.isEmpty()) {
            continue;
        }
        const QString rawValue = stripped.mid(colonIndex + 1);
        const QString path = joinYamlPath(stack, key);
        const bool hasValue = !rawValue.trimmed().isEmpty();
        nodes.append(YamlNode{
            path,
            stripped.left(colonIndex).trimmed(),
            normalizeYamlScalar(rawValue),
            lineIndex,
            indent,
            hasValue
        });
        if (!hasValue) {
            stack.append(YamlStackEntry{indent, path});
        }
    }
    return nodes;
}

const YamlNode *findYamlNode(const QList<YamlNode> &nodes, const QString &path) {
    for (const YamlNode &node : nodes) {
        if (node.path == path) {
            return &node;
        }
    }
    return nullptr;
}

QString inferFieldType(const QString &value) {
    const QString normalized = value.trimmed().toLower();
    if (normalized == QStringLiteral("true") || normalized == QStringLiteral("false")
        || normalized == QStringLiteral("yes") || normalized == QStringLiteral("no")
        || normalized == QStringLiteral("on") || normalized == QStringLiteral("off")) {
        return QStringLiteral("boolean");
    }
    bool numeric = false;
    value.trimmed().toDouble(&numeric);
    if (numeric && !value.trimmed().isEmpty()) {
        return QStringLiteral("number");
    }
    if (value.size() > 96) {
        return QStringLiteral("textarea");
    }
    return QStringLiteral("text");
}

QString fieldTypeText() {
    return QStringLiteral("text");
}

QString fieldTypeNumber() {
    return QStringLiteral("number");
}

QString fieldTypeBoolean() {
    return QStringLiteral("boolean");
}

QString fieldTypeSelect() {
    return QStringLiteral("select");
}

QString fieldTypeTextarea() {
    return QStringLiteral("textarea");
}

QString fieldTypeList() {
    return QStringLiteral("list");
}

bool isSensitivePath(const QString &path) {
    return path.contains(QStringLiteral("api-key"), Qt::CaseInsensitive)
        || path.contains(QStringLiteral("password"), Qt::CaseInsensitive)
        || path.contains(QStringLiteral("token"), Qt::CaseInsensitive);
}

FieldDefinition field(QString key,
                      QString path,
                      QString label,
                      QString description,
                      QString type,
                      QStringList options = {},
                      QString placeholder = {}) {
    const bool sensitive = isSensitivePath(path);
    return FieldDefinition{
        std::move(key),
        std::move(path),
        std::move(label),
        std::move(description),
        std::move(type),
        std::move(options),
        sensitive,
        std::move(placeholder)
    };
}

FieldDefinition text(const QString &key, const QString &label, const QString &description) {
    return field(key, key, label, description, fieldTypeText());
}

FieldDefinition text(const QString &key, const QString &path, const QString &label, const QString &description) {
    return field(key, path, label, description, fieldTypeText());
}

FieldDefinition number(const QString &key, const QString &label, const QString &description) {
    return field(key, key, label, description, fieldTypeNumber());
}

FieldDefinition booleanField(const QString &key, const QString &label, const QString &description) {
    return field(key, key, label, description, fieldTypeBoolean());
}

FieldDefinition textarea(const QString &key, const QString &label, const QString &description) {
    return field(key, key, label, description, fieldTypeTextarea());
}

FieldDefinition listField(const QString &key, const QString &label, const QString &description) {
    return field(key, key, label, description, fieldTypeList());
}

FieldDefinition selectField(const QString &key,
                            const QString &path,
                            const QString &label,
                            const QString &description,
                            QStringList options) {
    return field(key, path, label, description, fieldTypeSelect(), std::move(options));
}

FieldDefinition modelTier(const QString &key, const QString &label, const QString &description) {
    return selectField(key, key, label, description, QStringList{QStringLiteral("light"), QStringLiteral("heavy")});
}

QList<ConfigDefinition> buildDefinitions() {
    return {
        ConfigDefinition{
            QStringLiteral("application-ai.yaml"),
            QStringLiteral("AI 模型"),
            QStringLiteral("配置轻模型、重模型和 embedding 模型的 API 地址、模型名与密钥。"),
            {
                selectField(QStringLiteral("assistant.mode"), QStringLiteral("assistant.mode"), QStringLiteral("运行模式"), QStringLiteral("api 使用远程 API，local 使用本地模型配置。"), {QStringLiteral("api"), QStringLiteral("local")}),
                text(QStringLiteral("assistant.api.light-model.api-key"), QStringLiteral("assistant.api.light-model.api-key"), QStringLiteral("轻模型 API Key"), QStringLiteral("parser、checker 等低延迟任务使用的模型密钥。")),
                text(QStringLiteral("assistant.api.light-model.base-url"), QStringLiteral("assistant.api.light-model.base-url"), QStringLiteral("轻模型地址"), QStringLiteral("轻模型 OpenAI 兼容接口地址。")),
                text(QStringLiteral("assistant.api.light-model.model-name"), QStringLiteral("assistant.api.light-model.model-name"), QStringLiteral("轻模型名称"), QStringLiteral("轻量任务实际请求的模型名。")),
                text(QStringLiteral("assistant.api.heavy-model.api-key"), QStringLiteral("assistant.api.heavy-model.api-key"), QStringLiteral("重模型 API Key"), QStringLiteral("RP、supervisor 等复杂任务使用的模型密钥。")),
                text(QStringLiteral("assistant.api.heavy-model.base-url"), QStringLiteral("assistant.api.heavy-model.base-url"), QStringLiteral("重模型地址"), QStringLiteral("重模型 OpenAI 兼容接口地址；留空时由配置绑定逻辑继承轻模型。")),
                text(QStringLiteral("assistant.api.heavy-model.model-name"), QStringLiteral("assistant.api.heavy-model.model-name"), QStringLiteral("重模型名称"), QStringLiteral("复杂任务实际请求的模型名。")),
                text(QStringLiteral("assistant.api.embedding-model.api-key"), QStringLiteral("assistant.api.embedding-model.api-key"), QStringLiteral("Embedding API Key"), QStringLiteral("长期记忆向量化使用的密钥。")),
                text(QStringLiteral("assistant.api.embedding-model.base-url"), QStringLiteral("assistant.api.embedding-model.base-url"), QStringLiteral("Embedding 地址"), QStringLiteral("Embedding OpenAI 兼容接口地址。")),
                text(QStringLiteral("assistant.api.embedding-model.model-name"), QStringLiteral("assistant.api.embedding-model.model-name"), QStringLiteral("Embedding 模型"), QStringLiteral("长期记忆向量化使用的模型名。"))
            }
        },
        ConfigDefinition{
            QStringLiteral("application-ai-services.yaml"),
            QStringLiteral("AI 服务分配"),
            QStringLiteral("把 RP、parser、checker、supervisor 分配到轻模型或重模型，并控制应用侧 LLM 日志显示。"),
            {
                modelTier(QStringLiteral("assistant.model-services.rp"), QStringLiteral("RP 模型"), QStringLiteral("角色扮演主回复使用的模型强度。")),
                modelTier(QStringLiteral("assistant.model-services.parser"), QStringLiteral("Parser 模型"), QStringLiteral("把 RP 自然语言动作翻译成游戏操作的模型强度。")),
                modelTier(QStringLiteral("assistant.model-services.checker"), QStringLiteral("Checker 模型"), QStringLiteral("轻量校验与降级链路使用的模型强度。")),
                modelTier(QStringLiteral("assistant.model-services.supervisor"), QStringLiteral("Supervisor 模型"), QStringLiteral("复杂工具任务调度使用的模型强度。")),
                booleanField(QStringLiteral("assistant.llm-logs.console.rp"), QStringLiteral("RP 控制台日志"), QStringLiteral("是否在控制台打印 RP 的最新请求和回复。")),
                booleanField(QStringLiteral("assistant.llm-logs.console.parser"), QStringLiteral("Parser 控制台日志"), QStringLiteral("是否在控制台打印 parser 的最新请求和回复。")),
                booleanField(QStringLiteral("assistant.llm-logs.console.checker"), QStringLiteral("Checker 控制台日志"), QStringLiteral("是否在控制台打印 checker 的最新请求和回复。")),
                booleanField(QStringLiteral("assistant.llm-logs.console.supervisor"), QStringLiteral("Supervisor 控制台日志"), QStringLiteral("是否在控制台打印 supervisor 的最新请求和回复。"))
            }
        },
        ConfigDefinition{
            QStringLiteral("application-tts.yaml"),
            QStringLiteral("语音合成"),
            QStringLiteral("配置当前 TTS 引擎、服务地址和参考音频。"),
            {
                booleanField(QStringLiteral("tts.enabled"), QStringLiteral("启用语音"), QStringLiteral("关闭后不再请求 TTS 合成。")),
                selectField(QStringLiteral("tts.provider"), QStringLiteral("tts.provider"), QStringLiteral("语音引擎"), QStringLiteral("选择当前使用的 TTS provider。"), {QStringLiteral("gpt-sovits-http")}),
                text(QStringLiteral("tts.gpt-so-vits.base-url"), QStringLiteral("tts.gpt-so-vits.base-url"), QStringLiteral("GPT-SoVITS 地址"), QStringLiteral("GPT-SoVITS HTTP 服务地址。")),
                text(QStringLiteral("tts.gpt-so-vits.ref-audio-path"), QStringLiteral("GPT-SoVITS 主参考音频"), QStringLiteral("克隆音色使用的主参考音频路径。")),
                listField(QStringLiteral("tts.gpt-so-vits.aux-ref-audio-paths"), QStringLiteral("GPT-SoVITS 辅助参考音频"), QStringLiteral("每行一个辅助参考音频路径。")),
                textarea(QStringLiteral("tts.gpt-so-vits.prompt-text"), QStringLiteral("GPT-SoVITS 参考文本"), QStringLiteral("参考音频对应文本。")),
                selectField(QStringLiteral("tts.gpt-so-vits.text-lang"), QStringLiteral("tts.gpt-so-vits.text-lang"), QStringLiteral("合成文本语言"), QStringLiteral("待合成文本语言。"), {QStringLiteral("zh"), QStringLiteral("en"), QStringLiteral("ja")}),
                selectField(QStringLiteral("tts.gpt-so-vits.prompt-lang"), QStringLiteral("tts.gpt-so-vits.prompt-lang"), QStringLiteral("参考文本语言"), QStringLiteral("参考音频文本语言。"), {QStringLiteral("zh"), QStringLiteral("en"), QStringLiteral("ja")}),
            }
        },
        ConfigDefinition{
            QStringLiteral("mcp-catalog.yaml"),
            QStringLiteral("MCP 游戏目录"),
            QStringLiteral("配置本地 MCP 游戏服务展示信息。"),
            {
                text(QStringLiteral("mcp.catalog.STS2MCP.display-name"), QStringLiteral("显示名称"), QStringLiteral("前端和日志里展示的游戏名称。")),
                textarea(QStringLiteral("mcp.catalog.STS2MCP.description"), QStringLiteral("说明"), QStringLiteral("MCP 条目的用途和安装前提。")),
            }
        }
    };
}

const ConfigDefinition *findDefinition(const QList<ConfigDefinition> &definitions, const QString &fileName) {
    for (const ConfigDefinition &definition : definitions) {
        if (definition.fileName == fileName) {
            return &definition;
        }
    }
    return nullptr;
}

QVariant normalizedFieldValue(const QString &type, const QString &value) {
    if (type == QStringLiteral("boolean")) {
        return parseBoolean(value);
    }
    return value;
}

QString parseInlineListItem(QString value) {
    value = normalizeYamlScalar(value.trimmed());
    return value;
}

QStringList parseInlineList(const QString &rawValue) {
    QString body = rawValue.trimmed();
    if (!body.startsWith(QLatin1Char('[')) || !body.endsWith(QLatin1Char(']'))) {
        return {};
    }
    body = body.mid(1, body.size() - 2).trimmed();
    if (body.isEmpty()) {
        return {};
    }
    QStringList values;
    QString current;
    bool quoted = false;
    QChar quoteChar;
    for (int index = 0; index < body.size(); ++index) {
        const QChar ch = body.at(index);
        if ((ch == QLatin1Char('"') || ch == QLatin1Char('\'')) && (index == 0 || body.at(index - 1) != QLatin1Char('\\'))) {
            if (!quoted) {
                quoted = true;
                quoteChar = ch;
            } else if (quoteChar == ch) {
                quoted = false;
            }
        }
        if (ch == QLatin1Char(',') && !quoted) {
            const QString item = parseInlineListItem(current);
            if (!item.isEmpty()) {
                values.append(item);
            }
            current.clear();
            continue;
        }
        current.append(ch);
    }
    const QString item = parseInlineListItem(current);
    if (!item.isEmpty()) {
        values.append(item);
    }
    return values;
}

QString readScalarValue(const QList<YamlNode> &nodes, const QString &path) {
    const YamlNode *node = findYamlNode(nodes, path);
    if (node == nullptr || !node->hasValue) {
        return QString();
    }
    return node->value;
}

QString readListValue(const QStringList &lines, const QList<YamlNode> &nodes, const QString &path) {
    const YamlNode *node = findYamlNode(nodes, path);
    if (node == nullptr) {
        return QString();
    }
    if (node->hasValue && node->value.startsWith(QLatin1Char('[')) && node->value.endsWith(QLatin1Char(']'))) {
        return parseInlineList(node->value).join(QLatin1Char('\n'));
    }
    QStringList values;
    for (int index = node->lineIndex + 1; index < lines.size(); ++index) {
        const QString line = lines.at(index);
        const int indent = leadingSpaces(line);
        const QString stripped = line.mid(indent);
        if (stripped.trimmed().isEmpty() || stripped.trimmed().startsWith(QLatin1Char('#'))) {
            continue;
        }
        const int colonIndex = findYamlColon(stripped);
        if (colonIndex > 0 && indent <= node->indent) {
            break;
        }
        if (stripped.startsWith(QStringLiteral("- ")) && indent > node->indent) {
            values.append(normalizeYamlScalar(stripped.mid(2)));
        }
    }
    return values.join(QLatin1Char('\n'));
}

QVariantMap renderField(const YamlScalar &scalar) {
    const QString type = inferFieldType(scalar.value);
    QVariantMap field;
    field.insert(QStringLiteral("key"), scalar.path);
    field.insert(QStringLiteral("type"), type);
    field.insert(QStringLiteral("label"), scalar.path);
    field.insert(QStringLiteral("description"), QString());
    field.insert(QStringLiteral("path"), scalar.path);
    field.insert(QStringLiteral("placeholder"), QString());
    field.insert(QStringLiteral("sensitive"), scalar.path.contains(QStringLiteral("api-key"), Qt::CaseInsensitive)
                 || scalar.path.contains(QStringLiteral("password"), Qt::CaseInsensitive)
                 || scalar.path.contains(QStringLiteral("token"), Qt::CaseInsensitive));
    field.insert(QStringLiteral("value"), normalizedFieldValue(type, scalar.value));
    return field;
}

QVariantMap renderDefinedField(const FieldDefinition &definition, const QStringList &lines, const QList<YamlNode> &nodes) {
    const QString value = definition.type == fieldTypeList()
        ? readListValue(lines, nodes, definition.path)
        : readScalarValue(nodes, definition.path);
    QVariantMap field;
    field.insert(QStringLiteral("key"), definition.key);
    field.insert(QStringLiteral("type"), definition.type);
    field.insert(QStringLiteral("label"), definition.label);
    field.insert(QStringLiteral("description"), definition.description);
    field.insert(QStringLiteral("path"), definition.path);
    field.insert(QStringLiteral("placeholder"), definition.placeholder);
    field.insert(QStringLiteral("options"), definition.options);
    field.insert(QStringLiteral("sensitive"), definition.sensitive);
    field.insert(QStringLiteral("value"), normalizedFieldValue(definition.type, value));
    return field;
}

QString configTitle(const QString &fileName, const QList<ConfigDefinition> &definitions) {
    for (const ConfigDefinition &definition : definitions) {
        if (definition.fileName == fileName) {
            return definition.title;
        }
    }
    QString baseName = QFileInfo(fileName).completeBaseName();
    if (baseName.startsWith(QStringLiteral("application-"))) {
        baseName.remove(0, QStringLiteral("application-").size());
    }
    if (baseName == QStringLiteral("application")) {
        return QStringLiteral("application");
    }
    return baseName;
}

QString resolveLocalConfigDir() {
    const QString envConfigDir = qEnvironmentVariable("ARCLIGHT_CONFIG_DIR").trimmed();
    if (!envConfigDir.isEmpty()) {
        return QDir(envConfigDir).absolutePath();
    }

    QStringList searchRoots;
    searchRoots << QDir::currentPath() << QCoreApplication::applicationDirPath();
    for (const QString &root : searchRoots) {
        QDir dir(root);
        for (int depth = 0; depth < 6; ++depth) {
            const QString candidate = dir.absoluteFilePath(QStringLiteral("config"));
            if (QFileInfo(candidate).isDir()) {
                return QDir(candidate).absolutePath();
            }
            if (!dir.cdUp()) {
                break;
            }
        }
    }
    return QString();
}

bool readUtf8Lines(const QString &filePath, QStringList *lines, QString *errorMessage) {
    QFile file(filePath);
    if (!file.open(QIODevice::ReadOnly | QIODevice::Text)) {
        if (errorMessage != nullptr) {
            *errorMessage = QStringLiteral("读取配置失败: %1").arg(filePath);
        }
        return false;
    }
    const QString text = QString::fromUtf8(file.readAll());
    *lines = text.split(QLatin1Char('\n'));
    if (!lines->isEmpty() && lines->last().isEmpty()) {
        lines->removeLast();
    }
    return true;
}

bool writeUtf8Lines(const QString &filePath, const QStringList &lines, QString *errorMessage) {
    QFile file(filePath);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Text | QIODevice::Truncate)) {
        if (errorMessage != nullptr) {
            *errorMessage = QStringLiteral("写入配置失败: %1").arg(filePath);
        }
        return false;
    }
    file.write(lines.join(QLatin1Char('\n')).toUtf8());
    file.write("\n");
    return true;
}

QVariantMap renderConfigPage(const QString &configDir, const ConfigDefinition &definition, QString *errorMessage) {
    const QString filePath = QDir(configDir).absoluteFilePath(definition.fileName);
    QStringList lines;
    if (!readUtf8Lines(filePath, &lines, errorMessage)) {
        return {};
    }

    QVariantList fields;
    const QList<YamlNode> nodes = parseYamlNodes(lines);
    fields.reserve(definition.fields.size());
    for (const FieldDefinition &field : definition.fields) {
        fields.append(renderDefinedField(field, lines, nodes));
    }

    QVariantMap page;
    page.insert(QStringLiteral("fileName"), definition.fileName);
    page.insert(QStringLiteral("title"), definition.title);
    page.insert(QStringLiteral("description"), definition.description);
    page.insert(QStringLiteral("path"), filePath);
    page.insert(QStringLiteral("fields"), fields);
    return page;
}

QVariantMap renderFallbackConfigPage(const QString &configDir, const QString &fileName, QString *errorMessage) {
    const QString filePath = QDir(configDir).absoluteFilePath(fileName);
    QStringList lines;
    if (!readUtf8Lines(filePath, &lines, errorMessage)) {
        return {};
    }

    QVariantList fields;
    const QList<YamlScalar> scalars = parseYamlScalars(lines);
    fields.reserve(scalars.size());
    for (const YamlScalar &scalar : scalars) {
        fields.append(renderField(scalar));
    }

    QVariantMap page;
    page.insert(QStringLiteral("fileName"), fileName);
    page.insert(QStringLiteral("title"), configTitle(fileName));
    page.insert(QStringLiteral("description"), QStringLiteral("本地 YAML 配置文件"));
    page.insert(QStringLiteral("path"), filePath);
    page.insert(QStringLiteral("fields"), fields);
    return page;
}

QString formatScalar(const QVariant &value) {
    if (value.typeId() == QMetaType::Bool) {
        return value.toBool() ? QStringLiteral("true") : QStringLiteral("false");
    }
    const QString text = value.toString().trimmed();
    if (text.isEmpty()) {
        return QStringLiteral("\"\"");
    }
    bool safe = true;
    for (const QChar ch : text) {
        if (!(ch.isLetterOrNumber()
              || ch == QLatin1Char('_')
              || ch == QLatin1Char('.')
              || ch == QLatin1Char('/')
              || ch == QLatin1Char(':')
              || ch == QLatin1Char('@')
              || ch == QLatin1Char('$')
              || ch == QLatin1Char('{')
              || ch == QLatin1Char('}')
              || ch == QLatin1Char('\\')
              || ch == QLatin1Char('-')
              || ch == QLatin1Char('[')
              || ch == QLatin1Char(']')
              || ch == QLatin1Char(','))) {
            safe = false;
            break;
        }
    }
    if (safe) {
        return text;
    }
    QString escaped = text;
    escaped.replace(QLatin1Char('\\'), QStringLiteral("\\\\"));
    escaped.replace(QLatin1Char('"'), QStringLiteral("\\\""));
    return QStringLiteral("\"%1\"").arg(escaped);
}

bool updateYamlScalar(QStringList *lines, const QString &path, const QVariant &value) {
    QList<YamlStackEntry> stack;
    for (int lineIndex = 0; lineIndex < lines->size(); ++lineIndex) {
        const QString line = lines->at(lineIndex);
        const int indent = leadingSpaces(line);
        const QString stripped = line.mid(indent);
        if (stripped.trimmed().isEmpty()
            || stripped.trimmed().startsWith(QLatin1Char('#'))
            || stripped.trimmed().startsWith(QStringLiteral("- "))) {
            continue;
        }
        const int colonIndex = findYamlColon(stripped);
        if (colonIndex <= 0) {
            continue;
        }
        while (!stack.isEmpty() && stack.last().indent >= indent) {
            stack.removeLast();
        }
        const QString key = normalizeYamlKey(stripped.left(colonIndex));
        const QString currentPath = joinYamlPath(stack, key);
        const QString rawValue = stripped.mid(colonIndex + 1);
        if (rawValue.trimmed().isEmpty()) {
            stack.append(YamlStackEntry{indent, currentPath});
            continue;
        }
        if (currentPath == path) {
            (*lines)[lineIndex] = QString(line.left(indent) + stripped.left(colonIndex).trimmed()
                                          + QStringLiteral(": ") + formatScalar(value));
            return true;
        }
    }
    return false;
}

bool updateYamlList(QStringList *lines, const QString &path, const QVariant &value) {
    const QList<YamlNode> nodes = parseYamlNodes(*lines);
    const YamlNode *node = findYamlNode(nodes, path);
    if (node == nullptr) {
        return false;
    }

    int endIndex = node->lineIndex + 1;
    while (endIndex < lines->size()) {
        const QString line = lines->at(endIndex);
        const int indent = leadingSpaces(line);
        const QString stripped = line.mid(indent);
        if (!stripped.trimmed().isEmpty()
            && !stripped.trimmed().startsWith(QLatin1Char('#'))
            && findYamlColon(stripped) > 0
            && indent <= node->indent) {
            break;
        }
        ++endIndex;
    }

    QStringList values = value.toString().split(QLatin1Char('\n'));
    values.removeIf([](const QString &item) {
        return item.trimmed().isEmpty();
    });

    QStringList replacement;
    replacement.append(QString(lines->at(node->lineIndex).left(node->indent) + node->key + QStringLiteral(":")));
    for (const QString &item : values) {
        replacement.append(QString(QString(node->indent + 2, QLatin1Char(' '))
                                   + QStringLiteral("- ")
                                   + formatScalar(item.trimmed())));
    }

    lines->erase(lines->begin() + node->lineIndex, lines->begin() + endIndex);
    for (int index = 0; index < replacement.size(); ++index) {
        lines->insert(node->lineIndex + index, replacement.at(index));
    }
    return true;
}

bool parseBoolean(const QString &value) {
    const QString normalized = value.trimmed().toLower();
    return normalized == QStringLiteral("true")
        || normalized == QStringLiteral("yes")
        || normalized == QStringLiteral("1")
        || normalized == QStringLiteral("on");
}
} // namespace configcatalog