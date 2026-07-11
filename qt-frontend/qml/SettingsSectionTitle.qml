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

    RowLayout {
        Layout.fillWidth: true
        spacing: host.sp(12)

        Text {
            text: sectionTitle.eyebrow
            color: tokens.orange
            font.family: tokens.displayFont
            font.pixelSize: host.sp(11)
            font.weight: Font.DemiBold
            font.letterSpacing: host.sp(0.6)
        }

        Text {
            Layout.fillWidth: true
            text: sectionTitle.title
            color: tokens.ink
            font.family: tokens.sansFont
            font.pixelSize: host.sp(23)
            font.weight: Font.DemiBold
            elide: Text.ElideRight
        }
    }

    Rectangle {
        Layout.fillWidth: true
        Layout.preferredHeight: 1
        color: tokens.inkAlpha(0.14)
    }
}
