import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

TextField {
    id: field
    property var host
    property var tokens
    Layout.fillWidth: true
    font.family: tokens.sansFont
    font.pixelSize: host.sp(14)
    color: tokens.ink
    selectedTextColor: "#FFFFFF"
    selectionColor: tokens.orange
    placeholderTextColor: tokens.inkAlpha(0.4)
    leftPadding: host.sp(12)
    rightPadding: host.sp(12)
    topPadding: host.sp(10)
    bottomPadding: host.sp(10)
    implicitHeight: host.sp(46)
    horizontalAlignment: TextInput.AlignLeft
    verticalAlignment: TextInput.AlignVCenter
    selectByMouse: true
    background: Rectangle {
        radius: host.sp(tokens.radiusFrame)
        color: tokens.inputPaper
        border.color: field.activeFocus ? tokens.orange : tokens.inkAlpha(0.2)
        border.width: 1
        Behavior on border.color { ColorAnimation { duration: tokens.fastMotion } }
    }
}
