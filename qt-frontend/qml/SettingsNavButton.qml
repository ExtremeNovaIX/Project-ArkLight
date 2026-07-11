import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Button {
    id: navButton
    property var host
    property var tokens
    property string viewKey: ""
    property string number: ""
    property string kicker: ""
    property string label: ""
    property string glyphKind: "general"

    Layout.fillWidth: true
    Layout.preferredHeight: host.sp(104)
    focusPolicy: Qt.StrongFocus
    hoverEnabled: true

    onClicked: {
        host.activeView = viewKey
        host.activeHistoryKey = ""
        if (viewKey === "backend") {
            if (configCatalog.configPages.length === 0) {
                configCatalog.fetchConfigs()
            }
        } else if (viewKey === "game") {
            host.refreshGameStatus()
        }
    }

    contentItem: RowLayout {
        spacing: host.sp(14)

        SettingsCategoryGlyph {
            Layout.preferredWidth: host.sp(52)
            Layout.preferredHeight: host.sp(52)
            host: navButton.host
            tokens: navButton.tokens
            kind: navButton.glyphKind
            active: host.activeView === navButton.viewKey
            hovered: navButton.hovered
        }

        ColumnLayout {
            Layout.fillWidth: true
            Layout.minimumWidth: 0
            spacing: host.sp(3)

            RowLayout {
                Layout.fillWidth: true
                spacing: host.sp(8)
                Text {
                    text: navButton.number
                    color: host.activeView === navButton.viewKey ? tokens.orange : tokens.ink
                    font.family: tokens.displayFont
                    font.pixelSize: host.sp(14)
                    font.weight: Font.DemiBold
                }
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 1
                    color: host.activeView === navButton.viewKey
                           ? tokens.orangeAlpha(0.40)
                           : tokens.inkAlpha(0.08)
                }
            }

            Text {
                Layout.fillWidth: true
                text: navButton.label
                color: tokens.ink
                font.family: tokens.sansFont
                font.pixelSize: host.sp(14)
                font.weight: host.activeView === navButton.viewKey ? Font.DemiBold : Font.Normal
                elide: Text.ElideRight
            }

            Text {
                Layout.fillWidth: true
                text: navButton.kicker
                color: tokens.inkAlpha(host.activeView === navButton.viewKey ? 0.48 : 0.34)
                font.family: tokens.monoFont
                font.pixelSize: host.sp(8)
                font.letterSpacing: host.sp(0.8)
                elide: Text.ElideRight
            }
        }
    }

    background: Rectangle {
        radius: host.sp(tokens.radiusFrame)
        color: host.activeView === navButton.viewKey
               ? tokens.inkAlpha(0.028)
               : ((navButton.hovered || navButton.activeFocus) ? tokens.inkAlpha(0.018) : "transparent")

        Rectangle {
            visible: host.activeView === navButton.viewKey
            anchors.left: parent.left
            anchors.verticalCenter: parent.verticalCenter
            width: host.sp(2)
            height: host.sp(24)
            color: tokens.orange
        }

        Behavior on color { ColorAnimation { duration: tokens.fastMotion } }
    }
}
