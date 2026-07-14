import QtQuick

Item {
    id: clock
    property real scaleFactor: 1
    property string value: "00:00:00"
    property int flipDuration: 142

    implicitWidth: sp(158)
    implicitHeight: sp(34)

    ArkLightTokens {
        id: tokens
    }

    function sp(value) {
        return Math.max(1, Math.round(value * scaleFactor))
    }

    Rectangle {
        anchors.fill: parent
        radius: clock.sp(3)
        color: tokens.inkAlpha(0.030)
        border.color: tokens.inkAlpha(0.16)
        border.width: 1

        Rectangle {
            anchors.fill: parent
            anchors.margins: clock.sp(1)
            radius: clock.sp(2)
            color: tokens.panelAlt
        }

        Row {
            anchors.centerIn: parent
            spacing: clock.sp(2)

            Repeater {
                model: clock.value.length

                Item {
                    id: digitSlot
                    required property int index
                    property string character: clock.value.charAt(digitSlot.index)
                    property string displayedCharacter: ""
                    property string previousCharacter: ""
                    property string incomingCharacter: ""
                    property string pendingCharacter: ""
                    property real topFlipAngle: 0
                    property real bottomFlipAngle: -90
                    property bool flipping: false
                    property bool initialized: false
                    readonly property bool separator: character === ":"
                    width: separator ? clock.sp(8) : clock.sp(18)
                    height: separator ? clock.sp(27) : width * 1.5

                    function settle(characterValue) {
                        displayedCharacter = characterValue
                        previousCharacter = characterValue
                        incomingCharacter = characterValue
                        pendingCharacter = ""
                        topFlipAngle = 0
                        bottomFlipAngle = -90
                        flipping = false
                    }

                    function beginFlip(characterValue) {
                        if (flipAnimation.running) {
                            pendingCharacter = characterValue === incomingCharacter
                                               ? ""
                                               : characterValue
                            return
                        }

                        if (characterValue === displayedCharacter) {
                            pendingCharacter = ""
                            return
                        }

                        previousCharacter = displayedCharacter
                        incomingCharacter = characterValue
                        pendingCharacter = ""
                        topFlipAngle = 0
                        bottomFlipAngle = -90
                        flipping = true
                        flipAnimation.restart()
                    }

                    function finishFlip() {
                        const queuedCharacter = pendingCharacter
                        settle(incomingCharacter)

                        if (queuedCharacter === "" || queuedCharacter === displayedCharacter)
                            return

                        pendingCharacter = queuedCharacter
                        Qt.callLater(function() {
                            const nextCharacter = digitSlot.pendingCharacter !== ""
                                                  ? digitSlot.pendingCharacter
                                                  : digitSlot.character
                            digitSlot.pendingCharacter = ""
                            digitSlot.beginFlip(nextCharacter)
                        })
                    }

                    onCharacterChanged: {
                        if (!initialized) {
                            settle(character)
                            return
                        }

                        if (separator) {
                            settle(character)
                            return
                        }

                        beginFlip(character)
                    }

                    Component.onCompleted: {
                        settle(character)
                        initialized = true
                    }

                    SequentialAnimation {
                        id: flipAnimation

                        NumberAnimation {
                            target: digitSlot
                            property: "topFlipAngle"
                            from: 0
                            to: 90
                            duration: clock.flipDuration * 0.44
                            easing.type: Easing.InCubic
                        }

                        NumberAnimation {
                            target: digitSlot
                            property: "bottomFlipAngle"
                            from: -90
                            to: 0
                            duration: clock.flipDuration * 0.56
                            easing.type: Easing.OutCubic
                        }

                        ScriptAction {
                            script: digitSlot.finishFlip()
                        }
                    }

                    Rectangle {
                        id: digitCell
                        anchors.fill: parent
                        visible: !digitSlot.separator
                        radius: clock.sp(1)
                        border.color: tokens.inkAlpha(0.06)
                        border.width: 1
                        color: tokens.inputPaper

                        Item {
                            id: topBase
                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.top: parent.top
                            height: parent.height / 2
                            clip: true

                            Image {
                                width: digitCell.width
                                height: digitCell.height
                                source: "../assets/ui/clock/split-flap-cell-texture.png"
                                fillMode: Image.Stretch
                                smooth: true
                                mipmap: true
                                cache: true
                                opacity: 0.56
                            }

                            Text {
                                width: digitCell.width
                                height: digitCell.height
                                y: -clock.sp(1)
                                text: digitSlot.flipping
                                      ? digitSlot.incomingCharacter
                                      : digitSlot.displayedCharacter
                                color: tokens.ink
                                font.family: tokens.displayFont
                                font.pixelSize: clock.sp(20)
                                font.weight: Font.DemiBold
                                horizontalAlignment: Text.AlignHCenter
                                verticalAlignment: Text.AlignVCenter
                            }
                        }

                        Item {
                            id: bottomBase
                            anchors.left: parent.left
                            anchors.right: parent.right
                            y: digitCell.height / 2
                            height: digitCell.height / 2
                            clip: true

                            Image {
                                width: digitCell.width
                                height: digitCell.height
                                y: -digitCell.height / 2
                                source: "../assets/ui/clock/split-flap-cell-texture.png"
                                fillMode: Image.Stretch
                                smooth: true
                                mipmap: true
                                cache: true
                                opacity: 0.56
                            }

                            Text {
                                width: digitCell.width
                                height: digitCell.height
                                y: -digitCell.height / 2 - clock.sp(1)
                                text: digitSlot.flipping
                                      ? digitSlot.previousCharacter
                                      : digitSlot.displayedCharacter
                                color: tokens.ink
                                font.family: tokens.displayFont
                                font.pixelSize: clock.sp(20)
                                font.weight: Font.DemiBold
                                horizontalAlignment: Text.AlignHCenter
                                verticalAlignment: Text.AlignVCenter
                            }
                        }

                        Item {
                            id: topFlap
                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.top: parent.top
                            height: parent.height / 2
                            visible: digitSlot.flipping
                            clip: true
                            z: 4
                            antialiasing: true
                            layer.enabled: digitSlot.flipping
                            layer.smooth: true

                            Image {
                                width: digitCell.width
                                height: digitCell.height
                                source: "../assets/ui/clock/split-flap-cell-texture.png"
                                fillMode: Image.Stretch
                                smooth: true
                                mipmap: true
                                cache: true
                                opacity: 0.64
                            }

                            Text {
                                width: digitCell.width
                                height: digitCell.height
                                y: -clock.sp(1)
                                text: digitSlot.previousCharacter
                                color: tokens.ink
                                font.family: tokens.displayFont
                                font.pixelSize: clock.sp(20)
                                font.weight: Font.DemiBold
                                horizontalAlignment: Text.AlignHCenter
                                verticalAlignment: Text.AlignVCenter
                            }

                            Rectangle {
                                anchors.fill: parent
                                color: tokens.blackAlpha(0.015 + Math.abs(digitSlot.topFlipAngle) / 90 * 0.14)
                            }

                            transform: Rotation {
                                origin.x: topFlap.width / 2
                                origin.y: topFlap.height
                                axis.x: 1
                                axis.y: 0
                                axis.z: 0
                                angle: digitSlot.topFlipAngle
                            }
                        }

                        Item {
                            id: bottomFlap
                            anchors.left: parent.left
                            anchors.right: parent.right
                            y: digitCell.height / 2
                            height: digitCell.height / 2
                            visible: digitSlot.flipping
                            clip: true
                            z: 5
                            antialiasing: true
                            layer.enabled: digitSlot.flipping
                            layer.smooth: true

                            Image {
                                width: digitCell.width
                                height: digitCell.height
                                y: -digitCell.height / 2
                                source: "../assets/ui/clock/split-flap-cell-texture.png"
                                fillMode: Image.Stretch
                                smooth: true
                                mipmap: true
                                cache: true
                                opacity: 0.64
                            }

                            Text {
                                width: digitCell.width
                                height: digitCell.height
                                y: -digitCell.height / 2 - clock.sp(1)
                                text: digitSlot.incomingCharacter
                                color: tokens.ink
                                font.family: tokens.displayFont
                                font.pixelSize: clock.sp(20)
                                font.weight: Font.DemiBold
                                horizontalAlignment: Text.AlignHCenter
                                verticalAlignment: Text.AlignVCenter
                            }

                            Rectangle {
                                anchors.fill: parent
                                color: tokens.blackAlpha(0.015 + Math.abs(digitSlot.bottomFlipAngle) / 90 * 0.12)
                            }

                            transform: Rotation {
                                origin.x: bottomFlap.width / 2
                                origin.y: 0
                                axis.x: 1
                                axis.y: 0
                                axis.z: 0
                                angle: digitSlot.bottomFlipAngle
                            }
                        }

                        Rectangle {
                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            height: 1
                            color: tokens.inkAlpha(0.08)
                            z: 7
                        }

                        Rectangle {
                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.top: parent.top
                            anchors.leftMargin: 1
                            anchors.rightMargin: 1
                            height: 1
                            color: tokens.whiteAlpha(0.08)
                            z: 7
                        }
                    }

                    Text {
                        anchors.centerIn: parent
                        visible: digitSlot.separator
                        text: ":"
                        color: tokens.inkAlpha(0.62)
                        font.family: tokens.displayFont
                        font.pixelSize: clock.sp(17)
                        font.weight: Font.DemiBold
                        horizontalAlignment: Text.AlignHCenter
                        verticalAlignment: Text.AlignVCenter
                    }
                }
            }
        }

        Repeater {
            model: 2

            Rectangle {
                required property int index
                x: index === 0 ? clock.sp(4) : parent.width - clock.sp(7)
                y: clock.sp(4)
                width: clock.sp(3)
                height: width
                radius: width / 2
                color: tokens.inkAlpha(0.18)

                Rectangle {
                    anchors.centerIn: parent
                    width: parent.width
                    height: 1
                    color: tokens.paperLight
                    opacity: 0.52
                    rotation: index === 0 ? -24 : 28
                }
            }
        }
    }
}