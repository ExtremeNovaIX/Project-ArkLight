import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: composer
    property real scaleFactor: 1
    property string value: ""
    property bool sendDisabled: false
    signal inputEdited(string value)
    signal submitRequested(string value)

    color: "transparent"
    implicitHeight: sp(84)

    ArkLightTokens {
        id: tokens
    }

    function sp(value) {
        return Math.round(value * scaleFactor)
    }

    function submit() {
        const content = inputArea.text.trim()
        if (composer.sendDisabled || content.length === 0) {
            return
        }
        composer.submitRequested(content)
    }

    Rectangle {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        height: 1
        color: tokens.inkAlpha(0.12)
    }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: composer.sp(32)
        anchors.rightMargin: composer.sp(28)
        anchors.topMargin: composer.sp(14)
        anchors.bottomMargin: composer.sp(14)
        spacing: composer.sp(12)

        TextArea {
            id: inputArea
            Layout.fillWidth: true
            Layout.fillHeight: true
            text: composer.value
            placeholderText: "输入内容..."
            wrapMode: TextEdit.NoWrap
            color: tokens.ink
            placeholderTextColor: tokens.inkAlpha(0.42)
            selectionColor: tokens.orange
            selectedTextColor: tokens.paperLight
            font.family: tokens.sansFont
            font.pixelSize: composer.sp(14)
            leftPadding: composer.sp(14)
            rightPadding: composer.sp(14)
            topPadding: composer.sp(13)
            bottomPadding: composer.sp(11)

            background: Rectangle {
                radius: composer.sp(tokens.radiusControl)
                color: tokens.inputPaper
                border.color: inputArea.activeFocus ? tokens.orangeAlpha(0.52) : tokens.inkAlpha(0.22)
                border.width: 1

                Behavior on border.color {
                    ColorAnimation {
                        duration: tokens.fastMotion
                    }
                }
            }

            onTextChanged: {
                if (text !== composer.value) {
                    composer.inputEdited(text)
                }
            }

            Keys.onPressed: function(event) {
                if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) {
                    if (event.modifiers & Qt.ShiftModifier) {
                        return
                    }
                    composer.submit()
                    event.accepted = true
                }
            }
        }

        Button {
            id: sendButton
            Layout.preferredWidth: composer.sp(92)
            Layout.fillHeight: true
            enabled: !composer.sendDisabled && inputArea.text.trim().length > 0
            focusPolicy: Qt.NoFocus
            scale: pressed ? 0.96 : 1

            Behavior on scale {
                NumberAnimation {
                    duration: tokens.fastMotion
                    easing.type: Easing.OutCubic
                }
            }

            contentItem: Text {
                text: "发送"
                color: sendButton.enabled ? tokens.ink : tokens.inkAlpha(0.42)
                font.family: tokens.sansFont
                font.pixelSize: composer.sp(13)
                font.weight: Font.DemiBold
                horizontalAlignment: Text.AlignHCenter
                verticalAlignment: Text.AlignVCenter
            }

            background: Rectangle {
                radius: composer.sp(tokens.radiusControl)
                color: sendButton.enabled ? tokens.orange : tokens.orangeAlpha(0.28)

                Rectangle {
                    anchors.fill: parent
                    anchors.margins: composer.sp(4)
                    radius: composer.sp(Math.max(3, tokens.radiusControl - 2))
                    color: sendButton.hovered && sendButton.enabled ? tokens.paperLight : tokens.inputPaper
                    border.color: tokens.blackAlpha(sendButton.enabled ? 0.28 : 0.12)
                    border.width: 1

                    Behavior on color {
                        ColorAnimation {
                            duration: tokens.fastMotion
                        }
                    }
                }
            }

            onClicked: composer.submit()
        }
    }
}
