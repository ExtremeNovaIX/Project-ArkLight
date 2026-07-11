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
    hoverEnabled: true
    leftPadding: host.sp(13)
    rightPadding: host.sp(13)
    topPadding: host.sp(8)
    bottomPadding: host.sp(8)
    font.family: tokens.sansFont
    font.pixelSize: host.sp(11)
    font.weight: Font.DemiBold

    contentItem: Text {
        text: button.text
        color: !button.enabled
               ? tokens.inkAlpha(0.30)
               : (button.primary ? tokens.paperLight : button.accentColor)
        font: button.font
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }

    background: Rectangle {
        radius: host.sp(tokens.radiusFrame)
        color: !button.enabled
               ? tokens.inkAlpha(0.03)
               : (button.primary
                  ? (button.hovered ? tokens.orange : tokens.ink)
                  : (button.hovered ? tokens.inkAlpha(0.035) : "transparent"))
        border.color: button.primary ? tokens.ink : tokens.inkAlpha(0.26)
        border.width: 1
        Behavior on color { ColorAnimation { duration: tokens.fastMotion } }
    }
}
