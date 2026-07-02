import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Button {
    id: navButton
    property var host
    property var tokens
    property string viewKey: ""
    property string kicker: ""
    property string label: ""
    Layout.fillWidth: true
    Layout.preferredHeight: host.sp(78)
    focusPolicy: Qt.NoFocus
    scale: pressed ? 0.985 : (hovered ? 1.008 : 1)

    Behavior on scale { NumberAnimation { duration: 140; easing.type: Easing.OutCubic } }

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

    contentItem: Column {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.verticalCenter: parent.verticalCenter
        anchors.leftMargin: host.sp(14)
        anchors.rightMargin: host.sp(14)
        spacing: host.sp(8)
        Text {
            width: parent.width
            text: navButton.kicker
            color: host.activeView === navButton.viewKey ? tokens.whiteAlpha(0.6) : tokens.inkAlpha(0.55)
            font.family: tokens.monoFont
            font.pixelSize: host.sp(10)
            font.capitalization: Font.AllUppercase
            elide: Text.ElideRight
        }
        Text {
            width: parent.width
            text: navButton.label
            color: host.activeView === navButton.viewKey ? "#FFFFFF" : tokens.ink
            font.family: tokens.sansFont
            font.pixelSize: host.sp(13)
            font.weight: Font.Black
            elide: Text.ElideRight
        }
    }

    background: Rectangle {
        radius: host.sp(tokens.radiusFrame)
        color: host.activeView === navButton.viewKey ? tokens.ink : tokens.whiteAlpha(0.5)
        border.color: host.activeView === navButton.viewKey ? tokens.ink : (navButton.hovered ? tokens.ink : tokens.inkAlpha(0.15))
        border.width: 2
        Behavior on color { ColorAnimation { duration: tokens.fastMotion } }
        Behavior on border.color { ColorAnimation { duration: tokens.fastMotion } }
    }
}
