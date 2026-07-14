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
    property string relayTime: "00:00:00"
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
        return operatorName.length > 0 ? operatorName : "Local"
    }

    function refreshRelayTime() {
        relayTime = Qt.formatTime(new Date(), "hh:mm:ss")
    }

    function scheduleRelayTick() {
        const now = Date.now()
        relayTimer.interval = Math.max(16, 1002 - now % 1000)
        relayTimer.restart()
    }

    Component.onCompleted: {
        refreshRelayTime()
        scheduleRelayTick()
    }

    Timer {
        id: relayTimer
        repeat: false
        onTriggered: {
            surface.refreshRelayTime()
            surface.scheduleRelayTick()
        }
    }

    TechnicalBackdrop {
        anchors.fill: parent
        scaleFactor: surface.scaleFactor
    }

    AmbientSignalFlow {
        anchors.fill: parent
        scaleFactor: surface.scaleFactor
        mode: "surface"
        coreColor: tokens.teal
        haloColor: tokens.orange
        opacity: 0.52
        z: 1
    }

    Item {
        id: pioneerRail
        anchors.left: parent.left
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.topMargin: surface.sp(96)
        anchors.bottomMargin: surface.sp(112)
        width: surface.sp(44)
        z: 0

        Text {
            anchors.centerIn: parent
            width: pioneerRail.height
            text: "PIONEER"
            rotation: -90
            color: tokens.inkAlpha(0.034)
            font.family: tokens.displayFont
            font.pixelSize: surface.sp(52)
            font.weight: Font.Light
            font.letterSpacing: surface.sp(1.1)
            horizontalAlignment: Text.AlignHCenter
        }
    }

    Column {
        anchors.left: parent.left
        anchors.bottom: parent.bottom
        anchors.leftMargin: surface.sp(58)
        anchors.bottomMargin: surface.sp(148)
        spacing: surface.sp(4)
        opacity: 0.32
        z: 0

        Text {
            text: "ORBITAL ARC: 9.2°"
            color: tokens.inkAlpha(0.42)
            font.family: tokens.monoFont
            font.pixelSize: surface.sp(8)
        }

        Text {
            text: "RING DIA.: 128,600 km"
            color: tokens.inkAlpha(0.42)
            font.family: tokens.monoFont
            font.pixelSize: surface.sp(8)
        }

        Text {
            text: "STN ID: PNR-07"
            color: tokens.inkAlpha(0.42)
            font.family: tokens.monoFont
            font.pixelSize: surface.sp(8)
        }
    }

    Column {
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: surface.sp(18)
        anchors.bottomMargin: surface.sp(144)
        spacing: surface.sp(4)
        opacity: 0.28
        z: 0

        Text {
            text: "REF. AX-7"
            color: tokens.inkAlpha(0.44)
            font.family: tokens.monoFont
            font.pixelSize: surface.sp(8)
        }

        Text {
            text: "VER. 2.7.1"
            color: tokens.inkAlpha(0.44)
            font.family: tokens.monoFont
            font.pixelSize: surface.sp(8)
        }

        Text {
            text: "X: 37.7749° N"
            color: tokens.inkAlpha(0.44)
            font.family: tokens.monoFont
            font.pixelSize: surface.sp(8)
        }

        Text {
            text: "Y: 122.4194° W"
            color: tokens.inkAlpha(0.44)
            font.family: tokens.monoFont
            font.pixelSize: surface.sp(8)
        }
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0
        z: 2

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: surface.sp(78)
            color: tokens.whiteAlpha(0.10)
            border.color: tokens.inkAlpha(0.14)
            border.width: 1

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: surface.sp(24)
                anchors.rightMargin: surface.sp(22)
                spacing: surface.sp(18)

                Item {
                    Layout.preferredWidth: surface.sp(194)
                    Layout.fillHeight: true

                    Column {
                        anchors.left: parent.left
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: surface.sp(3)

                        Row {
                            spacing: surface.sp(8)

                            Text {
                                text: "RELAY INDEX"
                                color: tokens.inkAlpha(0.48)
                                font.family: tokens.monoFont
                                font.pixelSize: surface.sp(8)
                                font.letterSpacing: surface.sp(0.8)
                            }

                            Text {
                                text: "MECH / 08"
                                color: tokens.inkAlpha(0.28)
                                font.family: tokens.monoFont
                                font.pixelSize: surface.sp(7)
                                font.letterSpacing: surface.sp(0.5)
                            }
                        }

                        MechanicalCounterClock {
                            scaleFactor: surface.scaleFactor
                            value: surface.relayTime
                        }
                    }
                }

                Item {
                    Layout.fillWidth: true
                }

                Column {
                    Layout.alignment: Qt.AlignVCenter
                    spacing: surface.sp(5)

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
                            text: "ONLINE"
                            color: tokens.inkAlpha(0.80)
                            font.family: tokens.displayFont
                            font.pixelSize: surface.sp(13)
                            font.weight: Font.Medium
                            anchors.verticalCenter: parent.verticalCenter
                        }
                    }

                    Row {
                        spacing: 0

                        Rectangle {
                            width: surface.sp(28)
                            height: surface.sp(2)
                            color: tokens.statusRedLight
                        }
                        Rectangle {
                            width: surface.sp(28)
                            height: surface.sp(2)
                            color: tokens.statusYellow
                        }
                        Rectangle {
                            width: surface.sp(32)
                            height: surface.sp(2)
                            color: tokens.statusTeal
                        }
                    }
                }

                Button {
                    id: settingsButton
                    Layout.preferredWidth: surface.sp(94)
                    Layout.preferredHeight: surface.sp(44)
                    Layout.alignment: Qt.AlignVCenter
                    focusPolicy: Qt.StrongFocus
                    hoverEnabled: true

                    contentItem: Row {
                        anchors.centerIn: parent
                        spacing: surface.sp(8)

                        IconGear {
                            anchors.verticalCenter: parent.verticalCenter
                            width: surface.sp(18)
                            height: surface.sp(18)
                            strokeColor: tokens.orange
                        }

                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: "设置"
                            color: tokens.ink
                            font.family: tokens.sansFont
                            font.pixelSize: surface.sp(12)
                            font.weight: Font.DemiBold
                        }
                    }

                    background: Rectangle {
                        radius: surface.sp(5)
                        color: settingsButton.hovered ? tokens.paperLight : tokens.inputPaper
                        border.color: settingsButton.hovered || settingsButton.activeFocus
                                      ? tokens.orangeAlpha(0.78)
                                      : tokens.orangeAlpha(0.54)
                        border.width: 1

                        Rectangle {
                            anchors.fill: parent
                            anchors.margins: -surface.sp(3)
                            radius: surface.sp(8)
                            color: "transparent"
                            border.color: tokens.orangeAlpha(0.18)
                            border.width: 1
                            opacity: settingsButton.hovered || settingsButton.activeFocus ? 1 : 0

                            Behavior on opacity {
                                NumberAnimation {
                                    duration: tokens.fastMotion
                                    easing.type: Easing.OutCubic
                                }
                            }
                        }

                        Behavior on color {
                            ColorAnimation {
                                duration: tokens.fastMotion
                            }
                        }

                        Behavior on border.color {
                            ColorAnimation {
                                duration: tokens.fastMotion
                            }
                        }
                    }

                    onClicked: surface.openSettings()
                }
            }
        }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true

            ListView {
                id: chatList
                anchors.fill: parent
                anchors.leftMargin: surface.sp(52)
                anchors.rightMargin: surface.sp(26)
                anchors.topMargin: surface.sp(42)
                anchors.bottomMargin: surface.sp(26)
                spacing: surface.sp(26)
                clip: true
                model: surface.messagesModel
                boundsBehavior: Flickable.StopAtBounds

                add: Transition {
                    ParallelAnimation {
                        NumberAnimation {
                            property: "opacity"
                            from: 0
                            to: 1
                            duration: tokens.baseMotion
                            easing.type: Easing.OutCubic
                        }
                        NumberAnimation {
                            property: "y"
                            from: surface.sp(12)
                            duration: tokens.baseMotion
                            easing.type: Easing.OutCubic
                        }
                    }
                }

                displaced: Transition {
                    NumberAnimation {
                        properties: "x,y"
                        duration: tokens.fastMotion
                        easing.type: Easing.OutCubic
                    }
                }

                function scrollToEnd() {
                    Qt.callLater(function() {
                        chatList.positionViewAtEnd()
                    })
                }

                ScrollBar.vertical: ScrollBar {
                    id: chatScrollBar
                    parent: chatList.parent
                    anchors.top: chatList.top
                    anchors.bottom: chatList.bottom
                    anchors.right: parent.right
                    anchors.rightMargin: surface.sp(6)
                    implicitWidth: surface.sp(10)
                    policy: ScrollBar.AsNeeded
                    hoverEnabled: true
                    z: 3

                    contentItem: Item {
                        implicitWidth: surface.sp(10)

                        Rectangle {
                            anchors.top: parent.top
                            anchors.bottom: parent.bottom
                            anchors.right: parent.right
                            width: surface.sp(2)
                            radius: surface.sp(1)
                            color: tokens.inkAlpha(chatScrollBar.active ? 0.28 : 0.12)

                            Behavior on color {
                                ColorAnimation {
                                    duration: tokens.fastMotion
                                }
                            }
                        }
                    }

                    background: Rectangle {
                        color: "transparent"
                    }
                }

                delegate: Item {
                    id: messageItem
                    width: chatList.width
                    height: messageColumn.implicitHeight
                    opacity: 1

                    property bool fromUser: messageRole === "user"

                    Column {
                        id: messageColumn
                        width: Math.min(chatList.width * 0.82, surface.sp(560))
                        anchors.left: messageItem.fromUser ? undefined : parent.left
                        anchors.right: messageItem.fromUser ? parent.right : undefined
                        spacing: surface.sp(8)

                        Row {
                            spacing: surface.sp(9)
                            layoutDirection: messageItem.fromUser ? Qt.RightToLeft : Qt.LeftToRight
                            anchors.right: messageItem.fromUser ? parent.right : undefined

                            Rectangle {
                                width: surface.sp(7)
                                height: surface.sp(7)
                                color: messageItem.fromUser ? tokens.orange : tokens.teal
                                anchors.verticalCenter: parent.verticalCenter
                            }

                            Text {
                                width: Math.min(implicitWidth, surface.sp(270))
                                text: messageItem.fromUser
                                      ? "Operator / " + surface.displayOperatorName()
                                      : "ArkLight / Remote"
                                color: messageItem.fromUser ? tokens.orange : tokens.tealDark
                                font.family: tokens.displayFont
                                font.pixelSize: surface.sp(13)
                                font.weight: Font.DemiBold
                                elide: Text.ElideRight
                                anchors.verticalCenter: parent.verticalCenter
                            }
                        }

                        Item {
                            width: parent.width
                            height: messageText.implicitHeight + surface.sp(24)

                            Rectangle {
                                x: messageItem.fromUser ? parent.width - 1 : 0
                                y: 0
                                width: 1
                                height: parent.height
                                color: messageItem.fromUser ? tokens.orangeAlpha(0.23) : tokens.tealAlpha(0.22)
                            }

                            Text {
                                id: messageText
                                anchors.left: parent.left
                                anchors.right: parent.right
                                anchors.top: parent.top
                                anchors.leftMargin: surface.sp(16)
                                anchors.rightMargin: surface.sp(16)
                                text: messageContent
                                wrapMode: Text.Wrap
                                horizontalAlignment: messageItem.fromUser ? Text.AlignRight : Text.AlignLeft
                                color: tokens.ink
                                font.family: tokens.sansFont
                                font.pixelSize: surface.sp(14)
                                lineHeight: 1.5
                            }

                            Text {
                                anchors.left: parent.left
                                anchors.right: parent.right
                                anchors.bottom: parent.bottom
                                anchors.leftMargin: surface.sp(16)
                                anchors.rightMargin: surface.sp(16)
                                text: "SEQ / " + (index < 9 ? "0" : "") + (index + 1)
                                horizontalAlignment: messageItem.fromUser ? Text.AlignRight : Text.AlignLeft
                                color: tokens.inkAlpha(0.34)
                                font.family: tokens.monoFont
                                font.pixelSize: surface.sp(8)
                            }
                        }
                    }
                }

                footer: Item {
                    width: chatList.width
                    height: surface.assistantTyping ? typingColumn.implicitHeight + surface.sp(8) : 0
                    visible: surface.assistantTyping

                    Column {
                        id: typingColumn
                        width: Math.min(chatList.width * 0.82, surface.sp(560))
                        spacing: surface.sp(8)

                        Row {
                            spacing: surface.sp(9)

                            Rectangle {
                                width: surface.sp(7)
                                height: surface.sp(7)
                                color: tokens.teal
                                anchors.verticalCenter: parent.verticalCenter
                            }

                            Text {
                                text: "ArkLight / Remote"
                                color: tokens.tealDark
                                font.family: tokens.displayFont
                                font.pixelSize: surface.sp(13)
                                font.weight: Font.DemiBold
                            }
                        }

                        Row {
                            spacing: surface.sp(8)

                            Repeater {
                                model: 3

                                Rectangle {
                                    width: surface.sp(7)
                                    height: surface.sp(7)
                                    color: tokens.teal

                                    SequentialAnimation on opacity {
                                        loops: Animation.Infinite
                                        PauseAnimation {
                                            duration: index * 110
                                        }
                                        NumberAnimation {
                                            from: 0.2
                                            to: 0.85
                                            duration: 460
                                            easing.type: Easing.InOutSine
                                        }
                                        NumberAnimation {
                                            from: 0.85
                                            to: 0.2
                                            duration: 460
                                            easing.type: Easing.InOutSine
                                        }
                                    }
                                }
                            }

                            Text {
                                text: "对方正在输入..."
                                color: tokens.inkAlpha(0.58)
                                font.family: tokens.sansFont
                                font.pixelSize: surface.sp(13)
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
            id: composer
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
    }
}
