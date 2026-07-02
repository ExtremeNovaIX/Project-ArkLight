#include "config/ConfigCatalogSupport.h"

#include <QtTest/QtTest>

class ConfigCatalogSupportTest final : public QObject {
    Q_OBJECT

private slots:
    void updatesNestedScalar();
    void rewritesBlockList();
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

QTEST_GUILESS_MAIN(ConfigCatalogSupportTest)
#include "ConfigCatalogSupportTest.moc"