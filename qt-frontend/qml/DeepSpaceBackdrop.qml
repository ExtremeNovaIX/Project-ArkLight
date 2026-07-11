import QtQuick

Item {
    id: backdrop
    property real scaleFactor: 1
    property real rasterOpacity: 0.34

    function sp(value) {
        return Math.max(1, Math.round(value * scaleFactor))
    }

    Canvas {
        id: canvas
        anchors.fill: parent
        antialiasing: true

        function drawRasterFragment(ctx, cx, cy, rx, ry, step, phase, strength) {
            for (let x = Math.max(0, cx - rx); x <= Math.min(width, cx + rx); x += step) {
                for (let y = Math.max(0, cy - ry); y <= Math.min(height, cy + ry); y += step) {
                    const nx = (x - cx) / rx
                    const ny = (y - cy) / ry
                    const distance = Math.sqrt(nx * nx + ny * ny)
                    if (distance > 1.08) {
                        continue
                    }

                    const ring = Math.abs(distance - 0.72) < 0.055
                    const innerRing = Math.abs(distance - 0.48) < 0.035
                    const spoke = distance < 0.88
                                  && Math.abs(Math.sin(Math.atan2(ny, nx) * 7 + phase)) < 0.075
                    const noise = Math.abs(Math.sin(x * 0.083 + y * 0.061 + phase))
                    if (!(ring || innerRing || spoke) || noise < 0.24 + distance * 0.24) {
                        continue
                    }

                    const fade = Math.max(0, 1 - distance)
                    const alpha = (0.025 + fade * 0.11) * strength * backdrop.rasterOpacity
                    const size = Math.max(1, Math.round(step * (0.22 + fade * 0.24)))
                    ctx.fillStyle = "rgba(201,213,210," + alpha + ")"
                    ctx.fillRect(x, y, size, size)
                }
            }
        }

        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            ctx.clearRect(0, 0, width, height)

            const nebula = ctx.createRadialGradient(
                width * 0.88,
                height * 0.11,
                0,
                width * 0.88,
                height * 0.11,
                Math.max(width, height) * 0.34
            )
            nebula.addColorStop(0, "rgba(110,103,126,0.095)")
            nebula.addColorStop(0.42, "rgba(74,83,106,0.045)")
            nebula.addColorStop(1, "rgba(17,25,27,0)")
            ctx.fillStyle = nebula
            ctx.fillRect(0, 0, width, height)

            for (let i = 0; i < 118; i += 1) {
                const x = ((i * 83 + 19) % 509) / 509 * width
                const y = ((i * 149 + 47) % 613) / 613 * height
                const pulse = 0.18 + ((i * 37) % 73) / 73 * 0.42
                const size = i % 17 === 0 ? backdrop.sp(2) : 1
                ctx.fillStyle = "rgba(245,241,225," + pulse + ")"
                ctx.fillRect(x, y, size, size)
            }

            drawRasterFragment(ctx, width * 0.02, height * 0.02, width * 0.27, height * 0.20, backdrop.sp(6), 0.8, 0.8)
            drawRasterFragment(ctx, width * 0.98, height * 0.04, width * 0.25, height * 0.23, backdrop.sp(6), 2.3, 0.62)
            drawRasterFragment(ctx, width * 0.01, height * 0.92, width * 0.34, height * 0.30, backdrop.sp(7), 1.5, 0.82)
            drawRasterFragment(ctx, width * 1.01, height * 0.96, width * 0.31, height * 0.27, backdrop.sp(7), 3.1, 0.56)

            ctx.lineWidth = backdrop.sp(1)
            ctx.strokeStyle = "rgba(229,220,196,0.14)"
            for (let ring = 0; ring < 4; ring += 1) {
                ctx.beginPath()
                ctx.ellipse(
                    width * 0.61,
                    height * 0.51,
                    width * (0.39 + ring * 0.075),
                    height * (0.29 + ring * 0.045),
                    -0.2,
                    Math.PI * 0.82,
                    Math.PI * 2.06
                )
                ctx.stroke()
            }

            ctx.lineWidth = backdrop.sp(2)
            ctx.strokeStyle = "rgba(255,246,222,0.91)"
            ctx.beginPath()
            ctx.moveTo(-width * 0.12, height * 0.59)
            ctx.bezierCurveTo(
                width * 0.17,
                height * 0.68,
                width * 0.62,
                height * 0.54,
                width * 1.09,
                height * 0.32
            )
            ctx.stroke()

            ctx.lineWidth = backdrop.sp(5)
            ctx.strokeStyle = "rgba(242,100,34,0.075)"
            ctx.beginPath()
            ctx.moveTo(-width * 0.12, height * 0.594)
            ctx.bezierCurveTo(
                width * 0.17,
                height * 0.684,
                width * 0.62,
                height * 0.544,
                width * 1.09,
                height * 0.324
            )
            ctx.stroke()

            ctx.fillStyle = "rgba(242,100,34,0.96)"
            ctx.beginPath()
            ctx.arc(width * 0.235, height * 0.606, backdrop.sp(3), 0, Math.PI * 2)
            ctx.fill()
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    onScaleFactorChanged: canvas.requestPaint()
    onRasterOpacityChanged: canvas.requestPaint()
}
