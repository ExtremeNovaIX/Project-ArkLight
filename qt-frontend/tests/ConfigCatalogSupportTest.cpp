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
    void mainSurfaceUsesMechanicalCounter();
    void mainDecorationsUseExplicitEdgeZones();
    void ambientFlowAndScrollbarStayAtEdges();
    void focusFeedbackUsesGlowWithoutGeometryMovement();
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

void ConfigCatalogSupportTest::mainSurfaceUsesMechanicalCounter() {
    QFile surfaceFile(QStringLiteral(ARKLIGHT_QML_DIR) + QStringLiteral("/ChatSurface.qml"));
    QVERIFY2(surfaceFile.open(QIODevice::ReadOnly | QIODevice::Text), qPrintable(surfaceFile.fileName()));
    const QString surfaceQml = QString::fromUtf8(surfaceFile.readAll());

    QFile clockFile(QStringLiteral(ARKLIGHT_QML_DIR) + QStringLiteral("/MechanicalCounterClock.qml"));
    QVERIFY2(clockFile.open(QIODevice::ReadOnly | QIODevice::Text), qPrintable(clockFile.fileName()));
    const QString clockQml = QString::fromUtf8(clockFile.readAll());

    QVERIFY(surfaceQml.contains(QStringLiteral("MechanicalCounterClock {")));
    QVERIFY(surfaceQml.contains(QStringLiteral("scheduleRelayTick()")));
    QVERIFY(!surfaceQml.contains(QStringLiteral("text: surface.relayTime")));
    QVERIFY(clockQml.contains(QStringLiteral("id: digitCell")));
    QVERIFY(clockQml.contains(QStringLiteral("model: clock.value.length")));
    QFile textureFile(QStringLiteral(ARKLIGHT_QML_DIR)
                      + QStringLiteral("/../assets/ui/clock/split-flap-cell-texture.png"));
    QFile cmakeFile(QStringLiteral(ARKLIGHT_QML_DIR) + QStringLiteral("/../CMakeLists.txt"));
    QVERIFY2(cmakeFile.open(QIODevice::ReadOnly | QIODevice::Text), qPrintable(cmakeFile.fileName()));
    const QString cmake = QString::fromUtf8(cmakeFile.readAll());

    QVERIFY(clockQml.contains(QStringLiteral("property string previousCharacter")));
    QVERIFY(clockQml.contains(QStringLiteral("property string pendingCharacter")));
    QVERIFY(clockQml.contains(QStringLiteral("property real topFlipAngle")));
    QVERIFY(clockQml.contains(QStringLiteral("property real bottomFlipAngle: -90")));
    QVERIFY(clockQml.contains(QStringLiteral("property int flipDuration: 142")));
    QCOMPARE(clockQml.count(QStringLiteral("layer.enabled: digitSlot.flipping")), 2);
    QVERIFY(clockQml.contains(QStringLiteral("opacity: 0.56")));
    QVERIFY(clockQml.contains(QStringLiteral("SequentialAnimation")));
    QVERIFY(clockQml.contains(QStringLiteral("Rotation {")));
    QCOMPARE(clockQml.count(QStringLiteral("axis.x: 1")), 2);
    QVERIFY(clockQml.contains(QStringLiteral("duration: clock.flipDuration * 0.44")));
    QVERIFY(clockQml.contains(QStringLiteral("duration: clock.flipDuration * 0.56")));
    QVERIFY(clockQml.contains(QStringLiteral("if (flipAnimation.running)")));
    QVERIFY(clockQml.contains(QStringLiteral("pendingCharacter = characterValue")));
    QVERIFY(clockQml.contains(QStringLiteral("function finishFlip()")));
    QVERIFY(clockQml.contains(QStringLiteral("Qt.callLater")));
    QVERIFY(!clockQml.contains(QStringLiteral("flipAnimation.stop()\n                            displayedCharacter = incomingCharacter")));
    QVERIFY(clockQml.contains(QStringLiteral("source: \"../assets/ui/clock/split-flap-cell-texture.png\"")));
    QVERIFY(!clockQml.contains(QStringLiteral("SmoothedAnimation")));
    QVERIFY2(textureFile.exists(), qPrintable(textureFile.fileName()));
    QVERIFY(cmake.contains(QStringLiteral("assets/ui/clock/split-flap-cell-texture.png")));
}

void ConfigCatalogSupportTest::mainDecorationsUseExplicitEdgeZones() {
    QFile surfaceFile(QStringLiteral(ARKLIGHT_QML_DIR) + QStringLiteral("/ChatSurface.qml"));
    QVERIFY2(surfaceFile.open(QIODevice::ReadOnly | QIODevice::Text), qPrintable(surfaceFile.fileName()));
    const QString surfaceQml = QString::fromUtf8(surfaceFile.readAll());

    QFile backdropFile(QStringLiteral(ARKLIGHT_QML_DIR) + QStringLiteral("/TechnicalBackdrop.qml"));
    QVERIFY2(backdropFile.open(QIODevice::ReadOnly | QIODevice::Text), qPrintable(backdropFile.fileName()));
    const QString backdropQml = QString::fromUtf8(backdropFile.readAll());

    QVERIFY(surfaceQml.contains(QStringLiteral("id: pioneerRail")));
    QVERIFY(backdropQml.contains(QStringLiteral("id: megastructureZone")));
    QVERIFY(backdropQml.contains(QStringLiteral("id: contourZone")));
    QVERIFY(backdropQml.contains(QStringLiteral("id: rasterGeometryZone")));
}

void ConfigCatalogSupportTest::ambientFlowAndScrollbarStayAtEdges() {
    QFile surfaceFile(QStringLiteral(ARKLIGHT_QML_DIR) + QStringLiteral("/ChatSurface.qml"));
    QVERIFY2(surfaceFile.open(QIODevice::ReadOnly | QIODevice::Text), qPrintable(surfaceFile.fileName()));
    const QString surfaceQml = QString::fromUtf8(surfaceFile.readAll());

    QFile stageFile(QStringLiteral(ARKLIGHT_QML_DIR) + QStringLiteral("/CharacterStage.qml"));
    QVERIFY2(stageFile.open(QIODevice::ReadOnly | QIODevice::Text), qPrintable(stageFile.fileName()));
    const QString stageQml = QString::fromUtf8(stageFile.readAll());

    QFile flowFile(QStringLiteral(ARKLIGHT_QML_DIR) + QStringLiteral("/AmbientSignalFlow.qml"));
    QVERIFY2(flowFile.open(QIODevice::ReadOnly | QIODevice::Text), qPrintable(flowFile.fileName()));
    const QString flowQml = QString::fromUtf8(flowFile.readAll());

    QVERIFY(surfaceQml.contains(QStringLiteral("AmbientSignalFlow {")));
    QVERIFY(stageQml.contains(QStringLiteral("AmbientSignalFlow {")));
    QVERIFY(surfaceQml.contains(QStringLiteral("parent: chatList.parent")));
    QVERIFY(surfaceQml.contains(QStringLiteral("anchors.rightMargin: surface.sp(6)")));
    QVERIFY(!surfaceQml.contains(QStringLiteral("anchors.rightMargin: -surface.sp(20)")));
    QVERIFY(flowQml.contains(QStringLiteral("property real edgeFade")));
}

void ConfigCatalogSupportTest::focusFeedbackUsesGlowWithoutGeometryMovement() {
    QFile stageFile(QStringLiteral(ARKLIGHT_QML_DIR) + QStringLiteral("/CharacterStage.qml"));
    QVERIFY2(stageFile.open(QIODevice::ReadOnly | QIODevice::Text), qPrintable(stageFile.fileName()));
    const QString stageQml = QString::fromUtf8(stageFile.readAll());

    QFile glyphFile(QStringLiteral(ARKLIGHT_QML_DIR) + QStringLiteral("/SettingsCategoryGlyph.qml"));
    QVERIFY2(glyphFile.open(QIODevice::ReadOnly | QIODevice::Text), qPrintable(glyphFile.fileName()));
    const QString glyphQml = QString::fromUtf8(glyphFile.readAll());

    QVERIFY(stageQml.contains(QStringLiteral("id: characterFocusGlow")));
    QVERIFY(!stageQml.contains(QStringLiteral("stageHover.hovered ? 1.018 : 1")));
    QVERIFY(glyphQml.contains(QStringLiteral("property real glowStrength")));
    QVERIFY(glyphQml.contains(QStringLiteral("shadowBlur")));
    QVERIFY(!glyphQml.contains(QStringLiteral("property real assembly")));
}
QTEST_GUILESS_MAIN(ConfigCatalogSupportTest)
#include "ConfigCatalogSupportTest.moc"
