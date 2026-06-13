import QtQuick

Rectangle {
    id: overlay
    property real scaleFactor: 1
    property string workspaceName: "ArkLight Pioneer"
    property bool active: false
    property bool showTitle: false
    readonly property var titleLetters: ["A", "r", "k", "L", "i", "g", "h", "t"]
    readonly property var riseOffsets: [42, 18, 56, 28, 64, 34, 20, 50]
    readonly property var riseDelays: [180, 20, 280, 100, 340, 60, 240, 140]

    color: tokens.paper
    visible: active || opacity > 0.01
    enabled: active
    opacity: active ? 1 : 0

    Behavior on opacity {
        NumberAnimation { duration: 720; easing.type: Easing.InOutCubic }
    }

    ArkLightTokens {
        id: tokens
    }

    function sp(value) {
        return Math.round(value * scaleFactor)
    }

    SurfaceTexture {
        anchors.fill: parent
        scaleFactor: overlay.scaleFactor
        lineColor: tokens.chatGrid
        lineAlpha: 0.04
        geometryAlpha: 0.018
    }

    Rectangle {
        anchors.fill: parent
        color: tokens.paperLight
        opacity: 0.26
    }

    Item {
        id: titleDeck
        anchors.centerIn: parent
        width: Math.min(parent.width - overlay.sp(80), overlay.sp(720))
        height: overlay.sp(190)
        opacity: overlay.showTitle ? 1 : 0

        Behavior on opacity { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }

        Row {
            id: titleRow
            anchors.horizontalCenter: parent.horizontalCenter
            y: overlay.sp(36)
            spacing: overlay.sp(1)

            Repeater {
                model: overlay.titleLetters

                Item {
                    width: glyph.implicitWidth + overlay.sp(2)
                    height: overlay.sp(82)

                    Text {
                        id: glyph
                        x: 0
                        y: overlay.showTitle ? 0 : overlay.sp(overlay.riseOffsets[index])
                        opacity: overlay.showTitle ? 1 : 0
                        text: modelData
                        color: tokens.ink
                        font.family: tokens.displayFont
                        font.pixelSize: overlay.sp(62)
                        font.weight: Font.Light

                        states: State {
                            name: "shown"
                            when: overlay.showTitle
                            PropertyChanges {
                                target: glyph
                                y: 0
                                opacity: 1
                            }
                        }

                        transitions: Transition {
                            to: "shown"
                            SequentialAnimation {
                                PauseAnimation { duration: overlay.riseDelays[index] }
                                ParallelAnimation {
                                    NumberAnimation { properties: "y"; duration: 980; easing.type: Easing.OutCubic }
                                    NumberAnimation { properties: "opacity"; duration: 560; easing.type: Easing.OutCubic }
                                }
                            }
                        }
                    }
                }
            }
        }

        Item {
            id: reflectionClip
            anchors.horizontalCenter: titleRow.horizontalCenter
            y: titleRow.y + overlay.sp(58)
            width: titleRow.width
            height: overlay.sp(48)
            clip: true
            opacity: overlay.showTitle ? 1 : 0

            Row {
                id: reflectionRow
                anchors.horizontalCenter: parent.horizontalCenter
                y: -overlay.sp(24)
                spacing: titleRow.spacing
                opacity: 0.18
                transform: Scale {
                    origin.x: reflectionRow.width / 2
                    origin.y: reflectionRow.height / 2
                    yScale: -1
                }

                Repeater {
                    model: overlay.titleLetters

                    Item {
                        width: reflectionGlyph.implicitWidth + overlay.sp(2)
                        height: overlay.sp(82)

                        Text {
                            id: reflectionGlyph
                            x: 0
                            y: overlay.showTitle ? 0 : overlay.sp(overlay.riseOffsets[index])
                            opacity: overlay.showTitle ? 1 : 0
                            text: modelData
                            color: tokens.ink
                            font.family: tokens.displayFont
                            font.pixelSize: overlay.sp(62)
                            font.weight: Font.Light

                            states: State {
                                name: "shown"
                                when: overlay.showTitle
                                PropertyChanges {
                                    target: reflectionGlyph
                                    y: 0
                                    opacity: 1
                                }
                            }

                            transitions: Transition {
                                to: "shown"
                                SequentialAnimation {
                                    PauseAnimation { duration: overlay.riseDelays[index] }
                                    ParallelAnimation {
                                        NumberAnimation { properties: "y"; duration: 980; easing.type: Easing.OutCubic }
                                        NumberAnimation { properties: "opacity"; duration: 560; easing.type: Easing.OutCubic }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Rectangle {
                anchors.fill: parent
                gradient: Gradient {
                    orientation: Gradient.Vertical
                    GradientStop { position: 0; color: Qt.rgba(246 / 255, 240 / 255, 220 / 255, 0.12) }
                    GradientStop { position: 1; color: Qt.rgba(246 / 255, 240 / 255, 220 / 255, 0.82) }
                }
            }
        }

        Rectangle {
            anchors.horizontalCenter: parent.horizontalCenter
            y: titleRow.y + overlay.sp(118)
            width: overlay.showTitle ? overlay.sp(210) : 0
            height: 1
            color: tokens.inkAlpha(0.28)

            Behavior on width { NumberAnimation { duration: 680; easing.type: Easing.OutCubic } }
        }

        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            y: titleRow.y + overlay.sp(132)
            width: parent.width
            text: overlay.workspaceName
            color: tokens.inkAlpha(0.42)
            font.family: tokens.sansFont
            font.pixelSize: overlay.sp(11)
            font.weight: Font.DemiBold
            horizontalAlignment: Text.AlignHCenter
            elide: Text.ElideRight
        }
    }
}
