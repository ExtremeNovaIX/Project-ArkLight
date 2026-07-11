import QtQuick

Item {
    id: backdrop
    property real scaleFactor: 1

    function sp(value) {
        return Math.max(1, Math.round(value * scaleFactor))
    }

    Image {
        anchors.fill: parent
        source: "../assets/ui/technical-overlays/megastructure-raster.png"
        fillMode: Image.PreserveAspectCrop
        horizontalAlignment: Image.AlignRight
        verticalAlignment: Image.AlignVCenter
        smooth: true
        mipmap: true
        opacity: 0.032
    }

    Image {
        anchors.fill: parent
        source: "../assets/ui/technical-overlays/technical-graphics-raster.png"
        fillMode: Image.PreserveAspectCrop
        horizontalAlignment: Image.AlignHCenter
        verticalAlignment: Image.AlignVCenter
        smooth: true
        mipmap: true
        opacity: 0.022
    }

    Canvas {
        id: canvas
        anchors.fill: parent
        antialiasing: true

        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            ctx.clearRect(0, 0, width, height)

            const hatch = Math.max(backdrop.sp(18), 14)
            ctx.strokeStyle = "rgba(101,89,70,0.014)"
            ctx.lineWidth = 1
            for (let x = -height; x < width + height; x += hatch) {
                ctx.beginPath()
                ctx.moveTo(x, 0)
                ctx.lineTo(x + height, height)
                ctx.stroke()
            }

            const cx = width * 0.62
            const cy = height * 0.55
            const rx = width * 0.42
            const ry = height * 0.43
            const cell = Math.max(backdrop.sp(6), 5)
            for (let px = Math.max(0, cx - rx * 1.15); px < Math.min(width, cx + rx * 1.15); px += cell) {
                for (let py = Math.max(0, cy - ry * 1.15); py < Math.min(height, cy + ry * 1.15); py += cell) {
                    const nx = (px - cx) / rx
                    const ny = (py - cy) / ry
                    const radius = Math.sqrt(nx * nx + ny * ny)
                    const angle = Math.atan2(ny, nx)
                    const outerRing = Math.abs(radius - 0.91) < 0.028
                    const innerRing = Math.abs(radius - 0.66) < 0.025
                    const coreRing = Math.abs(radius - 0.34) < 0.022
                    const spoke = radius > 0.31 && radius < 0.94
                                  && Math.abs(Math.sin(angle * 9 + 0.35)) < 0.052
                    const cradle = Math.abs(ny - 0.34 * Math.sin(nx * 4.2)) < 0.035
                                    && Math.abs(nx) < 0.92
                    const gate = Math.abs(Math.sin(px * 0.071 + py * 0.047)) > 0.38 + Math.max(0, radius - 0.65) * 0.25
                    if ((outerRing || innerRing || coreRing || spoke || cradle) && gate) {
                        const alpha = 0.009 + Math.max(0, 0.95 - radius) * 0.010
                        ctx.fillStyle = "rgba(73,67,57," + alpha + ")"
                        const block = radius < 0.42 ? Math.max(1, Math.round(cell * 0.38)) : Math.max(1, Math.round(cell * 0.26))
                        ctx.fillRect(px, py, block, block)
                    }
                }
            }

            ctx.strokeStyle = "rgba(70,78,67,0.038)"
            ctx.lineWidth = 1
            const contourX = width * 0.84
            const contourY = height * 0.14
            for (let ring = 0; ring < 8; ring += 1) {
                ctx.beginPath()
                for (let i = 0; i <= 84; i += 1) {
                    const a = Math.PI * 2 * i / 84
                    const wobble = 1 + Math.sin(a * 3 + ring * 0.72) * 0.08 + Math.cos(a * 5) * 0.035
                    const crx = width * (0.085 + ring * 0.017) * wobble
                    const cry = height * (0.052 + ring * 0.011) * wobble
                    const x2 = contourX + Math.cos(a) * crx
                    const y2 = contourY + Math.sin(a) * cry
                    if (i === 0) {
                        ctx.moveTo(x2, y2)
                    } else {
                        ctx.lineTo(x2, y2)
                    }
                }
                ctx.closePath()
                ctx.stroke()
            }

            function cross(x, y, size) {
                ctx.beginPath()
                ctx.moveTo(x - size, y)
                ctx.lineTo(x + size, y)
                ctx.moveTo(x, y - size)
                ctx.lineTo(x, y + size)
                ctx.stroke()
            }

            ctx.strokeStyle = "rgba(67,75,66,0.07)"
            cross(width * 0.54, height * 0.12, backdrop.sp(8))
            cross(width * 0.93, height * 0.46, backdrop.sp(9))
            cross(width * 0.36, height * 0.78, backdrop.sp(8))
            cross(width * 0.81, height * 0.72, backdrop.sp(7))

            ctx.strokeStyle = "rgba(74,67,55,0.10)"
            ctx.beginPath()
            ctx.moveTo(width * 0.08, height * 0.78)
            ctx.lineTo(width * 0.30, height * 0.78)
            for (let tick = 0; tick <= 10; tick += 1) {
                const tx = width * 0.08 + width * 0.022 * tick
                ctx.moveTo(tx, height * 0.77)
                ctx.lineTo(tx, height * 0.79)
            }
            ctx.stroke()
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    onScaleFactorChanged: canvas.requestPaint()
}
