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

    color: tokens.whiteAlpha(0.32)
    border.color: tokens.inkAlpha(0.1)
    border.width: 1
    implicitHeight: sp(122)

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

    Item {
        anchors.centerIn: parent
        width: Math.min(parent.width - composer.sp(64), composer.sp(1080))
        height: Math.min(parent.height - composer.sp(30), composer.sp(92))

        RowLayout {
            anchors.fill: parent
            spacing: composer.sp(16)

            Item {
                Layout.fillWidth: true
                Layout.fillHeight: true

                TextArea {
                    id: inputArea
                    anchors.fill: parent
                    text: composer.value
                    placeholderText: "输入内容..."
                    wrapMode: TextEdit.Wrap
                    color: tokens.ink
                    placeholderTextColor: tokens.inkAlpha(0.4)
                    selectionColor: tokens.orange
                    selectedTextColor: "#FFFFFF"
                    font.family: tokens.sansFont
                    font.pixelSize: composer.sp(15)
                    leftPadding: composer.sp(20)
                    rightPadding: composer.sp(48)
                    topPadding: composer.sp(14)
                    bottomPadding: composer.sp(14)

                    background: Rectangle {
                        radius: composer.sp(22)
                        color: "#FFFFFF"
                        border.color: inputArea.activeFocus ? tokens.orangeAlpha(0.32) : tokens.inkAlpha(0.15)
                        border.width: 1

                        Behavior on border.color {
                            ColorAnimation { duration: 150 }
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

                IconCpu {
                    anchors.right: parent.right
                    anchors.top: parent.top
                    anchors.rightMargin: composer.sp(14)
                    anchors.topMargin: composer.sp(14)
                    width: composer.sp(20)
                    height: composer.sp(20)
                    strokeColor: inputArea.activeFocus ? tokens.orangeAlpha(0.45) : tokens.inkAlpha(0.2)
                }
            }

            Button {
                id: sendButton
                Layout.preferredWidth: composer.sp(156)
                Layout.fillHeight: true
                Layout.alignment: Qt.AlignVCenter
                enabled: !composer.sendDisabled && inputArea.text.trim().length > 0
                focusPolicy: Qt.NoFocus
                scale: pressed ? 0.96 : (hovered && enabled ? 1.015 : 1)

                Behavior on scale {
                    NumberAnimation { duration: 130; easing.type: Easing.OutCubic }
                }

                contentItem: Item {
                    implicitWidth: composer.sp(72)
                    implicitHeight: composer.sp(30)

                    Row {
                        anchors.centerIn: parent
                        spacing: composer.sp(10)

                        IconSend {
                            anchors.verticalCenter: parent.verticalCenter
                            width: composer.sp(28)
                            height: composer.sp(28)
                            strokeColor: sendButton.enabled ? "#FFFFFF" : tokens.whiteAlpha(0.55)
                        }

                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: "发送"
                            color: sendButton.enabled ? "#FFFFFF" : tokens.whiteAlpha(0.55)
                            font.family: tokens.sansFont
                            font.pixelSize: composer.sp(12)
                            font.weight: Font.Black
                        }
                    }
                }

                background: Rectangle {
                    radius: composer.sp(22)
                    color: sendButton.enabled ? (sendButton.hovered ? tokens.orange : tokens.ink) : tokens.inkAlpha(0.35)

                    Behavior on color {
                        ColorAnimation { duration: 150 }
                    }
                }

                onClicked: composer.submit()
            }
        }
    }
}
