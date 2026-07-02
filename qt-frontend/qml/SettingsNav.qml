import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: root
    property var host
    property var tokens
    Layout.preferredWidth: root.host.sp(320)
    Layout.fillHeight: true
    radius: root.host.sp(root.tokens.radiusFrame)
    color: root.tokens.panelAlt
    border.color: root.tokens.inkAlpha(0.1)
    border.width: 1

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: root.host.sp(24)
        spacing: root.host.sp(12)

        Text {
            Layout.fillWidth: true
            text: "系统设置"
            color: root.tokens.teal
            font.family: root.tokens.monoFont
            font.pixelSize: root.host.sp(10)
            font.capitalization: Font.AllUppercase
            elide: Text.ElideRight
        }
        Text {
            Layout.fillWidth: true
            text: "设置面板"
            color: root.tokens.ink
            font.family: root.tokens.sansFont
            font.pixelSize: root.host.sp(30)
            font.weight: Font.Black
            wrapMode: Text.WordWrap
        }
        Item { Layout.preferredHeight: root.host.sp(16) }

        SettingsNavButton {

            host: root.host

            tokens: root.tokens

            viewKey: "frontend"; kicker: "本地"; label: "前端设置" }
        SettingsNavButton {
            host: root.host
            tokens: root.tokens
            viewKey: "backend"; kicker: "Config"; label: "本地配置" }
        SettingsNavButton {
            host: root.host
            tokens: root.tokens
            viewKey: "game"; kicker: "Game"; label: "游戏模式" }
        SettingsNavButton {
            host: root.host
            tokens: root.tokens
            viewKey: "voice"; kicker: "Voice"; label: "语音调试" }

        Item { Layout.fillHeight: true }
    }
}
