import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ColumnLayout {
    id: sectionTitle
    property var host
    property var tokens
    property string eyebrow: ""
    property string title: ""
    Layout.fillWidth: true
    spacing: host.sp(8)
    Text {
        Layout.fillWidth: true
        text: sectionTitle.eyebrow
        color: tokens.teal
        font.family: tokens.monoFont
        font.pixelSize: host.sp(10)
        font.capitalization: Font.AllUppercase
        elide: Text.ElideRight
    }
    Text {
        Layout.fillWidth: true
        text: sectionTitle.title
        color: tokens.ink
        font.family: tokens.sansFont
        font.pixelSize: host.sp(30)
        font.weight: Font.Black
        wrapMode: Text.WordWrap
    }
}
