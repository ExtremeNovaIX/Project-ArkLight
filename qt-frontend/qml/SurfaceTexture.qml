import QtQuick

Item {
    id: texture
    property real scaleFactor: 1
    property color lineColor: "#786848"
    property real lineAlpha: 0.08
    property real geometryAlpha: 0.035
    property bool drawWatermark: true

    function sp(value) {
        return Math.round(value * scaleFactor)
    }

    Canvas {
        id: textureCanvas
        anchors.fill: parent

        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            ctx.clearRect(0, 0, width, height)

            const unit = Math.max(32, texture.sp(58))
            ctx.strokeStyle = texture.lineColor
            ctx.fillStyle = texture.lineColor
            ctx.lineWidth = 1

            ctx.globalAlpha = texture.lineAlpha
            ctx.beginPath()
            for (let x = -height; x < width + height; x += unit) {
                ctx.moveTo(x, 0)
                ctx.lineTo(x + height, height)
            }
            for (let x = 0; x < width + height; x += unit) {
                ctx.moveTo(x, 0)
                ctx.lineTo(x - height, height)
            }
            ctx.stroke()

            ctx.globalAlpha = texture.lineAlpha * 0.45
            for (let x2 = unit; x2 < width; x2 += unit * 2) {
                for (let y2 = unit; y2 < height; y2 += unit * 2) {
                    ctx.fillRect(x2 - 0.5, y2 - 0.5, 1, 1)
                }
            }

            if (!texture.drawWatermark) {
                return
            }

            ctx.globalAlpha = texture.geometryAlpha
            ctx.lineWidth = Math.max(1, texture.sp(2))
            const cx = width * 0.22
            const cy = height * 0.78
            const radius = Math.min(width, height) * 0.32
            ctx.beginPath()
            ctx.arc(cx, cy, radius, 0, Math.PI * 2)
            ctx.stroke()

            ctx.globalAlpha = texture.geometryAlpha * 0.65
            ctx.lineWidth = Math.max(1, texture.sp(1))
            ctx.strokeRect(width * 0.58, height * 0.12, width * 0.34, height * 0.22)
            ctx.strokeRect(width * 0.64, height * 0.54, width * 0.42, height * 0.26)

            ctx.globalAlpha = texture.geometryAlpha * 0.5
            ctx.beginPath()
            ctx.moveTo(width * 0.08, height * 0.38)
            ctx.lineTo(width * 0.92, height * 0.38)
            ctx.moveTo(width * 0.08, height * 0.62)
            ctx.lineTo(width * 0.92, height * 0.62)
            ctx.stroke()
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    onScaleFactorChanged: textureCanvas.requestPaint()
    onLineColorChanged: textureCanvas.requestPaint()
    onLineAlphaChanged: textureCanvas.requestPaint()
    onGeometryAlphaChanged: textureCanvas.requestPaint()
    onDrawWatermarkChanged: textureCanvas.requestPaint()
}
