from dash import Dash, html, dcc, Output, Input

app = Dash(__name__)

app.layout = html.Div([
    html.H3("Joystick Demo", style={"color": "white", "textAlign": "center"}),
    html.Div([
        html.Canvas(id="joystick", width=100, height=100, style={
            "backgroundColor": "#1e1e1e",
            "border": "2px solid #2c2c2c",
            "borderRadius": "50%",
            "display": "block",
            "margin": "auto"
        })
    ]),
    dcc.Store(id="joystick-refresh", data=0),
    html.Div(id="joystick-output", style={"textAlign": "center", "color": "#ccc", "marginTop": "10px"})
], style={"backgroundColor": "#121212", "height": "100vh", "paddingTop": "40px"})

app.clientside_callback(
    """
    function(n) {
        const canvas = document.getElementById("joystick");
        const output = document.getElementById("joystick-output");
        if (!canvas || !output) return window.dash_clientside.no_update;

        const ctx = canvas.getContext("2d");
        const radius = 15;
        const center = { x: canvas.width / 2, y: canvas.height / 2 };
        let x = center.x;
        let y = center.y;
        let dragging = false;

        function draw() {
            ctx.clearRect(0, 0, canvas.width, canvas.height);
            ctx.beginPath();
            ctx.arc(x, y, radius, 0, 2 * Math.PI);
            ctx.fillStyle = "#1f6feb";
            ctx.fill();
            const dx = x - center.x;
            const dy = y - center.y;
            output.textContent = `Vector: (${dx.toFixed(1)}, ${dy.toFixed(1)})`;
        }

        function animateReturn() {
            const dx = center.x - x;
            const dy = center.y - y;
            const dist = Math.sqrt(dx * dx + dy * dy);
            if (dist < 1) {
                x = center.x;
                y = center.y;
                draw();
                return;
            }
            x += dx * 0.2;
            y += dy * 0.2;
            draw();
            requestAnimationFrame(animateReturn);
        }

        canvas.addEventListener('mousedown', e => {
            const rect = canvas.getBoundingClientRect();
            const mx = e.clientX - rect.left;
            const my = e.clientY - rect.top;
            const dx = mx - center.x;
            const dy = my - center.y;
            const dist = Math.sqrt(dx * dx + dy * dy);
            const maxDist = canvas.width / 2 - radius;
            if (dist <= canvas.width / 2) {
                dragging = true;
                if (dist > maxDist) {
                    const scale = maxDist / dist;
                    x = center.x + dx * scale;
                    y = center.y + dy * scale;
                } else {
                    x = mx;
                    y = my;
                }
                draw();
            }
        });

        window.addEventListener('mousemove', e => {
            if (!dragging) return;
            const rect = canvas.getBoundingClientRect();
            const mx = e.clientX - rect.left;
            const my = e.clientY - rect.top;
            const dx = mx - center.x;
            const dy = my - center.y;
            const dist = Math.sqrt(dx * dx + dy * dy);
            const maxDist = canvas.width / 2 - radius;
            if (dist > maxDist) {
                const scale = maxDist / dist;
                x = center.x + dx * scale;
                y = center.y + dy * scale;
            } else {
                x = mx;
                y = my;
            }
            draw();
        });

        window.addEventListener('mouseup', () => {
            if (dragging) {
                dragging = false;
                animateReturn();
            }
        });

        draw();
        return window.dash_clientside.no_update;
    }
    """,
    Output("joystick-refresh", "data"),
    Input("joystick-refresh", "data")
)

if __name__ == "__main__":
    app.run(debug=True)
