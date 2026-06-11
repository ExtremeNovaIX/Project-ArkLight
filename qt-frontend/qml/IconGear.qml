import QtQuick

Canvas {
    implicitWidth: 20
    implicitHeight: 20
    property color strokeColor: "#1A1A1A"

    onPaint: {
        const ctx = getContext("2d")
        ctx.reset()
        ctx.strokeStyle = strokeColor
        ctx.lineWidth = 2
        ctx.translate(width / 2, height / 2)
        for (let i = 0; i < 8; i += 1) {
            ctx.rotate(Math.PI / 4)
            ctx.beginPath()
            ctx.moveTo(0, -height * 0.46)
            ctx.lineTo(0, -height * 0.34)
            ctx.stroke()
        }
        ctx.beginPath()
        ctx.arc(0, 0, height * 0.28, 0, Math.PI * 2)
        ctx.stroke()
        ctx.beginPath()
        ctx.arc(0, 0, height * 0.1, 0, Math.PI * 2)
        ctx.stroke()
    }

    onStrokeColorChanged: requestPaint()
}
