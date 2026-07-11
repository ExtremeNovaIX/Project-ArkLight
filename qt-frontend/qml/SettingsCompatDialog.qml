import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Dialog {
    id: dialog
    property real scaleFactor: 1
    property string activeView: "frontend"
    property string activeHistoryKey: ""
    property string gameStateText: "STOPPED"
    property int gameStepCount: 0
    property string gameSessionText: ""
    property string gameRpSessionText: ""
    property string gameStartedAt: ""
    property string gameLastActivityAt: ""
    property string gameLastUpdatedAt: ""
    property string gameErrorText: ""
    property bool gameLoading: false

    modal: true
    dim: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    padding: 0
    background: Rectangle { color: "transparent" }
    Overlay.modal: Rectangle { color: tokens.inkAlpha(0.45) }

    enter: Transition {
        ParallelAnimation {
            NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 180; easing.type: Easing.OutCubic }
            NumberAnimation { property: "scale"; from: 0.975; to: 1; duration: 220; easing.type: Easing.OutCubic }
        }
    }

    exit: Transition {
        ParallelAnimation {
            NumberAnimation { property: "opacity"; from: 1; to: 0; duration: 130; easing.type: Easing.InCubic }
            NumberAnimation { property: "scale"; from: 1; to: 0.985; duration: 130; easing.type: Easing.InCubic }
        }
    }

    ArkLightTokens {
        id: tokens
    }

    function sp(value) {
        return Math.round(value * scaleFactor)
    }

    function normalizedBaseUrl() {
        let value = frontendSettings.backendBaseUrl.trim()
        while (value.endsWith("/")) {
            value = value.slice(0, -1)
        }
        return value.length > 0 ? value : "http://localhost:8080"
    }

    function fieldKey(fileName, key) {
        return fileName + ":" + key
    }

    function characterNameModel() {
        const names = characterCatalog.characterNames()
        return names.length > 0 ? ["未选择"].concat(names) : ["未选择"]
    }

    function resolvedGameName() {
        return frontendSettings.gameName.trim().length > 0 ? frontendSettings.gameName.trim() : "STS2MCP"
    }

    function resolvedGameSessionId() {
        const configured = frontendSettings.gameSessionId.trim()
        return configured.length > 0
            ? configured
            : (frontendSettings.sessionId.trim().length > 0 ? frontendSettings.sessionId.trim() : "default")
    }

    function resolvedGameRpSessionId() {
        const configured = frontendSettings.gameRpSessionId.trim()
        return configured.length > 0 ? configured : resolvedGameSessionId()
    }

    function requestJson(method, url, body, done) {
        const xhr = new XMLHttpRequest()
        xhr.onreadystatechange = function() {
            if (xhr.readyState !== 4) {
                return
            }
            let payload = null
            try {
                payload = xhr.responseText.length > 0 ? JSON.parse(xhr.responseText) : null
            } catch (error) {
                payload = null
            }
            done(xhr.status, xhr.statusText, payload)
        }
        xhr.open(method, url)
        xhr.setRequestHeader("Accept", "application/json")
        if (body !== null && body !== undefined) {
            xhr.setRequestHeader("Content-Type", "application/json")
            xhr.send(JSON.stringify(body))
        } else {
            xhr.send()
        }
    }

    function refreshGameStatus() {
        gameLoading = true
        gameErrorText = ""
        const url = normalizedBaseUrl()
            + "/api/gamer/loop/status?gameName=" + encodeURIComponent(resolvedGameName())
            + "&sessionId=" + encodeURIComponent(resolvedGameSessionId())
        requestJson("GET", url, null, function(status, statusText, payload) {
            gameLoading = false
            gameLastUpdatedAt = new Date().toLocaleString()
            if (status < 200 || status >= 300 || payload === null) {
                gameErrorText = statusText || "无法读取游戏循环状态。"
                return
            }
            const session = payload.session || {}
            gameStateText = session.state || (payload.running ? "RUNNING" : "STOPPED")
            gameStepCount = session.totalStepCount || 0
            gameSessionText = session.sessionId || resolvedGameSessionId()
            gameRpSessionText = session.rpSessionId || resolvedGameRpSessionId()
            gameStartedAt = session.startedAt || "-"
            gameLastActivityAt = session.lastActivityAt || "-"
        })
    }

    function sendGameCommand(action) {
        gameLoading = true
        gameErrorText = ""
        requestJson("POST", normalizedBaseUrl() + "/api/gamer/loop/" + action, {
            gameName: resolvedGameName(),
            sessionId: resolvedGameSessionId(),
            rpSessionId: resolvedGameRpSessionId(),
            characterName: frontendSettings.characterName,
            shortMode: frontendSettings.shortModeEnabled
        }, function(status, statusText, payload) {
            gameLoading = false
            if (status < 200 || status >= 300) {
                gameErrorText = payload && payload.error ? payload.error : (statusText || "游戏循环操作失败。")
                return
            }
            refreshGameStatus()
        })
    }

    onOpened: {
        activeView = "frontend"
        configCatalog.fetchConfigs()
    }

    onClosed: {
        configCatalog.saveChangedConfigs()
        frontendSettings.save()
        activeHistoryKey = ""
    }


    contentItem: Item {
        implicitWidth: dialog.sp(1180)
        implicitHeight: dialog.sp(720)

        Rectangle {
            anchors.fill: parent
            radius: dialog.sp(4)
            color: tokens.shell
            border.color: tokens.inkAlpha(0.36)
            border.width: 1
            clip: true

            SettingsBackdrop {
                anchors.fill: parent
                host: dialog
                tokens: tokens
            }

            ColumnLayout {
                anchors.fill: parent
                spacing: 0

                Item {
                    Layout.fillWidth: true
                    Layout.preferredHeight: dialog.sp(146)

                    RowLayout {
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        anchors.leftMargin: dialog.sp(40)
                        anchors.rightMargin: dialog.sp(34)
                        spacing: dialog.sp(20)

                        OrbitalRelayMark {
                            Layout.preferredWidth: dialog.sp(76)
                            Layout.preferredHeight: dialog.sp(76)
                            host: dialog
                            tokens: tokens
                        }

                        ColumnLayout {
                            spacing: dialog.sp(3)
                            Text {
                                text: "ARKLIGHT PIONEER"
                                color: tokens.ink
                                font.family: tokens.displayFont
                                font.pixelSize: dialog.sp(21)
                                font.weight: Font.DemiBold
                                font.letterSpacing: dialog.sp(0.5)
                            }
                            Text {
                                text: "C A S S E T T E   R E L A Y"
                                color: tokens.inkAlpha(0.72)
                                font.family: tokens.monoFont
                                font.pixelSize: dialog.sp(8)
                                font.letterSpacing: dialog.sp(1.5)
                            }
                        }

                        Item { Layout.fillWidth: true }

                        Text {
                            text: "RLY-Σ / " + (dialog.activeView === "frontend" ? "01"
                                                  : dialog.activeView === "backend" ? "02"
                                                  : dialog.activeView === "game" ? "03" : "04")
                            color: tokens.inkAlpha(0.25)
                            font.family: tokens.monoFont
                            font.pixelSize: dialog.sp(8)
                        }

                        SettingsUiButton {
                            host: dialog
                            tokens: tokens
                            text: "关闭"
                            onClicked: dialog.close()
                        }
                    }

                    Rectangle {
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.bottom: parent.bottom
                        anchors.leftMargin: dialog.sp(24)
                        anchors.rightMargin: dialog.sp(24)
                        height: 1
                        color: tokens.inkAlpha(0.16)
                    }
                }

                RowLayout {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    spacing: 0

                    SettingsNav {
                        host: dialog
                        tokens: tokens
                    }

                    Rectangle {
                        Layout.fillHeight: true
                        Layout.preferredWidth: 1
                        color: tokens.inkAlpha(0.14)
                    }

                    ScrollView {
                        id: settingsScroll
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        clip: true
                        contentWidth: availableWidth

                        ScrollBar.vertical.policy: ScrollBar.AsNeeded

                        ColumnLayout {
                            width: Math.max(0, settingsScroll.availableWidth - dialog.sp(66))
                            x: dialog.sp(34)
                            y: dialog.sp(26)
                            spacing: dialog.sp(26)

                            SettingsFrontendPanel {
                                host: dialog
                                tokens: tokens
                            }

                            SettingsConfigPanel {
                                host: dialog
                                tokens: tokens
                            }

                            SettingsGamePanel {
                                host: dialog
                                tokens: tokens
                            }

                            SettingsVoicePanel {
                                host: dialog
                                tokens: tokens
                            }

                            Item {
                                Layout.fillWidth: true
                                Layout.preferredHeight: dialog.sp(28)
                            }
                        }
                    }
                }
            }
        }
    }
}
