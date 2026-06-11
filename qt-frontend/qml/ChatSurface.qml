import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: surface
    property real scaleFactor: 1
    property string workspaceName: "ArkLight Pioneer"
    property string operatorName: "本地"
    property string backendBaseUrl: ""
    property var messagesModel: null
    property string userInput: ""
    property bool assistantTyping: false
    property bool sendDisabled: false
    signal openSettings()
    signal inputEdited(string value)
    signal submitMessage(string value)

    color: tokens.paper
    clip: true

    ArkLightTokens {
        id: tokens
    }

    function sp(value) {
        return Math.round(value * scaleFactor)
    }

    function displayOperatorName() {
        return operatorName === "Local" ? "本地" : operatorName
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: surface.sp(80)
            color: tokens.whiteAlpha(0.52)
            border.color: tokens.ink
            border.width: 2
            clip: true

            DiagonalGrid {
                anchors.fill: parent
                step: surface.sp(10)
                lineColor: tokens.ink
                lineAlpha: 0.02
                drawDiagonals: false
            }

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: surface.sp(40)
                anchors.rightMargin: surface.sp(40)
                spacing: surface.sp(24)

                Item {
                    Layout.fillWidth: true
                }

                Button {
                    id: settingsButton
                    Layout.preferredHeight: surface.sp(40)
                    Layout.preferredWidth: surface.sp(108)
                    Layout.alignment: Qt.AlignVCenter
                    leftPadding: surface.sp(16)
                    rightPadding: surface.sp(16)
                    focusPolicy: Qt.NoFocus
                    scale: pressed ? 0.96 : (hovered ? 1.02 : 1)

                    Behavior on scale {
                        NumberAnimation { duration: 130; easing.type: Easing.OutCubic }
                    }

                    contentItem: Item {
                        implicitWidth: surface.sp(76)
                        implicitHeight: surface.sp(24)

                        Row {
                            anchors.centerIn: parent
                            spacing: surface.sp(10)

                            IconGear {
                                anchors.verticalCenter: parent.verticalCenter
                                width: surface.sp(20)
                                height: surface.sp(20)
                                strokeColor: settingsButton.hovered ? "#FFFFFF" : tokens.ink
                            }

                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: "设置"
                                color: settingsButton.hovered ? "#FFFFFF" : tokens.ink
                                font.family: tokens.sansFont
                                font.pixelSize: surface.sp(10)
                                font.weight: Font.Black
                            }
                        }
                    }

                    background: Rectangle {
                        color: settingsButton.hovered ? tokens.ink : "transparent"
                        border.color: tokens.ink
                        border.width: 2

                        Behavior on color {
                            ColorAnimation { duration: 150 }
                        }
                    }

                    onClicked: surface.openSettings()
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.fillHeight: true
            color: tokens.paper
            clip: true

            Rectangle {
                anchors.fill: parent
                color: tokens.paper
                opacity: 0.95
            }

            DiagonalGrid {
                anchors.fill: parent
                step: surface.sp(56)
                lineColor: tokens.chatGrid
                lineAlpha: 0.11
                opacity: 0.42
            }

            ListView {
                id: chatList
                anchors.fill: parent
                anchors.margins: surface.sp(32)
                spacing: surface.sp(20)
                clip: true
                model: surface.messagesModel
                add: Transition {
                    NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 220; easing.type: Easing.OutCubic }
                    NumberAnimation { property: "scale"; from: 0.985; to: 1; duration: 220; easing.type: Easing.OutCubic }
                }
                displaced: Transition {
                    NumberAnimation { properties: "x,y"; duration: 180; easing.type: Easing.OutCubic }
                }

                function scrollToEnd() {
                    Qt.callLater(function() {
                        chatList.positionViewAtEnd()
                    })
                }

                ScrollBar.vertical: ScrollBar {
                    policy: ScrollBar.AsNeeded
                    contentItem: Rectangle {
                        implicitWidth: surface.sp(5)
                        radius: surface.sp(3)
                        color: tokens.inkAlpha(0.13)
                    }
                    background: Rectangle { color: "transparent" }
                }

                delegate: Item {
                    width: chatList.width
                    height: messageColumn.implicitHeight
                    opacity: 1
                    scale: 1

                    Column {
                        id: messageColumn
                        width: Math.min(chatList.width * 0.86, surface.sp(512))
                        anchors.right: messageRole === "user" ? parent.right : undefined
                        anchors.left: messageRole === "user" ? undefined : parent.left
                        spacing: surface.sp(6)

                        Row {
                            spacing: surface.sp(8)
                            layoutDirection: messageRole === "user" ? Qt.RightToLeft : Qt.LeftToRight
                            anchors.right: messageRole === "user" ? parent.right : undefined

                            Rectangle {
                                width: surface.sp(8)
                                height: surface.sp(8)
                                radius: surface.sp(4)
                                color: messageRole === "user" ? tokens.orange : tokens.teal
                                anchors.verticalCenter: parent.verticalCenter
                            }

                            Text {
                                text: messageRole === "user"
                                      ? "用户 / " + surface.displayOperatorName()
                                      : "ArkLight / 远端"
                                color: tokens.inkAlpha(0.45)
                                font.family: tokens.monoFont
                                font.pixelSize: surface.sp(10)
                                anchors.verticalCenter: parent.verticalCenter
                            }

                            Rectangle {
                                width: surface.sp(32)
                                height: 1
                                color: tokens.inkAlpha(0.1)
                                anchors.verticalCenter: parent.verticalCenter
                            }
                        }

                        Rectangle {
                            id: bubble
                            width: Math.min(messageText.implicitWidth + surface.sp(40), parent.width)
                            implicitHeight: messageText.implicitHeight + surface.sp(28)
                            radius: surface.sp(20)
                            color: messageRole === "user" ? tokens.ink : "#FFFFFF"
                            border.color: messageRole === "user" ? tokens.ink : (bubbleHover.hovered ? tokens.tealAlpha(0.28) : tokens.inkAlpha(0.08))
                            border.width: messageRole === "user" ? 0 : 1
                            anchors.right: messageRole === "user" ? parent.right : undefined
                            scale: bubbleHover.hovered ? 1.012 : 1

                            HoverHandler {
                                id: bubbleHover
                            }

                            Behavior on scale {
                                NumberAnimation { duration: 150; easing.type: Easing.OutCubic }
                            }

                            Behavior on border.color {
                                ColorAnimation { duration: 160 }
                            }

                            Text {
                                id: messageText
                                width: Math.min(implicitWidth, parent.parent.width - surface.sp(40))
                                anchors.fill: parent
                                anchors.margins: surface.sp(14)
                                text: messageContent
                                wrapMode: Text.Wrap
                                color: messageRole === "user" ? "#FFFFFF" : tokens.ink
                                font.family: tokens.sansFont
                                font.pixelSize: surface.sp(15)
                                lineHeight: 1.35
                            }
                        }
                    }
                }

                footer: Item {
                    width: chatList.width
                    height: surface.assistantTyping ? surface.sp(58) : 0
                    visible: surface.assistantTyping

                    Row {
                        anchors.left: parent.left
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: surface.sp(8)
                        Rectangle {
                            width: surface.sp(10)
                            height: surface.sp(10)
                            radius: surface.sp(5)
                            color: tokens.teal
                            SequentialAnimation on opacity {
                                loops: Animation.Infinite
                                NumberAnimation { from: 0.35; to: 0.95; duration: 520; easing.type: Easing.InOutSine }
                                NumberAnimation { from: 0.95; to: 0.35; duration: 520; easing.type: Easing.InOutSine }
                            }
                        }
                        Rectangle {
                            width: surface.sp(10)
                            height: surface.sp(10)
                            radius: surface.sp(5)
                            color: tokens.teal
                            SequentialAnimation on opacity {
                                loops: Animation.Infinite
                                PauseAnimation { duration: 130 }
                                NumberAnimation { from: 0.35; to: 0.95; duration: 520; easing.type: Easing.InOutSine }
                                NumberAnimation { from: 0.95; to: 0.35; duration: 520; easing.type: Easing.InOutSine }
                            }
                        }
                        Rectangle {
                            width: surface.sp(10)
                            height: surface.sp(10)
                            radius: surface.sp(5)
                            color: tokens.teal
                            SequentialAnimation on opacity {
                                loops: Animation.Infinite
                                PauseAnimation { duration: 260 }
                                NumberAnimation { from: 0.35; to: 0.95; duration: 520; easing.type: Easing.InOutSine }
                                NumberAnimation { from: 0.95; to: 0.35; duration: 520; easing.type: Easing.InOutSine }
                            }
                        }
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: "对方正在输入..."
                            color: tokens.inkAlpha(0.6)
                            font.family: tokens.sansFont
                            font.pixelSize: surface.sp(14)
                        }
                    }
                }

                onCountChanged: scrollToEnd()
                onContentHeightChanged: scrollToEnd()
                onHeightChanged: scrollToEnd()
            }
        }

        ChatComposer {
            Layout.fillWidth: true
            scaleFactor: surface.scaleFactor
            value: surface.userInput
            sendDisabled: surface.sendDisabled
            onInputEdited: function(value) {
                surface.inputEdited(value)
            }
            onSubmitRequested: function(value) {
                surface.submitMessage(value)
            }
        }

        StatusStrip {
            Layout.fillWidth: true
            scaleFactor: surface.scaleFactor
        }
    }
}
