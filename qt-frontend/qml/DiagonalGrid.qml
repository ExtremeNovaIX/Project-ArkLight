import QtQuick

Canvas {
    id: grid
    property int step: 80
    property color lineColor: "#1A1A1A"
    property real lineAlpha: 0.085
    property bool drawDiagonals: true

    onPaint: {
        const ctx = getContext("2d")
        ctx.reset()
        ctx.strokeStyle = lineColor
        ctx.globalAlpha = lineAlpha
        ctx.lineWidth = 1

        for (let x = 0; x <= width + step; x += step) {
            ctx.beginPath()
            ctx.moveTo(x + 0.5, 0)
            ctx.lineTo(x + 0.5, height)
            ctx.stroke()
        }
        for (let y = 0; y <= height + step; y += step) {
            ctx.beginPath()
            ctx.moveTo(0, y + 0.5)
            ctx.lineTo(width, y + 0.5)
            ctx.stroke()
        }
        if (!drawDiagonals) {
            return
        }
        for (let d = -height; d < width + height; d += step) {
            ctx.beginPath()
            ctx.moveTo(d, 0)
            ctx.lineTo(d + height, height)
            ctx.stroke()
            ctx.beginPath()
            ctx.moveTo(d, height)
            ctx.lineTo(d + height, 0)
            ctx.stroke()
        }
    }

    onWidthChanged: requestPaint()
    onHeightChanged: requestPaint()
    onStepChanged: requestPaint()
    onLineColorChanged: requestPaint()
    onLineAlphaChanged: requestPaint()
    onDrawDiagonalsChanged: requestPaint()
}
