import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Item {
    id: toggleRow
    property var host
    property var tokens
    property string title: ""
    property string detail: ""
    property bool checked: false
    signal toggled(bool value)

    Layout.fillWidth: true
    implicitHeight: Math.max(host.sp(74), contentRow.implicitHeight + host.sp(26))

    RowLayout {
        id: contentRow
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: divider.top
        anchors.leftMargin: host.sp(4)
        anchors.rightMargin: host.sp(4)
        anchors.topMargin: host.sp(12)
        anchors.bottomMargin: host.sp(12)
        spacing: host.sp(18)

        ColumnLayout {
            Layout.fillWidth: true
            Layout.minimumWidth: 0
            spacing: host.sp(5)

            Text {
                Layout.fillWidth: true
                text: toggleRow.title
                color: tokens.ink
                font.family: tokens.sansFont
                font.pixelSize: host.sp(12)
                font.weight: Font.DemiBold
                elide: Text.ElideRight
            }

            Text {
                Layout.fillWidth: true
                text: toggleRow.detail
                color: tokens.inkAlpha(0.42)
                font.family: tokens.sansFont
                font.pixelSize: host.sp(10)
                wrapMode: Text.WordWrap
                lineHeight: 1.35
            }
        }

        Button {
            id: switchButton
            Layout.preferredWidth: host.sp(46)
            Layout.preferredHeight: host.sp(24)
            focusPolicy: Qt.StrongFocus
            hoverEnabled: true
            Accessible.name: toggleRow.title
            Accessible.role: Accessible.CheckBox
            Accessible.checked: toggleRow.checked
            onClicked: toggleRow.toggled(!toggleRow.checked)

            contentItem: Item {}

            background: Rectangle {
                radius: host.sp(tokens.radiusFrame)
                color: toggleRow.checked ? tokens.teal : tokens.inputPaper
                border.color: switchButton.activeFocus
                              ? tokens.orange
                              : (toggleRow.checked ? tokens.tealDark : tokens.inkAlpha(0.28))
                border.width: switchButton.activeFocus ? 2 : 1

                Rectangle {
                    width: host.sp(18)
                    height: host.sp(18)
                    x: toggleRow.checked ? parent.width - width - host.sp(3) : host.sp(3)
                    y: host.sp(3)
                    radius: host.sp(tokens.radiusFrame)
                    color: toggleRow.checked ? tokens.paperLight : tokens.ink
                    Behavior on x {
                        NumberAnimation {
                            duration: tokens.fastMotion
                            easing.type: Easing.OutCubic
                        }
                    }
                }

                Behavior on color { ColorAnimation { duration: tokens.fastMotion } }
            }
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
