import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Item {
    id: card
    property var host
    property var tokens
    default property alias content: valueColumn.data
    property string title: ""
    property string detail: ""

    Layout.fillWidth: true
    implicitHeight: Math.max(host.sp(88), Math.max(labelColumn.implicitHeight, valueColumn.implicitHeight) + host.sp(30))

    RowLayout {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: divider.top
        anchors.leftMargin: host.sp(4)
        anchors.rightMargin: host.sp(4)
        anchors.topMargin: host.sp(14)
        anchors.bottomMargin: host.sp(14)
        spacing: host.sp(22)

        ColumnLayout {
            id: labelColumn
            Layout.preferredWidth: Math.min(host.sp(170), card.width * 0.38)
            Layout.alignment: Qt.AlignTop
            spacing: host.sp(6)

            Text {
                Layout.fillWidth: true
                text: card.title
                color: tokens.ink
                font.family: tokens.sansFont
                font.pixelSize: host.sp(12)
                font.weight: Font.DemiBold
                wrapMode: Text.WordWrap
            }

            Text {
                Layout.fillWidth: true
                visible: card.detail.length > 0
                text: card.detail
                color: tokens.inkAlpha(0.42)
                font.family: tokens.sansFont
                font.pixelSize: host.sp(10)
                wrapMode: Text.WordWrap
                lineHeight: 1.35
            }
        }

        ColumnLayout {
            id: valueColumn
            Layout.fillWidth: true
            Layout.minimumWidth: 0
            Layout.alignment: Qt.AlignTop
            spacing: host.sp(10)
        }
    }

    Rectangle {
        id: divider
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        height: 1
        color: tokens.inkAlpha(0.10)
    }
}
