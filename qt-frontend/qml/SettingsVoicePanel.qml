import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ColumnLayout {
    id: root
    property var host
    property var tokens
    visible: root.host.activeView === "voice"
    Layout.fillWidth: true
    spacing: root.host.sp(24)

    SettingsSectionTitle {

        host: root.host

        tokens: root.tokens

        eyebrow: "Voice Debug"; title: "语音调试" }

    SettingsUiCard {

        host: root.host

        tokens: root.tokens

        title: "ASR Runtime"
        Text {
            Layout.fillWidth: true
            text: "sherpa-qwen-onnx / ASR v2 / partial 500ms / segment 3000ms"
            color: root.tokens.inkAlpha(0.62)
            font.family: root.tokens.monoFont
            font.pixelSize: root.host.sp(11)
            wrapMode: Text.WrapAnywhere
        }
    }

    SettingsBlackToggle {

        host: root.host

        tokens: root.tokens

        title: "只调试不执行"
        detail: "运行语音识别和意图路由，但不把识别文本发给大模型，也不执行游戏副作用。"
        checked: frontendSettings.voiceDebugEnabled
        onToggled: function(value) { frontendSettings.voiceDebugEnabled = value }
    }

    GridLayout {
        Layout.fillWidth: true
        columns: root.host.width > root.host.sp(1080) ? 2 : 1
        columnSpacing: root.host.sp(20)
        rowSpacing: root.host.sp(20)
        SettingsUiCard {
            host: root.host
            tokens: root.tokens
            title: "ASR Runtime"
            Text {
                Layout.fillWidth: true
                text: "sherpa-qwen-onnx / ASR v2 / port 6006"
                color: root.tokens.inkAlpha(0.55)
                font.family: root.tokens.sansFont
                font.pixelSize: root.host.sp(12)
                wrapMode: Text.WrapAnywhere
            }
        }
        SettingsUiCard {
            host: root.host
            tokens: root.tokens
            title: "音频输入设备"
            SettingsUiCombo {
                host: root.host
                tokens: root.tokens
                model: sttAudio.deviceNames
                currentIndex: Math.max(0, Math.min(sttAudio.selectedDeviceIndex, count - 1))
                onActivated: sttAudio.selectedDeviceIndex = currentIndex
            }
        }
    }

    SettingsUiCard {

        host: root.host

        tokens: root.tokens

        title: "实时链路"
        RowLayout {
            Layout.fillWidth: true
            spacing: root.host.sp(12)
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: root.host.sp(34)
                radius: root.host.sp(17)
                color: root.tokens.inkAlpha(0.08)
                clip: true
                Rectangle {
                    anchors.left: parent.left
                    anchors.top: parent.top
                    anchors.bottom: parent.bottom
                    width: parent.width * sttAudio.audioLevel
                    radius: root.host.sp(17)
                    color: sttAudio.running ? root.tokens.teal : root.tokens.inkAlpha(0.4)
                }
                Text {
                    anchors.centerIn: parent
                    text: sttAudio.backendConnected ? "后端识别" : "本地预览"
                    color: root.tokens.ink
                    font.family: root.tokens.monoFont
                    font.pixelSize: root.host.sp(10)
                    font.weight: Font.Black
                }
            }
            SettingsUiButton {
                host: root.host
                tokens: root.tokens
                text: "刷新"
                enabled: !sttAudio.running
                onClicked: {
                    sttAudio.refreshDevices()
                }
            }
            SettingsUiButton {
                host: root.host
                tokens: root.tokens
                text: sttAudio.running ? "停止" : "监听"
                primary: sttAudio.running
                onClicked: sttAudio.running ? sttAudio.stop() : sttAudio.start()
            }
        }
        Text {
            Layout.fillWidth: true
            text: sttAudio.statusText
            color: root.tokens.inkAlpha(0.55)
            font.family: root.tokens.sansFont
            font.pixelSize: root.host.sp(12)
            wrapMode: Text.WrapAnywhere
        }
        Text {
            Layout.fillWidth: true
            text: sttAudio.transcriptText.length > 0 ? sttAudio.transcriptText : "暂无识别文本。"
            color: root.tokens.ink
            font.family: root.tokens.sansFont
            font.pixelSize: root.host.sp(14)
            wrapMode: Text.WordWrap
        }
    }

    SettingsUiCard {

        host: root.host

        tokens: root.tokens

        title: "识别审计"
        RowLayout {
            Layout.fillWidth: true
            Text {
                Layout.fillWidth: true
                text: "事件数: " + sttAudio.debugEventCount
                color: root.tokens.ink
                font.family: root.tokens.sansFont
                font.pixelSize: root.host.sp(13)
                font.weight: Font.Black
            }
            SettingsUiButton {
                host: root.host
                tokens: root.tokens
                text: "导出"; onClicked: sttAudio.exportDebugEvents() }
            SettingsUiButton {
                host: root.host
                tokens: root.tokens
                text: "清空"; onClicked: sttAudio.clearDebugEvents() }
        }
        ListView {
            Layout.fillWidth: true
            Layout.preferredHeight: root.host.sp(260)
            clip: true
            spacing: root.host.sp(8)
            model: sttAudio.debugEvents
            delegate: Rectangle {
                width: ListView.view.width
                implicitHeight: eventText.implicitHeight + root.host.sp(20)
                color: root.tokens.whiteAlpha(0.8)
                border.color: root.tokens.inkAlpha(0.15)
                border.width: 1
                Text {
                    id: eventText
                    anchors.fill: parent
                    anchors.margins: root.host.sp(10)
                    text: modelData
                    color: root.tokens.ink
                    font.family: root.tokens.monoFont
                    font.pixelSize: root.host.sp(11)
                    wrapMode: Text.WrapAnywhere
                }
            }
        }
    }
}
