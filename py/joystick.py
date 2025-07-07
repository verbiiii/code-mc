from dash import Dash, html, dcc, Output, Input

def create_joystick_component(canvas_id="joystick", output_id="joystick-output", store_id="joystick-refresh"):
    layout = html.Div([
        html.Canvas(id=canvas_id, width=100, height=100, style={
            "backgroundColor": "#1e1e1e",
            "border": "2px solid #2c2c2c",
            "borderRadius": "50%",
            "display": "block",
            "margin": "auto"
        }),
        dcc.Store(id=store_id, data=0),
        html.Div(id=output_id, style={"textAlign": "center", "color": "#ccc", "marginTop": "10px", "whiteSpace": "pre-line"})
    ])
    return layout

def register_joystick_callback(app, canvas_id="joystick", output_id="joystick-output", store_id="joystick-refresh"):
    app.clientside_callback(
        f"""
        function(n) {{
            const canvas = document.getElementById("{canvas_id}");
            const output = document.getElementById("{output_id}");
            if (!canvas || !output) return window.dash_clientside.no_update;

            const ctx = canvas.getContext("2d");
            const radius = 15;
            const center = {{ x: canvas.width / 2, y: canvas.height / 2 }};
            let x = center.x;
            let y = center.y;
            let dragging = false;

            function draw() {{
                ctx.clearRect(0, 0, canvas.width, canvas.height);
                ctx.beginPath();
                ctx.arc(x, y, radius, 0, 2 * Math.PI);
                ctx.fillStyle = "#7CFC00";  // light green
                ctx.fill();
                const dx = x - center.x;
                const dy = y - center.y;
                const maxDist = canvas.width / 2 - radius;
                const normX = +(dx / maxDist).toFixed(1);
                const normY = +(dy / maxDist).toFixed(1);
                let angleDeg = Math.atan2(dy, dx) * (180 / Math.PI);
                angleDeg = (angleDeg + 360) % 360;
                angleDeg = (angleDeg + 90) % 360;
                output.textContent = `Vector: (${{normX}}, ${{normY}})\nAngle: ${{angleDeg.toFixed(1)}}\u00B0`;
            }}

            function animateReturn() {{
                const dx = center.x - x;
                const dy = center.y - y;
                const dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < 1) {{
                    x = center.x;
                    y = center.y;
                    draw();
                    return;
                }}
                x += dx * 0.2;
                y += dy * 0.2;
                draw();
                requestAnimationFrame(animateReturn);
            }}

            canvas.addEventListener('mousedown', e => {{
                const rect = canvas.getBoundingClientRect();
                const mx = e.clientX - rect.left;
                const my = e.clientY - rect.top;
                const dx = mx - center.x;
                const dy = my - center.y;
                const dist = Math.sqrt(dx * dx + dy * dy);
                const maxDist = canvas.width / 2 - radius;
                if (dist <= canvas.width / 2) {{
                    dragging = true;
                    if (dist > maxDist) {{
                        const scale = maxDist / dist;
                        x = center.x + dx * scale;
                        y = center.y + dy * scale;
                    }} else {{
                        x = mx;
                        y = my;
                    }}
                    draw();
                }}
            }});

            window.addEventListener('mousemove', e => {{
                if (!dragging) return;
                const rect = canvas.getBoundingClientRect();
                const mx = e.clientX - rect.left;
                const my = e.clientY - rect.top;
                const dx = mx - center.x;
                const dy = my - center.y;
                const dist = Math.sqrt(dx * dx + dy * dy);
                const maxDist = canvas.width / 2 - radius;
                if (dist > maxDist) {{
                    const scale = maxDist / dist;
                    x = center.x + dx * scale;
                    y = center.y + dy * scale;
                }} else {{
                    x = mx;
                    y = my;
                }}
                draw();
            }});

            window.addEventListener('mouseup', () => {{
                if (dragging) {{
                    dragging = false;
                    animateReturn();
                }}
            }});

            draw();
            return window.dash_clientside.no_update;
        }}
        """,
        Output(store_id, "data"),
        Input(store_id, "data")
    )

if __name__ == "__main__":
    app = Dash(__name__)
    app.layout = html.Div([
        html.H3("Joystick Demo", style={"color": "white", "textAlign": "center"}),
        create_joystick_component(),
    ], style={"backgroundColor": "#121212", "height": "100vh", "paddingTop": "40px"})
    register_joystick_callback(app)
    app.run(debug=True)