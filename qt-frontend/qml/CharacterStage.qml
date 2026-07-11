import QtQuick
import QtQuick.Controls
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

    Image {
        id: peripheralTechnicalLayer
        anchors.fill: parent
        source: "../assets/ui/technical-overlays/technical-graphics-raster.png"
        fillMode: Image.PreserveAspectFit
        horizontalAlignment: Image.AlignHCenter
        verticalAlignment: Image.AlignVCenter
        smooth: true
        mipmap: true
        opacity: 0.10
        z: 1
        layer.enabled: true
        layer.effect: MultiEffect {
            brightness: 1.0
            colorization: 1.0
            colorizationColor: "#B8C8C4"
        }
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
        width: stage.width * 1.18
        height: stage.height - stage.sp(58)
        x: stage.width * 0.06
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
        scale: status === Image.Ready ? (stageHover.hovered ? 1.018 : 1) : 0.99
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
            anchors.leftMargin: stage.sp(30)
            anchors.rightMargin: stage.sp(26)
            spacing: stage.sp(15)

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
        x: stage.sp(30)
        y: stage.sp(112)
        width: Math.min(stage.width * 0.38, stage.sp(184))
        spacing: stage.sp(5)
        z: 5

        Text {
            text: "COMMS NODE"
            color: tokens.whiteAlpha(0.62)
            font.family: tokens.monoFont
            font.pixelSize: stage.sp(9)
            font.letterSpacing: stage.sp(0.6)
        }

        Text {
            text: "CH-01"
            color: tokens.whiteAlpha(0.92)
            font.family: tokens.displayFont
            font.pixelSize: stage.sp(33)
            font.weight: Font.DemiBold
            font.letterSpacing: stage.sp(1.2)
        }

        Text {
            text: "NODE ID: ARK-1847A"
            color: tokens.whiteAlpha(0.54)
            font.family: tokens.monoFont
            font.pixelSize: stage.sp(9)
        }

        Item {
            width: parent.width
            height: stage.sp(18)

            Text {
                anchors.left: parent.left
                anchors.verticalCenter: parent.verticalCenter
                text: "LINK:"
                color: tokens.whiteAlpha(0.55)
                font.family: tokens.monoFont
                font.pixelSize: stage.sp(9)
            }

            Text {
                anchors.left: parent.left
                anchors.leftMargin: stage.sp(43)
                anchors.verticalCenter: parent.verticalCenter
                text: "ONLINE"
                color: tokens.teal
                font.family: tokens.monoFont
                font.pixelSize: stage.sp(9)
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

        Item {
            width: 1
            height: stage.sp(4)
        }

        Text {
            text: "FREQ: 7.142.540 MHZ"
            color: tokens.whiteAlpha(0.48)
            font.family: tokens.monoFont
            font.pixelSize: stage.sp(8)
        }

        Text {
            text: "COORD: 37.7749°N, 122.4194°W"
            color: tokens.whiteAlpha(0.42)
            font.family: tokens.monoFont
            font.pixelSize: stage.sp(8)
        }
    }

    Item {
        id: lowerData
        x: stage.sp(30)
        y: stage.height - stage.sp(188)
        width: Math.min(stage.width * 0.36, stage.sp(174))
        height: stage.sp(128)
        z: 5

        Rectangle {
            x: 0
            y: stage.sp(22)
            width: stage.sp(44)
            height: 1
            color: tokens.whiteAlpha(0.28)
            rotation: -48
            transformOrigin: Item.Left
        }

        Rectangle {
            x: stage.sp(34)
            y: stage.sp(54)
            width: 1
            height: stage.sp(24)
            color: tokens.whiteAlpha(0.28)
        }

        Rectangle {
            x: stage.sp(31)
            y: stage.sp(76)
            width: stage.sp(6)
            height: stage.sp(6)
            color: tokens.paperLight
            opacity: 0.55
        }

        Column {
            x: stage.sp(54)
            y: stage.sp(5)
            spacing: stage.sp(4)

            Text {
                text: "ARRAY: DS.N-62"
                color: tokens.whiteAlpha(0.43)
                font.family: tokens.monoFont
                font.pixelSize: stage.sp(8)
            }

            Text {
                text: "BAND: X"
                color: tokens.whiteAlpha(0.38)
                font.family: tokens.monoFont
                font.pixelSize: stage.sp(8)
            }
        }

        Column {
            x: stage.sp(54)
            y: stage.sp(90)
            spacing: stage.sp(4)

            Text {
                text: "RNG: 2.1e+10 km"
                color: tokens.whiteAlpha(0.42)
                font.family: tokens.monoFont
                font.pixelSize: stage.sp(8)
            }

            Text {
                text: "STATUS: STANDBY"
                color: tokens.whiteAlpha(0.38)
                font.family: tokens.monoFont
                font.pixelSize: stage.sp(8)
            }
        }
    }

    Text {
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: stage.sp(22)
        anchors.bottomMargin: stage.sp(20)
        width: stage.width * 0.42
        horizontalAlignment: Text.AlignRight
        text: stage.activeEmotion.length > 0
              ? "PERSONA / " + stage.activeEmotion.toUpperCase()
              : "PERSONA / NEUTRAL"
        color: tokens.whiteAlpha(0.34)
        font.family: tokens.monoFont
        font.pixelSize: stage.sp(8)
        elide: Text.ElideRight
        z: 6
    }
}
