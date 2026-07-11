import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Item {
    id: root
    property var host
    property var tokens
    Layout.preferredWidth: root.host.sp(238)
    Layout.fillHeight: true

    ColumnLayout {
        anchors.fill: parent
        anchors.leftMargin: root.host.sp(22)
        anchors.rightMargin: root.host.sp(18)
        anchors.topMargin: root.host.sp(24)
        anchors.bottomMargin: root.host.sp(18)
        spacing: root.host.sp(4)

        SettingsNavButton {
            host: root.host
            tokens: root.tokens
            viewKey: "frontend"
            number: "01"
            kicker: "GENERAL"
            label: "常规"
            glyphKind: "general"
        }
        SettingsNavButton {
            host: root.host
            tokens: root.tokens
            viewKey: "backend"
            number: "02"
            kicker: "MODELS & SERVICES"
            label: "模型与服务"
            glyphKind: "services"
        }
        SettingsNavButton {
            host: root.host
            tokens: root.tokens
            viewKey: "game"
            number: "03"
            kicker: "GAME LINK"
            label: "游戏联动"
            glyphKind: "game"
        }
        SettingsNavButton {
            host: root.host
            tokens: root.tokens
            viewKey: "voice"
            number: "04"
            kicker: "VOICE & AUDIO"
            label: "语音与音频"
            glyphKind: "voice"
        }

        Item { Layout.fillHeight: true }

        Text {
            Layout.fillWidth: true
            text: "RLY-Σ 22B\nFIELD INDEX 0.93"
            color: root.tokens.inkAlpha(0.24)
            font.family: root.tokens.monoFont
            font.pixelSize: root.host.sp(8)
            lineHeight: 1.45
        }
    }
}
