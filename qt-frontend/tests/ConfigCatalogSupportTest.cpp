#include "config/ConfigCatalogSupport.h"

#include <QFile>
#include <QtTest/QtTest>

class ConfigCatalogSupportTest final : public QObject {
    Q_OBJECT

private slots:
    void updatesNestedScalar();
    void rewritesBlockList();
    void settingsHistoryUsesDropdownPopup();
    void settingsVisualLanguageUsesUserFacingNavigation();
};

void ConfigCatalogSupportTest::updatesNestedScalar() {
    QStringList lines{
        QStringLiteral("assistant:"),
        QStringLiteral("  mode: api")
    };

    QVERIFY(configcatalog::updateYamlScalar(&lines, QStringLiteral("assistant.mode"), QStringLiteral("local")));
    QCOMPARE(lines.at(1), QStringLiteral("  mode: local"));
}

void ConfigCatalogSupportTest::rewritesBlockList() {
    QStringList lines{
        QStringLiteral("tts:"),
        QStringLiteral("  refs:"),
        QStringLiteral("    - old.wav"),
        QStringLiteral("  enabled: true")
    };

    QVERIFY(configcatalog::updateYamlList(&lines, QStringLiteral("tts.refs"), QStringLiteral("new-a.wav\nnew-b.wav")));
    QCOMPARE(lines, QStringList({
        QStringLiteral("tts:"),
        QStringLiteral("  refs:"),
        QStringLiteral("    - new-a.wav"),
        QStringLiteral("    - new-b.wav"),
        QStringLiteral("  enabled: true")
    }));
}

void ConfigCatalogSupportTest::settingsHistoryUsesDropdownPopup() {
    QFile file(QStringLiteral(ARKLIGHT_QML_DIR) + QStringLiteral("/SettingsConfigFieldEditor.qml"));
    QVERIFY2(file.open(QIODevice::ReadOnly | QIODevice::Text), qPrintable(file.fileName()));
    const QString qml = QString::fromUtf8(file.readAll());

    QVERIFY(qml.contains(QStringLiteral("id: historyPopup")));
    QVERIFY(qml.contains(QStringLiteral("Popup {")));
    QVERIFY(qml.contains(QStringLiteral("historyPopup.open()")));
    QVERIFY(!qml.contains(QStringLiteral("id: historyButton")));
}
void ConfigCatalogSupportTest::settingsVisualLanguageUsesUserFacingNavigation() {
    QFile navFile(QStringLiteral(ARKLIGHT_QML_DIR) + QStringLiteral("/SettingsNav.qml"));
    QVERIFY2(navFile.open(QIODevice::ReadOnly | QIODevice::Text), qPrintable(navFile.fileName()));
    const QString navQml = QString::fromUtf8(navFile.readAll());

    QFile buttonFile(QStringLiteral(ARKLIGHT_QML_DIR) + QStringLiteral("/SettingsNavButton.qml"));
    QVERIFY2(buttonFile.open(QIODevice::ReadOnly | QIODevice::Text), qPrintable(buttonFile.fileName()));
    const QString buttonQml = QString::fromUtf8(buttonFile.readAll());

    QVERIFY(buttonQml.contains(QStringLiteral("SettingsCategoryGlyph")));
    QVERIFY(navQml.contains(QStringLiteral("常规")));
    QVERIFY(navQml.contains(QStringLiteral("模型与服务")));
    QVERIFY(navQml.contains(QStringLiteral("游戏联动")));
    QVERIFY(navQml.contains(QStringLiteral("语音与音频")));
    QVERIFY(!navQml.contains(QStringLiteral("前端设置")));
    QVERIFY(!navQml.contains(QStringLiteral("本地配置")));
    QVERIFY(!navQml.contains(QStringLiteral("语音调试")));
}

QTEST_GUILESS_MAIN(ConfigCatalogSupportTest)
#include "ConfigCatalogSupportTest.moc"
