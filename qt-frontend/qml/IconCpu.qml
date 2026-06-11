import QtQuick

Canvas {
    implicitWidth: 20
    implicitHeight: 20
    property color strokeColor: Qt.rgba(26 / 255, 26 / 255, 26 / 255, 0.2)

    onPaint: {
        const ctx = getContext("2d")
        ctx.reset()
        ctx.strokeStyle = strokeColor
        ctx.lineWidth = 1.6
        ctx.strokeRect(width * 0.28, height * 0.28, width * 0.44, height * 0.44)
        for (let i = 0; i < 4; i += 1) {
            const p = width * (0.22 + i * 0.18)
            ctx.beginPath()
            ctx.moveTo(p, height * 0.08)
            ctx.lineTo(p, height * 0.2)
            ctx.moveTo(p, height * 0.8)
            ctx.lineTo(p, height * 0.92)
            ctx.moveTo(width * 0.08, p)
            ctx.lineTo(width * 0.2, p)
            ctx.moveTo(width * 0.8, p)
            ctx.lineTo(width * 0.92, p)
            ctx.stroke()
        }
    }

    onStrokeColorChanged: requestPaint()
}
