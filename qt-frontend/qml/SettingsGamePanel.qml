import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ColumnLayout {
    id: root
    property var host
    property var tokens
    visible: root.host.activeView === "game"
    Layout.fillWidth: true
    spacing: root.host.sp(20)

    SettingsSectionTitle {
        host: root.host
        tokens: root.tokens
        eyebrow: "03 / GAME LINK"
        title: "\u6e38\u620f\u8054\u52a8"
    }

    GridLayout {
        Layout.fillWidth: true
        columns: root.host.width > root.host.sp(1180) ? 3 : 1
        columnSpacing: root.host.sp(20)
        rowSpacing: root.host.sp(20)

        SettingsUiCard {
            host: root.host
            tokens: root.tokens
            title: "\u6e38\u620f\u540d"
            SettingsUiField {
                host: root.host
                tokens: root.tokens
                text: frontendSettings.gameName
                onEditingFinished: frontendSettings.gameName = text
            }
        }

        SettingsUiCard {
            host: root.host
            tokens: root.tokens
            title: "\u6e38\u620f\u4f1a\u8bdd"
            detail: "\u7559\u7a7a\u65f6\u4f7f\u7528\u804a\u5929 Session\u3002"
            SettingsUiField {
                host: root.host
                tokens: root.tokens
                text: frontendSettings.gameSessionId
                onEditingFinished: frontendSettings.gameSessionId = text
            }
        }

        SettingsUiCard {
            host: root.host
            tokens: root.tokens
            title: "\u89d2\u8272\u4f1a\u8bdd"
            detail: "\u7559\u7a7a\u65f6\u4f7f\u7528\u804a\u5929 Session\u3002"
            SettingsUiField {
                host: root.host
                tokens: root.tokens
                text: frontendSettings.gameRpSessionId
                onEditingFinished: frontendSettings.gameRpSessionId = text
            }
        }
    }

    GridLayout {
        Layout.fillWidth: true
        columns: 2
        columnSpacing: root.host.sp(16)
        rowSpacing: root.host.sp(16)

        SettingsUiCard {
            host: root.host
            tokens: root.tokens
            title: "\u72b6\u6001"
            Text {
                text: root.host.gameStateText
                color: root.tokens.ink
                font.family: root.tokens.sansFont
                font.pixelSize: root.host.sp(24)
                font.weight: Font.Black
            }
        }

        SettingsUiCard {
            host: root.host
            tokens: root.tokens
            title: "\u6e38\u620f"
            Text {
                text: root.host.resolvedGameName()
                color: root.tokens.ink
                font.family: root.tokens.monoFont
                font.pixelSize: root.host.sp(13)
                wrapMode: Text.WrapAnywhere
            }
        }

        SettingsUiCard {
            host: root.host
            tokens: root.tokens
            title: "\u6b65\u6570"
            Text {
                text: String(root.host.gameStepCount)
                color: root.tokens.ink
                font.family: root.tokens.sansFont
                font.pixelSize: root.host.sp(24)
                font.weight: Font.Black
            }
        }

        SettingsUiCard {
            host: root.host
            tokens: root.tokens
            title: "\u5237\u65b0"
            Text {
                text: root.host.gameLastUpdatedAt || "\u672a\u5237\u65b0"
                color: root.tokens.ink
                font.family: root.tokens.sansFont
                font.pixelSize: root.host.sp(13)
                wrapMode: Text.WrapAnywhere
            }
        }
    }

    SettingsUiCard {
        host: root.host
        tokens: root.tokens
        title: "\u8fd0\u884c\u4fe1\u606f"
        Text {
            Layout.fillWidth: true
            text: "gameSessionId=" + (root.host.gameSessionText || root.host.resolvedGameSessionId())
                  + "\nrpSessionId=" + (root.host.gameRpSessionText || root.host.resolvedGameRpSessionId())
                  + "\nstartedAt=" + (root.host.gameStartedAt || "-")
                  + "\nlastActivityAt=" + (root.host.gameLastActivityAt || "-")
            color: root.tokens.ink
            font.family: root.tokens.monoFont
            font.pixelSize: root.host.sp(12)
            wrapMode: Text.WrapAnywhere
        }
    }

    Rectangle {
        visible: root.host.gameErrorText.length > 0
        Layout.fillWidth: true
        implicitHeight: gameErrorTextItem.implicitHeight + root.host.sp(24)
        color: root.tokens.orangeDarkAlpha(0.08)
        border.color: root.tokens.orangeAlpha(0.5)
        border.width: 1
        Text {
            id: gameErrorTextItem
            anchors.fill: parent
            anchors.margins: root.host.sp(12)
            text: root.host.gameErrorText
            color: root.tokens.orangeDark
            font.family: root.tokens.sansFont
            font.pixelSize: root.host.sp(13)
            wrapMode: Text.WordWrap
        }
    }

    Flow {
        Layout.fillWidth: true
        spacing: root.host.sp(12)
        SettingsUiButton {
            host: root.host
            tokens: root.tokens
            text: "\u542f\u52a8"
            primary: true
            enabled: !root.host.gameLoading
            onClicked: root.host.sendGameCommand("start")
        }
        SettingsUiButton {
            host: root.host
            tokens: root.tokens
            text: "\u6682\u505c"
            enabled: !root.host.gameLoading
            onClicked: root.host.sendGameCommand("pause")
        }
        SettingsUiButton {
            host: root.host
            tokens: root.tokens
            text: "\u6062\u590d"
            enabled: !root.host.gameLoading
            onClicked: root.host.sendGameCommand("resume")
        }
        SettingsUiButton {
            host: root.host
            tokens: root.tokens
            text: "\u505c\u6b62"
            enabled: !root.host.gameLoading
            onClicked: root.host.sendGameCommand("stop")
        }
        SettingsUiButton {
            host: root.host
            tokens: root.tokens
            text: "\u5237\u65b0"
            accentColor: root.tokens.teal
            enabled: !root.host.gameLoading
            onClicked: root.host.refreshGameStatus()
        }
    }
}
