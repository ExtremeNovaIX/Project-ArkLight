import QtQuick

Canvas {
    implicitWidth: 220
    implicitHeight: 220
    property color strokeColor: Qt.rgba(26 / 255, 26 / 255, 26 / 255, 0.35)

    onPaint: {
        const ctx = getContext("2d")
        ctx.reset()
        ctx.strokeStyle = strokeColor
        ctx.lineWidth = 6
        ctx.beginPath()
        ctx.arc(width / 2, height * 0.34, width * 0.14, 0, Math.PI * 2)
        ctx.stroke()
        ctx.beginPath()
        ctx.arc(width / 2, height * 0.72, width * 0.26, Math.PI, Math.PI * 2)
        ctx.stroke()
    }

    onStrokeColorChanged: requestPaint()
}
