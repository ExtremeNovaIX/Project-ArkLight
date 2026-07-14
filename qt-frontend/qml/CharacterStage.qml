import QtQuick
import QtQuick.Effects
import QtQuick.Layouts

Rectangle {
    id: stage
    property real scaleFactor: 1
    property string workspaceName: "ArkLight Pioneer"
    property string characterName: ""
    property string characterImageUrl: ""
    property string activeEmotion: ""

    color: tokens.blackPanel
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

    DeepSpaceBackdrop {
        anchors.fill: parent
        scaleFactor: stage.scaleFactor
        rasterOpacity: 0.32
    }

    Canvas {
        id: characterFocusGlow
        anchors.fill: parent
        antialiasing: true
        opacity: stageHover.hovered ? 0.56 : 0.06
        z: 1

        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            ctx.clearRect(0, 0, width, height)

            const glow = ctx.createRadialGradient(
                width * 0.55,
                height * 0.50,
                0,
                width * 0.55,
                height * 0.50,
                Math.max(width, height) * 0.48
            )
            glow.addColorStop(0, "rgba(116,177,170,0.09)")
            glow.addColorStop(0.42, "rgba(121,139,151,0.036)")
            glow.addColorStop(1, "rgba(17,25,27,0)")
            ctx.fillStyle = glow
            ctx.fillRect(0, 0, width, height)
        }

        Behavior on opacity {
            NumberAnimation {
                duration: tokens.baseMotion
                easing.type: Easing.OutCubic
            }
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    Image {
        id: peripheralTechnicalLayer
        anchors.fill: parent
        source: "../assets/ui/technical-overlays/technical-graphics-raster.png"
        fillMode: Image.PreserveAspectCrop
        horizontalAlignment: Image.AlignHCenter
        verticalAlignment: Image.AlignVCenter
        smooth: true
        mipmap: true
        opacity: 0.10
        z: 1
        layer.enabled: true
        layer.effect: MultiEffect {
            brightness: 0.70
            colorization: 1.0
            colorizationColor: "#AEBDB9"
        }
    }

    AmbientSignalFlow {
        anchors.fill: parent
        scaleFactor: stage.scaleFactor
        mode: "stage"
        coreColor: tokens.paperLight
        haloColor: tokens.orange
        opacity: 0.56
        z: 4
    }

    Rectangle {
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: 1
        color: tokens.whiteAlpha(0.13)
        z: 8
    }

    Image {
        id: characterImage
        width: stage.width * 1.10
        height: stage.height - stage.sp(58)
        x: -stage.width * 0.015
        anchors.bottom: parent.bottom
        anchors.bottomMargin: -stage.sp(8)
        source: stage.characterImageUrl
        cache: false
        asynchronous: true
        mipmap: true
        fillMode: Image.PreserveAspectFit
        horizontalAlignment: Image.AlignHCenter
        verticalAlignment: Image.AlignBottom
        visible: source.toString().length > 0
        opacity: status === Image.Ready ? 1 : 0
        scale: status === Image.Ready ? 1 : 0.995
        transformOrigin: Item.Bottom
        z: 2

        Behavior on opacity {
            NumberAnimation {
                duration: tokens.slowMotion
                easing.type: Easing.OutCubic
            }
        }

        Behavior on scale {
            NumberAnimation {
                duration: tokens.baseMotion
                easing.type: Easing.OutCubic
            }
        }
    }

    IconUser {
        anchors.centerIn: parent
        width: Math.min(parent.width * 0.46, stage.sp(190))
        height: width
        strokeColor: tokens.whiteAlpha(0.24)
        visible: stage.characterImageUrl.length === 0
        z: 2
    }

    Rectangle {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        height: stage.sp(128)
        gradient: Gradient {
            orientation: Gradient.Vertical
            GradientStop {
                position: 0
                color: Qt.rgba(17 / 255, 25 / 255, 27 / 255, 0)
            }
            GradientStop {
                position: 1
                color: Qt.rgba(8 / 255, 12 / 255, 13 / 255, 0.62)
            }
        }
        z: 3
    }

    Item {
        id: header
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        height: stage.sp(78)
        z: 6

        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: stage.sp(24)
            anchors.rightMargin: stage.sp(22)
            spacing: stage.sp(13)

            Text {
                Layout.maximumWidth: stage.width * 0.54
                text: stage.workspaceName.length > 0 ? stage.workspaceName.toUpperCase() : "ARKLIGHT PIONEER"
                color: tokens.whiteAlpha(0.92)
                font.family: tokens.displayFont
                font.pixelSize: stage.sp(23)
                font.weight: Font.DemiBold
                font.letterSpacing: stage.sp(1.1)
                elide: Text.ElideRight
            }

            Rectangle {
                Layout.preferredWidth: 1
                Layout.preferredHeight: stage.sp(28)
                color: tokens.orange
            }

            Text {
                Layout.fillWidth: true
                text: "CASSETTE RELAY"
                color: tokens.orange
                font.family: tokens.displayFont
                font.pixelSize: stage.sp(19)
                font.weight: Font.Bold
                font.letterSpacing: stage.sp(0.8)
                elide: Text.ElideRight
            }
        }

        Rectangle {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            height: 1
            color: tokens.whiteAlpha(0.17)
        }
    }

    Column {
        id: commsBlock
        anchors.left: parent.left
        anchors.top: parent.top
        anchors.leftMargin: stage.sp(16)
        anchors.topMargin: stage.sp(94)
        width: Math.min(stage.width * 0.32, stage.sp(152))
        spacing: stage.sp(3)
        z: 5

        Text {
            text: "COMMS NODE"
            color: tokens.whiteAlpha(0.56)
            font.family: tokens.monoFont
            font.pixelSize: stage.sp(8)
            font.letterSpacing: stage.sp(0.6)
        }

        Text {
            text: "CH-01"
            color: tokens.whiteAlpha(0.90)
            font.family: tokens.displayFont
            font.pixelSize: stage.sp(27)
            font.weight: Font.DemiBold
            font.letterSpacing: stage.sp(1.0)
        }

        Text {
            text: "NODE / ARK-1847A"
            color: tokens.whiteAlpha(0.46)
            font.family: tokens.monoFont
            font.pixelSize: stage.sp(8)
        }

        Item {
            width: parent.width
            height: stage.sp(12)

            Text {
                anchors.left: parent.left
                anchors.verticalCenter: parent.verticalCenter
                text: "LINK"
                color: tokens.whiteAlpha(0.46)
                font.family: tokens.monoFont
                font.pixelSize: stage.sp(8)
            }

            Text {
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                text: "ONLINE"
                color: tokens.statusTeal
                font.family: tokens.monoFont
                font.pixelSize: stage.sp(8)
            }
        }

        Row {
            width: parent.width
            height: stage.sp(2)
            spacing: 0

            Rectangle {
                width: parent.width * 0.32
                height: parent.height
                color: tokens.statusRedLight
            }
            Rectangle {
                width: parent.width * 0.33
                height: parent.height
                color: tokens.statusYellow
            }
            Rectangle {
                width: parent.width * 0.35
                height: parent.height
                color: tokens.statusTeal
            }
        }
    }

    Column {
        id: telemetryRail
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.rightMargin: stage.sp(12)
        anchors.topMargin: stage.sp(96)
        width: Math.min(stage.width * 0.28, stage.sp(132))
        spacing: stage.sp(3)
        z: 5

        Text {
            width: parent.width
            horizontalAlignment: Text.AlignRight
            text: "FREQ / 7.142.540"
            color: tokens.whiteAlpha(0.34)
            font.family: tokens.monoFont
            font.pixelSize: stage.sp(8)
        }

        Text {
            width: parent.width
            horizontalAlignment: Text.AlignRight
            text: "COORD / 37.7749 N"
            color: tokens.whiteAlpha(0.30)
            font.family: tokens.monoFont
            font.pixelSize: stage.sp(8)
        }

        Text {
            width: parent.width
            horizontalAlignment: Text.AlignRight
            text: "ARRAY / DS.N-62"
            color: tokens.whiteAlpha(0.28)
            font.family: tokens.monoFont
            font.pixelSize: stage.sp(8)
        }
    }

    Item {
        id: lowerData
        anchors.left: parent.left
        anchors.bottom: parent.bottom
        anchors.leftMargin: stage.sp(8)
        anchors.bottomMargin: stage.sp(12)
        width: Math.min(stage.width * 0.35, stage.sp(154))
        height: stage.sp(64)
        z: 5

        Rectangle {
            anchors.fill: parent
            radius: stage.sp(1)
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop {
                    position: 0
                    color: tokens.blackAlpha(0.28)
                }
                GradientStop {
                    position: 0.72
                    color: tokens.blackAlpha(0.08)
                }
                GradientStop {
                    position: 1
                    color: tokens.blackAlpha(0)
                }
            }
        }

        Column {
            x: stage.sp(8)
            y: stage.sp(6)
            spacing: stage.sp(2)

            Text {
                text: "RNG / 2.1e+10 km"
                color: tokens.whiteAlpha(0.48)
                font.family: tokens.monoFont
                font.pixelSize: stage.sp(8)
            }

            Text {
                text: "STATUS / STANDBY"
                color: tokens.whiteAlpha(0.42)
                font.family: tokens.monoFont
                font.pixelSize: stage.sp(8)
            }

            Text {
                text: "BAND / X  ·  REF / 62"
                color: tokens.whiteAlpha(0.34)
                font.family: tokens.monoFont
                font.pixelSize: stage.sp(8)
            }
        }

        Item {
            x: stage.sp(8)
            y: stage.sp(48)
            width: stage.sp(92)
            height: stage.sp(10)

            Rectangle {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                height: 1
                color: tokens.whiteAlpha(0.18)
            }

            Repeater {
                model: 7

                Rectangle {
                    required property int index
                    x: index * stage.sp(15)
                    anchors.verticalCenter: parent.verticalCenter
                    width: 1
                    height: index === 0 || index === 6 ? stage.sp(8) : stage.sp(4)
                    color: tokens.whiteAlpha(0.22)
                }
            }
        }
    }

    Text {
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: stage.sp(18)
        anchors.bottomMargin: stage.sp(16)
        width: stage.width * 0.42
        horizontalAlignment: Text.AlignRight
        text: stage.activeEmotion.length > 0
              ? "PERSONA / " + stage.activeEmotion.toUpperCase()
              : "PERSONA / NEUTRAL"
        color: tokens.whiteAlpha(0.30)
        font.family: tokens.monoFont
        font.pixelSize: stage.sp(8)
        elide: Text.ElideRight
        z: 6
    }
}
