import QtQuick

Rectangle {
    id: overlay
    property real scaleFactor: 1
    property string workspaceName: "ArkLight Pioneer"
    property bool active: false
    property bool showTitle: false

    color: "#0A0A0A"
    visible: active || opacity > 0.01
    enabled: active
    opacity: active ? 1 : 0

    Behavior on opacity {
        NumberAnimation { duration: 560; easing.type: Easing.OutCubic }
    }

    ArkLightTokens {
        id: tokens
    }

    function sp(value) {
        return Math.round(value * scaleFactor)
    }

    Canvas {
        anchors.fill: parent
        opacity: 0.2

        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            ctx.strokeStyle = tokens.teal
            ctx.lineWidth = 0.5
            const step = 100
            for (let x = 0; x < width; x += step) {
                ctx.beginPath()
                ctx.moveTo(x, 0)
                ctx.lineTo(x, height)
                ctx.stroke()
            }
            for (let y = 0; y < height; y += step) {
                ctx.beginPath()
                ctx.moveTo(0, y)
                ctx.lineTo(width, y)
                ctx.stroke()
            }
            ctx.translate(width / 2, height / 2)
            ctx.lineWidth = 1
            ctx.beginPath()
            ctx.arc(0, 0, 200, 0, Math.PI * 2)
            ctx.stroke()
            ctx.beginPath()
            ctx.arc(0, 0, 150, 0, Math.PI * 2)
            ctx.stroke()
            ctx.strokeRect(-100, -100, 200, 200)
            ctx.beginPath()
            ctx.moveTo(-200, 0)
            ctx.lineTo(200, 0)
            ctx.moveTo(0, -200)
            ctx.lineTo(0, 200)
            ctx.stroke()
        }
    }

    Item {
        id: titleDeck
        anchors.centerIn: parent
        width: overlay.sp(420)
        height: titleColumn.implicitHeight
        opacity: overlay.showTitle ? 1 : 0
        scale: overlay.showTitle ? 1 : 0.96

        Behavior on opacity {
            NumberAnimation { duration: 360; easing.type: Easing.OutCubic }
        }

        Behavior on scale {
            NumberAnimation { duration: 420; easing.type: Easing.OutBack }
        }

        Column {
            id: titleColumn
            anchors.centerIn: parent
            spacing: overlay.sp(16)
            width: parent.width

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "ARKLIGHT"
                color: "#FFFFFF"
                font.family: tokens.sansFont
                font.pixelSize: overlay.sp(60)
                font.weight: Font.Black
                font.italic: true
            }

            Rectangle {
                anchors.horizontalCenter: parent.horizontalCenter
                width: overlay.showTitle ? overlay.sp(300) : 0
                height: overlay.sp(2)
                color: tokens.orange

                Behavior on width {
                    NumberAnimation { duration: 420; easing.type: Easing.OutCubic }
                }
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: overlay.workspaceName + " / 轨道终端"
                color: tokens.tealAlpha(0.6)
                font.family: tokens.monoFont
                font.pixelSize: overlay.sp(10)
            }
        }
    }

    Rectangle {
        width: parent.width
        height: 1
        color: tokens.teal
        opacity: overlay.active ? 0.5 : 0

        Behavior on opacity {
            NumberAnimation { duration: 220 }
        }

        SequentialAnimation on y {
            loops: Animation.Infinite
            NumberAnimation {
                from: -overlay.height * 0.1
                to: overlay.height * 1.1
                duration: 2000
            }
        }
    }

    Rectangle {
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        anchors.bottomMargin: overlay.sp(36)
        width: overlay.showTitle ? overlay.sp(180) : overlay.sp(72)
        height: overlay.sp(2)
        color: tokens.whiteAlpha(0.4)
        opacity: overlay.showTitle ? 1 : 0

        Behavior on width {
            NumberAnimation { duration: 480; easing.type: Easing.OutCubic }
        }

        Behavior on opacity {
            NumberAnimation { duration: 300 }
        }
    }
}
