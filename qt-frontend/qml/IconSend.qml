import QtQuick

Canvas {
    implicitWidth: 28
    implicitHeight: 28
    property color strokeColor: "#FFFFFF"

    onPaint: {
        const ctx = getContext("2d")
        ctx.reset()
        ctx.strokeStyle = strokeColor
        ctx.lineWidth = 2.2
        ctx.lineJoin = "round"
        ctx.lineCap = "round"
        ctx.beginPath()
        ctx.moveTo(width * 0.14, height * 0.5)
        ctx.lineTo(width * 0.86, height * 0.16)
        ctx.lineTo(width * 0.62, height * 0.86)
        ctx.closePath()
        ctx.stroke()
        ctx.beginPath()
        ctx.moveTo(width * 0.14, height * 0.5)
        ctx.lineTo(width * 0.56, height * 0.56)
        ctx.stroke()
    }

    onStrokeColorChanged: requestPaint()
}
