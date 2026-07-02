import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Button {
    id: button
    property var host
    property var tokens
    property bool primary: false
    property color accentColor: tokens.ink
    focusPolicy: Qt.NoFocus
    leftPadding: host.sp(14)
    rightPadding: host.sp(14)
    topPadding: host.sp(10)
    bottomPadding: host.sp(10)
    font.family: tokens.sansFont
    font.pixelSize: host.sp(11)
    font.weight: Font.Black
    scale: pressed ? 0.95 : (hovered && enabled ? 1.01 : 1)

    Behavior on scale { NumberAnimation { duration: 130; easing.type: Easing.OutCubic } }

    contentItem: Text {
        text: button.text
        color: !button.enabled
               ? tokens.whiteAlpha(0.55)
               : (button.primary ? "#FFFFFF" : (button.hovered ? "#FFFFFF" : button.accentColor))
        font: button.font
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }

    background: Rectangle {
        radius: host.sp(tokens.radiusFrame)
        color: !button.enabled
               ? tokens.inkAlpha(0.35)
               : (button.primary ? (button.hovered ? tokens.orange : tokens.ink) : (button.hovered ? button.accentColor : "transparent"))
        border.color: button.primary ? tokens.ink : button.accentColor
        border.width: 2
        Behavior on color { ColorAnimation { duration: tokens.fastMotion } }
    }
}
