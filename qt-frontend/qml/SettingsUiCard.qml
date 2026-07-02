import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: card
    property var host
    property var tokens
    default property alias content: body.data
    property string title: ""
    property string detail: ""
    Layout.fillWidth: true
    implicitHeight: Math.max(host.sp(112), body.implicitHeight + host.sp(40))
    radius: host.sp(tokens.radiusFrame)
    color: tokens.whiteAlpha(0.7)
    border.color: tokens.ink
    border.width: 2
    ColumnLayout {
        id: body
        anchors.fill: parent
        anchors.margins: host.sp(18)
        spacing: host.sp(12)
        Text {
            Layout.fillWidth: true
            text: card.title
            color: tokens.ink
            font.family: tokens.sansFont
            font.pixelSize: host.sp(11)
            font.weight: Font.Black
            font.capitalization: Font.AllUppercase
            elide: Text.ElideRight
        }
        Text {
            Layout.fillWidth: true
            visible: card.detail.length > 0
            text: card.detail
            color: tokens.inkAlpha(0.55)
            font.family: tokens.sansFont
            font.pixelSize: host.sp(12)
            wrapMode: Text.WordWrap
        }
    }
}
