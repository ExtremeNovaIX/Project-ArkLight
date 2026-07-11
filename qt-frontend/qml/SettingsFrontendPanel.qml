import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ColumnLayout {
    id: root
    property var host
    property var tokens
    visible: root.host.activeView === "frontend"
    Layout.fillWidth: true
    spacing: root.host.sp(24)

    RowLayout {
        Layout.fillWidth: true
        spacing: root.host.sp(20)
        SettingsSectionTitle {
            host: root.host
            tokens: root.tokens
            eyebrow: "01 / GENERAL"; title: "常规" }
        SettingsUiButton {
            host: root.host
            tokens: root.tokens
            text: "重置"
            onClicked: frontendSettings.reset()
        }
    }

    GridLayout {
        Layout.fillWidth: true
        columns: 1
        columnSpacing: root.host.sp(20)
        rowSpacing: root.host.sp(20)

        SettingsUiCard {

            host: root.host

            tokens: root.tokens

            title: "界面"
            detail: "选择应用的显示风格。"
            SettingsUiCombo {
                host: root.host
                tokens: root.tokens
                model: ["默认主题"]
                currentIndex: 0
                onActivated: frontendSettings.themeId = "arklight"
            }
        }
        SettingsUiCard {
            host: root.host
            tokens: root.tokens
            title: "角色"
            detail: "选择当前对话使用的角色。"
            SettingsUiCombo {
                host: root.host
                tokens: root.tokens
                model: root.host.characterNameModel()
                currentIndex: Math.max(0, root.host.characterNameModel().indexOf(frontendSettings.characterName))
                onActivated: {
                    if (currentText === "未选择") {
                        frontendSettings.characterName = ""
                    } else {
                        frontendSettings.characterName = currentText
                        chatSession.selectCharacter(currentText)
                    }
                }
            }
        }
        SettingsUiCard {
            host: root.host
            tokens: root.tokens
            title: "服务地址"
            detail: "用于连接本地服务与同步对话。"
            SettingsUiField {
                host: root.host
                tokens: root.tokens
                text: frontendSettings.backendBaseUrl
                onEditingFinished: frontendSettings.backendBaseUrl = text
            }
        }
        SettingsUiCard {
            host: root.host
            tokens: root.tokens
            title: "会话编号"
            detail: "用于区分当前对话记录。"
            SettingsUiField {
                host: root.host
                tokens: root.tokens
                text: frontendSettings.sessionId
                onEditingFinished: frontendSettings.sessionId = text
            }
        }
        SettingsUiCard {
            host: root.host
            tokens: root.tokens
            title: "工作区"
            detail: "显示在应用标题与启动画面中。"
            SettingsUiField {
                host: root.host
                tokens: root.tokens
                text: frontendSettings.workspaceName
                onEditingFinished: frontendSettings.workspaceName = text
            }
        }
        SettingsUiCard {
            host: root.host
            tokens: root.tokens
            title: "显示名称"
            detail: "用于标记你发送的消息。"
            SettingsUiField {
                host: root.host
                tokens: root.tokens
                text: frontendSettings.operatorName
                onEditingFinished: frontendSettings.operatorName = text
            }
        }
    }

    GridLayout {
        Layout.fillWidth: true
        columns: 1
        columnSpacing: root.host.sp(16)
        rowSpacing: root.host.sp(16)
        SettingsBlackToggle {
            host: root.host
            tokens: root.tokens
            title: "短句模式"
            detail: "让回复更紧凑，并缩短段落之间的等待。"
            checked: frontendSettings.shortModeEnabled
            onToggled: function(value) { frontendSettings.shortModeEnabled = value }
        }
        SettingsBlackToggle {
            host: root.host
            tokens: root.tokens
            title: "启动动画"
            detail: "开启或关闭 ArkLight 启动遮罩。"
            checked: frontendSettings.bootAnimationEnabled
            onToggled: function(value) { frontendSettings.bootAnimationEnabled = value }
        }
    }

    Rectangle {
        Layout.fillWidth: true
        height: 1
        color: root.tokens.inkAlpha(0.1)
    }

    RowLayout {
        Layout.fillWidth: true
        spacing: root.host.sp(16)
        SettingsSectionTitle {
            host: root.host
            tokens: root.tokens
            eyebrow: "CHARACTER INDEX"; title: "角色" }
        SettingsUiButton {
            host: root.host
            tokens: root.tokens
            text: "刷新"
            onClicked: characterCatalog.reload()
        }
        SettingsUiButton {
            host: root.host
            tokens: root.tokens
            text: "打开目录"
            accentColor: root.tokens.teal
            onClicked: characterCatalog.openCharactersFolder()
        }
    }

    GridLayout {
        Layout.fillWidth: true
        columns: root.host.width > root.host.sp(1250) ? 3 : (root.host.width > root.host.sp(940) ? 2 : 1)
        columnSpacing: root.host.sp(16)
        rowSpacing: root.host.sp(16)
        Repeater {
            model: characterCatalog.characters
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: root.host.sp(334)
                radius: root.host.sp(root.tokens.radiusFrame)
                color: "#FFFFFF"
                border.color: frontendSettings.characterName === modelData.name ? root.tokens.teal : root.tokens.inkAlpha(0.1)
                border.width: 2
                clip: true

                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: root.host.sp(14)
                    spacing: root.host.sp(12)
                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: root.host.sp(208)
                        radius: root.host.sp(root.tokens.radiusFrame)
                        color: root.tokens.inputPaper
                        border.color: root.tokens.inkAlpha(0.1)
                        border.width: 1
                        clip: true
                        Image {
                            anchors.fill: parent
                            anchors.margins: root.host.sp(10)
                            source: modelData.iconUrl
                            fillMode: Image.PreserveAspectFit
                        }
                        Rectangle {
                            anchors.left: parent.left
                            anchors.top: parent.top
                            anchors.leftMargin: root.host.sp(10)
                            anchors.topMargin: root.host.sp(10)
                            radius: root.host.sp(root.tokens.radiusFrame)
                            color: root.tokens.whiteAlpha(0.9)
                            implicitWidth: emotionText.implicitWidth + root.host.sp(16)
                            implicitHeight: emotionText.implicitHeight + root.host.sp(8)
                            Text {
                                id: emotionText
                                anchors.centerIn: parent
                                text: modelData.defaultEmotion || "default"
                                color: root.tokens.teal
                                font.family: root.tokens.monoFont
                                font.pixelSize: root.host.sp(10)
                            }
                        }
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: root.host.sp(12)
                        ColumnLayout {
                            Layout.fillWidth: true
                            Layout.minimumWidth: 0
                            spacing: root.host.sp(4)
                            Text {
                                Layout.fillWidth: true
                                text: modelData.name
                                color: root.tokens.ink
                                font.family: root.tokens.sansFont
                                font.pixelSize: root.host.sp(18)
                                font.weight: Font.Black
                                elide: Text.ElideRight
                            }
                            Text {
                                Layout.fillWidth: true
                                text: "表情数: " + (modelData.emotions ? modelData.emotions.length : 0)
                                color: root.tokens.inkAlpha(0.55)
                                font.family: root.tokens.sansFont
                                font.pixelSize: root.host.sp(12)
                            }
                        }
                        SettingsUiButton {
                            host: root.host
                            tokens: root.tokens
                            text: frontendSettings.characterName === modelData.name ? "使用中" : "使用"
                            primary: frontendSettings.characterName === modelData.name
                            accentColor: root.tokens.teal
                            onClicked: {
                                frontendSettings.characterName = modelData.name
                                chatSession.selectCharacter(modelData.name)
                            }
                        }
                    }
                }
            }
        }
    }
}
