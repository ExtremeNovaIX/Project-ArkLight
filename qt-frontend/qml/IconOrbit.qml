import QtQuick

Canvas {
    implicitWidth: 24
    implicitHeight: 24
    property color strokeColor: "#FFFFFF"

    onPaint: {
        const ctx = getContext("2d")
        ctx.reset()
        ctx.strokeStyle = strokeColor
        ctx.lineWidth = 1.8
        ctx.beginPath()
        ctx.arc(width / 2, height / 2, width * 0.22, 0, Math.PI * 2)
        ctx.stroke()
        ctx.beginPath()
        ctx.ellipse(width / 2, height / 2, width * 0.42, height * 0.18, Math.PI / 5, 0, Math.PI * 2)
        ctx.stroke()
        ctx.beginPath()
        ctx.ellipse(width / 2, height / 2, width * 0.42, height * 0.18, -Math.PI / 5, 0, Math.PI * 2)
        ctx.stroke()
    }

    onStrokeColorChanged: requestPaint()
}
