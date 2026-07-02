import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: blackToggle
    property var host
    property var tokens
    property string title: ""
    property string detail: ""
    property bool checked: false
    signal toggled(bool value)
    Layout.fillWidth: true
    implicitHeight: Math.max(host.sp(78), toggleRow.implicitHeight + host.sp(28))
    radius: host.sp(tokens.radiusFrame)
    color: tokens.ink
    border.color: tokens.ink
    border.width: 2

    RowLayout {
        id: toggleRow
        anchors.fill: parent
        anchors.margins: host.sp(16)
        spacing: host.sp(16)

        ColumnLayout {
            Layout.fillWidth: true
            Layout.minimumWidth: 0
            spacing: host.sp(8)
            Text {
                Layout.fillWidth: true
                text: blackToggle.title
                color: "#FFFFFF"
                font.family: tokens.sansFont
                font.pixelSize: host.sp(11)
                font.weight: Font.Black
                font.capitalization: Font.AllUppercase
                elide: Text.ElideRight
            }
            Text {
                Layout.fillWidth: true
                text: blackToggle.detail
                color: tokens.whiteAlpha(0.6)
                font.family: tokens.sansFont
                font.pixelSize: host.sp(12)
                wrapMode: Text.WordWrap
            }
        }

        CheckBox {
            checked: blackToggle.checked
            onToggled: blackToggle.toggled(checked)
        }
    }
}
