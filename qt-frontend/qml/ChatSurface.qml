import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: surface
    property real scaleFactor: 1
    property string workspaceName: "ArkLight Pioneer"
    property string operatorName: "Local"
    property string backendBaseUrl: ""
    property var messagesModel: null
    property string userInput: ""
    property bool assistantTyping: false
    property bool sendDisabled: false
    signal openSettings()
    signal inputEdited(string value)
    signal submitMessage(string value)

    color: tokens.paper
    radius: surface.sp(tokens.radiusFrame)
    clip: true

    ArkLightTokens {
        id: tokens
    }

    function sp(value) {
        return Math.round(value * scaleFactor)
    }

    function displayOperatorName() {
        return operatorName.length > 0 ? operatorName : "Local"
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: surface.sp(80)
            color: tokens.whiteAlpha(0.52)
            border.color: tokens.inkAlpha(0.34)
            border.width: 1
            clip: true

            SurfaceTexture {
                anchors.fill: parent
                scaleFactor: surface.scaleFactor
                lineColor: tokens.chatGrid
                lineAlpha: 0.026
                geometryAlpha: 0.008
                drawWatermark: false
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
                    Layout.preferredWidth: surface.sp(126)
                    Layout.alignment: Qt.AlignVCenter
                    focusPolicy: Qt.NoFocus
                    scale: pressed ? 0.95 : (hovered ? 1.015 : 1)

                    Behavior on scale { NumberAnimation { duration: 130; easing.type: Easing.OutCubic } }

                    contentItem: Row {
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
                            font.capitalization: Font.AllUppercase
                        }
                    }

                    background: Rectangle {
                        radius: surface.sp(tokens.radiusFrame)
                        color: settingsButton.hovered ? tokens.ink : "transparent"
                        border.color: tokens.ink
                        border.width: 2
                        Behavior on color { ColorAnimation { duration: tokens.fastMotion } }
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

            SurfaceTexture {
                anchors.fill: parent
                lineColor: tokens.chatGrid
                lineAlpha: 0.052
                geometryAlpha: 0.02
                scaleFactor: surface.scaleFactor
            }

            ListView {
                id: chatList
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                anchors.leftMargin: surface.sp(32)
                anchors.rightMargin: 0
                anchors.topMargin: surface.sp(32)
                anchors.bottomMargin: surface.sp(32)
                spacing: surface.sp(20)
                clip: true
                model: surface.messagesModel
                boundsBehavior: Flickable.StopAtBounds

                add: Transition {
                    ParallelAnimation {
                        NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 260; easing.type: Easing.OutCubic }
                        NumberAnimation { property: "scale"; from: 0.98; to: 1; duration: 260; easing.type: Easing.OutCubic }
                        NumberAnimation { property: "y"; from: 20; duration: 260; easing.type: Easing.OutCubic }
                    }
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
                    anchors.right: parent.right
                    policy: ScrollBar.AsNeeded
                    contentItem: Rectangle {
                        implicitWidth: surface.sp(5)
                        radius: surface.sp(3)
                        color: tokens.inkAlpha(0.13)
                    }
                    background: Rectangle { color: "transparent" }
                }

                delegate: Item {
                    width: chatList.width - surface.sp(32)
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
                                      ? "Operator / " + surface.displayOperatorName()
                                      : "ArkLight / Remote"
                                color: tokens.inkAlpha(0.45)
                                font.family: tokens.monoFont
                                font.pixelSize: surface.sp(10)
                                font.capitalization: Font.AllUppercase
                                anchors.verticalCenter: parent.verticalCenter
                                elide: Text.ElideRight
                                width: Math.min(implicitWidth, surface.sp(260))
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
                            width: Math.min(messageText.implicitWidth + surface.sp(40), messageColumn.width)
                            implicitHeight: messageText.implicitHeight + surface.sp(28)
                            radius: surface.sp(tokens.radiusBubble)
                            color: messageRole === "user" ? tokens.ink : "#FFFFFF"
                            border.color: messageRole === "user"
                                          ? tokens.ink
                                          : (bubbleHover.hovered ? tokens.tealAlpha(0.28) : tokens.inkAlpha(0.08))
                            border.width: messageRole === "user" ? 0 : 1
                            anchors.right: messageRole === "user" ? parent.right : undefined
                            scale: bubbleHover.hovered ? 1.012 : 1

                            HoverHandler {
                                id: bubbleHover
                            }

                            Behavior on scale { NumberAnimation { duration: tokens.fastMotion; easing.type: Easing.OutCubic } }
                            Behavior on border.color { ColorAnimation { duration: 160 } }

                            Text {
                                id: messageText
                                anchors.fill: parent
                                anchors.margins: surface.sp(14)
                                width: parent.width - surface.sp(28)
                                text: messageContent
                                wrapMode: Text.WrapAnywhere
                                color: messageRole === "user" ? "#FFFFFF" : tokens.ink
                                font.family: tokens.sansFont
                                font.pixelSize: surface.sp(15)
                                lineHeight: 1.42
                            }
                        }
                    }
                }

                footer: Item {
                    width: chatList.width - surface.sp(32)
                    height: surface.assistantTyping ? typingColumn.implicitHeight + surface.sp(4) : 0
                    visible: surface.assistantTyping

                    Column {
                        id: typingColumn
                        width: Math.min(chatList.width * 0.86, surface.sp(512))
                        spacing: surface.sp(6)

                        Row {
                            spacing: surface.sp(8)
                            Rectangle {
                                width: surface.sp(8)
                                height: surface.sp(8)
                                radius: surface.sp(4)
                                color: tokens.teal
                                anchors.verticalCenter: parent.verticalCenter
                            }
                            Text {
                                text: "ArkLight / Remote"
                                color: tokens.inkAlpha(0.45)
                                font.family: tokens.monoFont
                                font.pixelSize: surface.sp(10)
                                font.capitalization: Font.AllUppercase
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
                            width: Math.min(typingRow.implicitWidth + surface.sp(40), typingColumn.width)
                            implicitHeight: typingRow.implicitHeight + surface.sp(28)
                            radius: surface.sp(tokens.radiusBubble)
                            color: "#FFFFFF"
                            border.color: tokens.inkAlpha(0.08)
                            border.width: 1

                            Row {
                                id: typingRow
                                anchors.centerIn: parent
                                spacing: surface.sp(8)
                                Repeater {
                                    model: 3
                                    Rectangle {
                                        width: surface.sp(10)
                                        height: surface.sp(10)
                                        radius: surface.sp(5)
                                        color: tokens.teal
                                        SequentialAnimation on opacity {
                                            loops: Animation.Infinite
                                            PauseAnimation { duration: index * 120 }
                                            NumberAnimation { from: 0.35; to: 0.95; duration: 520; easing.type: Easing.InOutSine }
                                            NumberAnimation { from: 0.95; to: 0.35; duration: 520; easing.type: Easing.InOutSine }
                                        }
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
