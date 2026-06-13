import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: stage
    property real scaleFactor: 1
    property string workspaceName: "ArkLight Pioneer"
    property string characterName: ""
    property string characterImageUrl: ""
    property string activeEmotion: ""

    color: tokens.blackPanel
    border.color: tokens.whiteAlpha(0.1)
    border.width: 1
    radius: stage.sp(tokens.radiusFrame)
    clip: true

    ArkLightTokens {
        id: tokens
    }

    function sp(value) {
        return Math.round(value * scaleFactor)
    }

    HoverHandler {
        id: stageHover
    }

    SurfaceTexture {
        anchors.fill: parent
        scaleFactor: stage.scaleFactor
        lineColor: "#FFFFFF"
        lineAlpha: 0.012
        geometryAlpha: 0
        drawWatermark: false
    }

    Rectangle {
        anchors.fill: parent
        color: tokens.blackAlpha(0.22)
    }

    Canvas {
        id: contourTexture
        anchors.fill: parent
        opacity: 0.9

        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            ctx.clearRect(0, 0, width, height)
            ctx.strokeStyle = "rgba(255,255,255,0.026)"
            ctx.lineWidth = 1
            const centers = [
                { x: width * 0.18, y: height * 0.40, sx: width * 0.18, sy: height * 0.14 },
                { x: width * 0.66, y: height * 0.68, sx: width * 0.24, sy: height * 0.18 }
            ]
            for (let c = 0; c < centers.length; c += 1) {
                const center = centers[c]
                for (let ring = 0; ring < 9; ring += 1) {
                    ctx.beginPath()
                    for (let i = 0; i <= 120; i += 1) {
                        const a = (Math.PI * 2 * i) / 120
                        const wobble = 1 + Math.sin(a * 3 + ring * 0.7) * 0.045 + Math.cos(a * 5 + ring) * 0.025
                        const rx = (center.sx + ring * stage.sp(16)) * wobble
                        const ry = (center.sy + ring * stage.sp(12)) * wobble
                        const x = center.x + Math.cos(a) * rx
                        const y = center.y + Math.sin(a) * ry
                        if (i === 0) {
                            ctx.moveTo(x, y)
                        } else {
                            ctx.lineTo(x, y)
                        }
                    }
                    ctx.closePath()
                    ctx.globalAlpha = Math.max(0.12, 0.42 - ring * 0.026)
                    ctx.stroke()
                }
            }
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: stage.sp(84)
            color: tokens.blackPanelAlt
            border.color: tokens.whiteAlpha(0.07)
            border.width: 1

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: stage.sp(28)
                anchors.rightMargin: stage.sp(24)
                spacing: stage.sp(16)

                Column {
                    Layout.alignment: Qt.AlignVCenter
                    spacing: stage.sp(5)

                    Repeater {
                        model: 3
                        Rectangle {
                            width: stage.sp(index === 1 ? 30 : 18)
                            height: stage.sp(4)
                            color: tokens.orange
                        }
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.minimumWidth: 0
                    Layout.alignment: Qt.AlignVCenter
                    spacing: stage.sp(4)

                    Text {
                        Layout.fillWidth: true
                        text: stage.workspaceName
                        color: tokens.whiteAlpha(0.92)
                        font.family: tokens.displayFont
                        font.pixelSize: stage.sp(13)
                        font.weight: Font.DemiBold
                        elide: Text.ElideRight
                    }

                    Text {
                        Layout.fillWidth: true
                        text: "Pioneer terminal / local link"
                        color: tokens.whiteAlpha(0.34)
                        font.family: tokens.sansFont
                        font.pixelSize: stage.sp(9)
                        font.capitalization: Font.AllUppercase
                        elide: Text.ElideRight
                    }
                }

                Rectangle {
                    Layout.preferredWidth: stage.sp(54)
                    Layout.preferredHeight: stage.sp(26)
                    color: "transparent"
                    border.color: tokens.orangeAlpha(0.5)
                    border.width: 1

                    Text {
                        anchors.centerIn: parent
                        text: "LIVE"
                        color: tokens.orange
                        font.family: tokens.sansFont
                        font.pixelSize: stage.sp(10)
                        font.weight: Font.DemiBold
                    }
                }
            }
        }

        Item {
            id: portraitDeck
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true

            Rectangle {
                anchors.left: parent.left
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                width: stage.sp(5)
                color: tokens.orange
                opacity: 0.95
            }

            Rectangle {
                id: portraitWell
                anchors.fill: parent
                anchors.leftMargin: stage.sp(26)
                anchors.rightMargin: stage.sp(26)
                anchors.topMargin: stage.sp(28)
                anchors.bottomMargin: stage.sp(28)
                color: tokens.whiteAlpha(stageHover.hovered ? 0.032 : 0.022)
                radius: stage.sp(tokens.radiusFrame)
                border.color: stageHover.hovered ? tokens.whiteAlpha(0.22) : tokens.whiteAlpha(0.12)
                border.width: 1
                clip: true

                Behavior on color { ColorAnimation { duration: tokens.baseMotion } }
                Behavior on border.color { ColorAnimation { duration: tokens.baseMotion } }

                SurfaceTexture {
                    anchors.fill: parent
                    scaleFactor: stage.scaleFactor
                    lineColor: "#FFFFFF"
                    lineAlpha: 0.012
                    geometryAlpha: 0
                    drawWatermark: false
                }

                Rectangle {
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    height: stage.sp(42)
                    color: tokens.blackAlpha(0.42)
                    border.color: tokens.whiteAlpha(0.055)
                    border.width: 1

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: stage.sp(14)
                        anchors.rightMargin: stage.sp(14)
                        spacing: stage.sp(9)

                        Rectangle {
                            Layout.preferredWidth: stage.sp(7)
                            Layout.preferredHeight: stage.sp(7)
                            color: tokens.teal
                        }

                        Text {
                            Layout.fillWidth: true
                            text: stage.characterName.length > 0 ? stage.characterName : "No character selected"
                            color: tokens.whiteAlpha(0.78)
                            font.family: tokens.displayFont
                            font.pixelSize: stage.sp(12)
                            font.weight: Font.DemiBold
                            elide: Text.ElideRight
                        }
                    }
                }

                Image {
                    id: characterImage
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    anchors.bottom: parent.bottom
                    anchors.leftMargin: -stage.sp(52)
                    anchors.rightMargin: -stage.sp(52)
                    anchors.topMargin: stage.sp(18)
                    anchors.bottomMargin: -stage.sp(82)
                    source: stage.characterImageUrl
                    cache: false
                    fillMode: Image.PreserveAspectFit
                    horizontalAlignment: Image.AlignHCenter
                    verticalAlignment: Image.AlignBottom
                    visible: source.toString().length > 0
                    opacity: status === Image.Ready ? 1 : 0
                    scale: status === Image.Ready ? (stageHover.hovered ? 1.03 : 1.0) : 0.985

                    Behavior on opacity { NumberAnimation { duration: tokens.slowMotion; easing.type: Easing.OutCubic } }
                    Behavior on scale { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                }

                IconUser {
                    anchors.centerIn: parent
                    width: Math.min(parent.width * 0.52, stage.sp(180))
                    height: width
                    strokeColor: tokens.whiteAlpha(0.28)
                    visible: stage.characterImageUrl.length === 0
                }

                Rectangle {
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.bottom: parent.bottom
                    height: stage.sp(58)
                    gradient: Gradient {
                        orientation: Gradient.Vertical
                        GradientStop { position: 0; color: Qt.rgba(18 / 255, 17 / 255, 14 / 255, 0) }
                        GradientStop { position: 1; color: tokens.blackAlpha(0.78) }
                    }
                }

                Text {
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.bottom: parent.bottom
                    anchors.leftMargin: stage.sp(14)
                    anchors.rightMargin: stage.sp(14)
                    anchors.bottomMargin: stage.sp(12)
                    text: stage.activeEmotion.length > 0 ? "Emotion / " + stage.activeEmotion : "Emotion / neutral"
                    color: tokens.whiteAlpha(0.48)
                    font.family: tokens.sansFont
                    font.pixelSize: stage.sp(9)
                    font.capitalization: Font.AllUppercase
                    elide: Text.ElideRight
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: stage.sp(132)
            color: tokens.blackPanelAlt
            border.color: tokens.whiteAlpha(0.07)
            border.width: 1

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: stage.sp(28)
                anchors.rightMargin: stage.sp(28)
                spacing: stage.sp(18)

                Rectangle {
                    Layout.preferredWidth: stage.sp(48)
                    Layout.preferredHeight: stage.sp(48)
                    radius: stage.sp(tokens.radiusFrame)
                    color: tokens.whiteAlpha(stageHover.hovered ? 0.11 : 0.07)
                    border.color: tokens.whiteAlpha(0.16)
                    border.width: 1

                    Behavior on color { ColorAnimation { duration: tokens.baseMotion } }

                    IconOrbit {
                        anchors.centerIn: parent
                        width: stage.sp(25)
                        height: stage.sp(25)
                        strokeColor: tokens.orange
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.minimumWidth: 0
                    spacing: stage.sp(6)

                    Text {
                        Layout.fillWidth: true
                        text: "Orbital link"
                        color: tokens.whiteAlpha(0.42)
                        font.family: tokens.sansFont
                        font.pixelSize: stage.sp(10)
                        font.capitalization: Font.AllUppercase
                        elide: Text.ElideRight
                    }

                    Text {
                        Layout.fillWidth: true
                        text: stage.characterName.length > 0 ? stage.characterName : "Established"
                        color: "#FFFFFF"
                        font.family: tokens.displayFont
                        font.pixelSize: stage.sp(16)
                        font.weight: Font.DemiBold
                        elide: Text.ElideRight
                    }
                }
            }
        }
    }
}
