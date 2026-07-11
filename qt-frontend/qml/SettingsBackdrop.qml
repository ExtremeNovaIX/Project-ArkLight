import QtQuick

Item {
    id: root
    property var host
    property var tokens

    Canvas {
        id: canvas
        anchors.fill: parent

        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            ctx.clearRect(0, 0, width, height)

            const ink = root.tokens ? root.tokens.ink : "#202322"
            ctx.strokeStyle = ink
            ctx.fillStyle = ink
            ctx.lineWidth = 1

            ctx.globalAlpha = 0.025
            const diagonal = Math.max(38, root.host ? root.host.sp(52) : 52)
            ctx.beginPath()
            for (let x = -height; x < width + height; x += diagonal) {
                ctx.moveTo(x, 0)
                ctx.lineTo(x + height, height)
            }
            ctx.stroke()

            ctx.globalAlpha = 0.035
            ctx.beginPath()
            ctx.arc(width * 0.90, height * 0.08, Math.min(width, height) * 0.17, 0, Math.PI * 2)
            ctx.arc(width * 0.90, height * 0.08, Math.min(width, height) * 0.10, 0, Math.PI * 2)
            ctx.stroke()

            ctx.globalAlpha = 0.026
            const dotStep = Math.max(8, root.host ? root.host.sp(11) : 11)
            for (let y = height * 0.70; y < height * 0.96; y += dotStep) {
                for (let x2 = width * 0.72; x2 < width * 0.98; x2 += dotStep) {
                    if (((Math.floor(x2 / dotStep) + Math.floor(y / dotStep)) % 3) !== 0) {
                        ctx.fillRect(x2, y, 1, 1)
                    }
                }
            }

            ctx.globalAlpha = 0.030
            const px = Math.max(2, root.host ? root.host.sp(3) : 3)
            for (let row = 0; row < 46; row++) {
                const widthCount = Math.max(2, Math.floor((46 - row) * 0.48))
                for (let col = 0; col < widthCount; col++) {
                    if ((row + col * 2) % 4 !== 0) {
                        ctx.fillRect(width - (col + 2) * px, height - (row + 2) * px, px, px)
                    }
                }
            }
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    Text {
        anchors.left: parent.left
        anchors.top: parent.top
        anchors.leftMargin: root.host.sp(22)
        anchors.topMargin: root.host.sp(16)
        text: "AXIOM FIELD // 07.14\nLATENT ARRAY 004"
        color: root.tokens.inkAlpha(0.13)
        font.family: root.tokens.monoFont
        font.pixelSize: root.host.sp(8)
        lineHeight: 1.45
    }

    Text {
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.rightMargin: root.host.sp(22)
        anchors.topMargin: root.host.sp(16)
        horizontalAlignment: Text.AlignRight
        text: "ORBITAL GRID // Σ-71\n37.7749°N, 122.4194°W"
        color: root.tokens.inkAlpha(0.12)
        font.family: root.tokens.monoFont
        font.pixelSize: root.host.sp(8)
        lineHeight: 1.45
    }

    Text {
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: root.host.sp(28)
        anchors.bottomMargin: root.host.sp(18)
        horizontalAlignment: Text.AlignRight
        text: "MEGASTRUCTURE FRAGMENT\nLAYER. 07 // SEC. A1-37"
        color: root.tokens.inkAlpha(0.11)
        font.family: root.tokens.monoFont
        font.pixelSize: root.host.sp(8)
        lineHeight: 1.45
    }
}
